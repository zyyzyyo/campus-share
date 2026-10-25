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
| PATCH | `/api/orders/{id}/actions/{action}` | 无需请求体，当前状态允许时执行动作 |

| action | 操作人 | 状态变化 |
| --- | --- | --- |
| approve | 发布者 | PENDING_REVIEW → AWAITING_PICKUP，检查时段冲突 |
| reject | 发布者 | PENDING_REVIEW → REJECTED |
| cancel | 借用人 | PENDING_REVIEW / AWAITING_PICKUP → CANCELLED |
| pickup | 发布者 | AWAITING_PICKUP → BORROWING，必须已到开始时间且未超过领取截止时间 |
| request-return | 借用人 | BORROWING → RETURN_REQUESTED |
| complete | 发布者 | RETURN_REQUESTED → COMPLETED |

同一操作重复请求且订单仍处于其目标状态时直接返回，不重复写日志。状态已经推进到其他状态时返回 409。每次真实状态变化与日志处于同一事务；所有订单变更先锁物品行。不能预约自己物品，同一用户不能重复申请重叠的待审核时段；不同用户的待审核申请可重叠，审核通过才占用时段。

待审核和待领取订单由每分钟定时任务检查超时。领取时还会检查是否有尚未实际归还的订单，防止仅因前一预约到期便把同一实物再次交付。借用中的逾期订单不自动完成。详细状态图和截止时间见 [业务说明](business.md)。

常见错误：400 参数非法或借自己物品，401 未认证/过期，403 越权，404 物品/订单/图片不存在，409 状态或时段冲突，413 上传过大，503 数据库或文件服务不可用。
