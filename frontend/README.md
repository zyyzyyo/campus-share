#Vue 3 前端（已接后端）

首页新增“校园今日热门”，展示当天浏览热度、排名和校区，可点击查看物品详情，并支持手动刷新、空榜提示和失败重试。榜单按北京时间每日切换，只展示当前上架物品。

热榜浏览器回归脚本为 `scripts/popular-smoke.cjs`，依赖与业务回归脚本相同，执行 `node scripts/popular-smoke.cjs`。请使用独立且为空的 demo 环境，脚本创建2个测试账号和2件物品，验证浏览去重、排名、详情更新、下架过滤、失败重试及390px布局，截图输出至 `backend/target/browser-tests`。

新增“消息通知”入口，支持分页、未读数量、全部已读和关联订单详情，未读数每30秒更新。订单支持续期申请/审批、逾期时长、发布者催还、取消原因展示。借出管理可在领取前填写原因取消订单；提前领取若影响其他预约会提示冲突。列表提供手动刷新。

新增浏览器回归脚本 `scripts/business-smoke.cjs`，在独立演示环境运行，会创建测试账号和物品。需要 Playwright 与本机 Edge，可通过 `PLAYWRIGHT_MODULE` 指向单独安装的 Playwright，`FRONTEND_URL` 指定前端地址。执行 `node scripts/business-smoke.cjs`；默认目标为 `http://127.0.0.1:15174`，截图保存在 `backend/target/browser-tests`。

已添加 IDEA 一键运行配置，无需手动输入启动命令，操作见 [IDEA 运行说明](../.run/README.md)。

需要 Node.js 20.19+ 或 22.12+。先启动 Redis（默认 `127.0.0.1:6379`，见 [后端说明](../backend/README.md)），再启动 Java 后端和前端：

```powershell
cd "D:\desktop\校园闲置物品借用平台\backend"
mvn -s .mvn/central-settings.xml spring-boot:run "-Dspring-boot.run.profiles=demo"
```

另开一个终端：

```powershell
cd "D:\desktop\校园闲置物品借用平台\frontend"
npm install
npm run dev
```

打开终端显示的地址，默认 `http://127.0.0.1:5173`。已安装依赖时无需重复 `npm install`。后端健康检查为 `http://127.0.0.1:8080/api/health`。

## 体验流程

1. 右上角“登录 / 注册”→“注册账号”，填写账号、昵称和密码，注册后自动登录。
2. “发布闲置”，填写物品、分类、校区、位置与描述，可选上传 1MB 以内的 JPG、PNG 或 WebP 图片。
3. 用另一浏览器或隐私窗口注册第二个账号，在物品详情选时间并提交预约。
4. 发布者进入“借出管理”同意或拒绝申请。借用人在“我的借用”查看状态，可取消未领取的订单。
5. 审核通过后可提前领取，发布者当面交付并点击“确认领取”；借用人点击“申请归还”，发布者检查物品后点击“确认归还”。

页面初始为空，不再注入示例库存。老版本 localStorage 里的物品、昵称、收藏与订单不参与真实业务，也不会自动上传。所有权限按后端用户 ID 判断，昵称相同不代表同一个账号。

## 请求与数据

- `src/api.js`：统一请求、令牌附带、HTTP/业务错误、401 清理。
- `src/App.vue`：页面和接口调用，包含异步加载、防重复提交、请求取消、空状态及失败重试。
- Vite 开发和预览将 `/api` 代理到 `http://127.0.0.1:8080`。图片也通过此路径读取。
- 后端地址不同，可在启动前设置 `$env:API_TARGET = 'http://127.0.0.1:实际端口'`。
- 登录令牌仅在当前标签页的 sessionStorage 保存，刷新后通过后端恢复身份。业务数据不再存 localStorage。
- `demo` 后端使用 H2 内存库，重启会清空账号及记录；要持久保存请按 [后端说明](../backend/README.md) 配置 MySQL。
- “只看可借”按上架状态筛选；是否能借所选时段由提交和审核时的后端冲突检查决定。
- 不同用户的操作不会自动推送到其他窗口；重新进入页面或点击“刷新订单”读取最新结果。

## 构建及检查

```powershell
npm run build
npm run preview
```

生产环境须配置同源 `/api` 反向代理到 Java 后端，构建出的静态文件自身不包含后端。

浏览器联调脚本使用本机 Microsoft Edge、Python 与 Playwright。先启动前后端，再运行：

```powershell
python -m pip install playwright
python scripts/smoke.py
```

默认检查 `http://127.0.0.1:5173`，可通过 `FRONTEND_URL` 环境变量修改。脚本会创建两组随机命名的测试账号及若干测试物品，请在演示或专用测试数据库运行。覆盖真实注册登录、图片上传、发布与修改、上下架、搜索、收藏、预约、审核、取消、同名身份隔离、刷新恢复、请求失败与重试、退出、390px 手机布局，以及取消第二页最后一个收藏时自动返回有效页。

后端登录会话现保存在 Redis，升级后需要重新登录。登录、注册和预约超限时会显示“操作过于频繁”，等待后再试；Redis 不可用返回 503，不会自动退出当前页面账号。
