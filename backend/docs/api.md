# 前后端接口约定

所有 JSON 响应为 `{ "code": "OK", "message": "操作成功", "data": ... }`。失败返回对应 HTTP 状态及业务码，不把失败包装为 HTTP 200。认证头为 `Authorization: Bearer <accessToken>`。日期请求必须有时区，订单使用 ISO 8601 UTC，例如 `2030-05-01T08:00:00Z`；前端将本地选择转换成 UTC 再提交。

## 认证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/register` | `{username,password,nickname}`，返回 201 与用户信息 |
| POST | `/api/auth/login` | `{username,password}`，返回 `{accessToken,tokenType,expiresAt,user}` |
| GET | `/api/auth/me` | 受保护，返回 `{id,username,nickname}` |
| POST | `/api/auth/logout` | 撤销当前令牌 |

账号 4–32 位字母、数字或下划线，忽略大小写；密码 8–64 字符且不超过 72 个 UTF-8 字节；昵称最多 32 字符。未知的 JSON 字段被拒绝，不能通过请求中的用户 ID 决定操作者。

## 物品与图片

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/categories` | 公开，分类 `{id,name}` 数组 |
| GET | `/api/items` | 公开列表；查询本人/收藏范围需要认证 |
| GET | `/api/items/{id}` | 公开详情 |
| GET | `/api/items/{id}/reservations` | 公开已预约时段，只返回开始时间、结束时间和状态 |
| POST | `/api/items` | 当前用户发布，返回 201 |
| PUT | `/api/items/{id}` | 仅发布者修改 |
| PATCH | `/api/items/{id}/status` | 仅发布者上下架，`{status:"ON_SHELF"或"OFF_SHELF"}` |
| POST | `/api/media` | 认证后上传，multipart 字段 `file`，返回 201 和 `{url}` |
| GET | `/api/media/{name}` | 公开读取图片，实际图片类型响应 |

列表参数：`scope=all|mine|favorites`，`q` 为字面关键词（最多 100 字符），`categoryId`、`campus`、`availableOnly=true|false`、`sort=recommended|newest`、`page`（从 1 开始）、`size`（1–50，默认 12）。返回 `{items,total,page,size}`。推荐排序先上架物品，再按发布时间与 ID 倒序。

发布和修改请求：

```json
{
  "title": "单反相机",
  "description": "功能正常，附镜头与充电器",
  "categoryId": 1,
  "campus": "东校区",
  "pickupLocation": "图书馆门口",
  "imageUrl": null
}
```

图片为可选，只能使用本人经 `/api/media` 上传得到的地址，不能引用任意远程地址或其他人的上传。校区为东校区、南校区或西校区。每条物品对应一件实物，状态不表示某个未来时段一定可借。未上架物品仍可查看详情或收藏，但不能提交新预约或同意尚未通过的申请。

## 收藏

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/favorites` | 本人收藏的物品 ID 数组 |
| PUT | `/api/favorites/{itemId}` | 收藏；重复调用幂等 |
| DELETE | `/api/favorites/{itemId}` | 取消收藏；重复调用幂等 |

## 订单

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/orders` | `role=borrowed|lent`，分页参数同物品，返回本人的借用或借出订单 |
| POST | `/api/orders` | `{itemId,startTime,endTime}`，返回 201，状态 `PENDING_REVIEW` |
| PATCH | `/api/orders/{id}/actions/{action}` | 当前状态允许时执行动作；仅 owner-cancel 必须传 `{reason}`，其他动作无需请求体 |
| GET | `/api/orders/{id}` | 订单详情，仅订单双方可访问 |
| POST | `/api/orders/{id}/renewal` | 借用人提交 `{endTime}`，UTC ISO 8601，必须晚于当前订单结束时间 |
| PATCH | `/api/orders/{id}/renewal/approve` | 发布者同意续期，无请求体，重新检查时间和冲突 |
| PATCH | `/api/orders/{id}/renewal/reject` | 发布者拒绝续期，无请求体 |

| action | 操作人 | 状态变化 |
| --- | --- | --- |
| approve | 发布者 | PENDING_REVIEW → AWAITING_PICKUP，检查时段冲突 |
| reject | 发布者 | PENDING_REVIEW → REJECTED |
| cancel | 借用人 | PENDING_REVIEW / AWAITING_PICKUP → CANCELLED |
| owner-cancel | 发布者 | PENDING_REVIEW / AWAITING_PICKUP → CANCELLED，请求体 `{reason:"无法交付的原因"}`，非空、最多500字 |
| remind | 发布者 | 仅逾期 BORROWING / RETURN_REQUESTED，可每天催还一次，状态不变 |
| pickup | 发布者 | AWAITING_PICKUP → BORROWING，支持提前领取，但不能超过领取截止时间，且物品必须已实际归还 |
| request-return | 借用人 | BORROWING → RETURN_REQUESTED |
| complete | 发布者 | RETURN_REQUESTED → COMPLETED |

同一操作重复请求且订单仍处于其目标状态时直接返回，不重复写日志。状态已经推进到其他状态时返回 409。每次真实状态变化与日志处于同一事务；所有订单变更先锁物品行。不能预约自己物品，同一用户不能重复申请重叠的待审核时段；不同用户的待审核申请可重叠，审核通过才占用时段。

待审核和待领取订单由每分钟定时任务检查超时。领取时还会检查是否有尚未实际归还的订单，防止仅因前一预约到期便把同一实物再次交付。借用中的逾期订单不自动完成。详细状态图和截止时间见 [业务说明](business.md)。

常见错误：400 参数非法或借自己物品，401 未认证/过期，403 越权，404 物品/订单/图片不存在，409 状态或时段冲突，413 上传过大，503 数据库或文件服务不可用。

已预约时段返回数组 `[{startTime,endTime,status}]`，时间为 UTC ISO 8601，按开始时间排序。仅包含结束时间晚于当前时间的待领取、借用中、待确认归还订单，不包含待审核、取消、拒绝、完成或已超时订单，不暴露借用人的个人信息。前端按浏览器本地时间显示，提供手动刷新及重叠提示；最终冲突仍由提交和审核接口校验。

订单列表和详情新增字段：`pickedUpAt`（实际领取时间）、`renewalEndTime`（本轮申请的结束时间）、`renewalStatus`（NONE/PENDING/APPROVED/REJECTED/CANCELLED）、`cancelReason`、`overdue`、`overdueMinutes`。时间均为 UTC；nullable 时间尚未发生时返回 null。公开时段 startTime 在领取后使用实际领取时间。续期只有审核通过才修改 endTime；申请或审批冲突、到期后申请/同意均返回409。

有逾期未归还订单的用户无法提交新预约、通过新预约审核或领取其他订单，返回409；完成归还后自动解除。逾期标识实时计算，催还消息按北京时间自然日去重。

## 站内消息

以下接口全部要求认证，身份来自令牌，不接受 userId 指定接收人。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/notifications?page=1&size=12` | 本人消息分页，page 1–100000、size 1–50 |
| GET | `/api/notifications/unread-count` | data 为未读数量 |
| PATCH | `/api/notifications/{id}/read` | 标记本人指定消息已读，重复、其他人的或不存在的 ID 不产生变化 |
| PATCH | `/api/notifications/read-all` | 标记本人所有未读消息已读 |

消息字段为 `{id,orderId,content,readAt,createdAt}`，时间为 UTC ISO 8601，未读 readAt 为 null；按创建时间和 ID 倒序。点击消息后可通过受权限保护的订单详情接口读取关联订单。消息采用数据库持久化，前端每30秒查询未读数，不依赖 WebSocket 或外部推送服务。


## 详情缓存与热门物品

`GET /api/items/{id}` 保持原返回格式，公开详情默认缓存2分钟。修改和上下架成功提交后清理缓存，数据库事务回滚不会发布未提交数据。预约和交付校验不读取详情缓存。成功的详情 GET 会尝试记录一次热度；登录发布者本人、下架物品及10分钟内的同访客重复浏览不重复计数。

`GET /api/items/popular?limit=6` 无需登录，limit为1–20，非法参数返回400。data 示例：

```json
{
  "available": true,
  "items": [
    {"item": {"id": 12, "title": "相机", "status": "ON_SHELF"}, "views": 8}
  ]
}
```

其中 item 是完整 ItemView，与普通物品详情字段一致，示例仅展示部分字段；views 为当天按10分钟窗口去重的浏览次数。按北京时间每日切换，只返回当前上架物品，按热度降序。无记录返回 available=true、items=[]；热榜存储或读取故障返回 available=false、items=[]，页面可独立重试。重复查询热榜不增加热度。

匿名访客以 TCP remoteAddr 去重，登录访客以账号 ID 去重；不信任客户端 X-Forwarded-For / X-Real-IP。缓存及热度记录故障不会使公开详情失败；带令牌请求的认证仍遵循下述会话故障规则。

## Redis 会话和限流

登录后会话保存在 Redis，默认固定有效期 8 小时，访问不续期，退出删除当前 token 对应的会话。token 摘要用作键，原 MySQL auth_session 不再读写，升级后需要重新登录。Redis 不可用返回 503，不清理前端身份；会话不存在或过期返回 401。

登录、注册分别按 IP 每分钟最多 10、5 次，提交预约按认证用户 ID 每分钟最多 10 次；失败请求也消耗额度，各接口独立计数，固定窗口从第一次请求开始。超限返回 HTTP 429：`{"code":"RATE_LIMITED","message":"操作过于频繁，请稍后重试","data":null}`，响应头 `Retry-After` 给出可重试的等待秒数。受限操作遇到 Redis 故障返回 503，不继续执行业务。
