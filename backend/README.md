
# 校园物品借用平台 · Java 后端

Redis 新增物品详情缓存与校园今日热门榜。详情缓存默认2分钟，修改/上下架在事务提交后失效，Redis异常时回源数据库；浏览热度用 Sorted Set 按北京时间每天统计，同一访客10分钟内对同一物品只计一次，已登录发布者本人不计入。热榜只展示当前上架物品。配置为 application.yml 的 app.items.cache-ttl / popularity-window，无需新增数据库迁移；预约与交付始终由数据库校验。

新增业务：站内通知、续期申请与审批、逾期限制和催还、提前领取实际时段冲突校验、发布者填写原因取消。重启后 Flyway 自动执行 V3，保留已有数据，无需手动建表。消息入口在前端“消息通知”，续期与催还在订单卡片中。规则见 [业务说明](docs/business.md)，接口见 [API 说明](docs/api.md)。

已添加 IDEA 一键运行配置，使用本机 JDK 17，操作见 [IDEA 运行说明](../.run/README.md)。

已与 Vue 3 前端对接：账号注册登录、物品发布与修改、搜索筛选分页、图片上传、上下架、收藏、预约申请、审核、领取、归还、取消及超时处理。数据由后端数据库保存，用户身份来自认证令牌。前端不再读取旧 localStorage 演示数据。

## 技术与目录

Java 17 / Spring Boot 3.5.16 / Spring MVC / MyBatis-Plus 3.5.17 / Spring Security / MySQL 8.0+ / Flyway。源码按 Java 17 编译，允许使用兼容的更高版本 JDK。本机发现的是 JDK 25。版本要求参考 [Spring Boot 官方文档](https://docs.spring.io/spring-boot/3.5/system-requirements.html) 和 [MyBatis 官方兼容说明](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)。

```text
backend/
  pom.xml
  src/main/java/com/campus/share/
    CampusShareApplication.java
    controller/       HTTP 接口、请求校验、调用 Service，禁止直接访问 Mapper
    service/          业务接口
      impl/           业务实现、权限、事务和状态流转
    mapper/           MyBatis 数据访问接口
    entity/           数据库实体和查询行
    dto/              请求参数
    vo/               返回数据
    security/         认证用户身份
    common/           统一响应、业务异常、异常处理
    config/           安全与基础配置
    task/             定时任务，只调用 Service
  src/main/resources/
    application.yml
    application-demo.yml
    application-mysql.yml
    db/migration/     V1 核心表、V2 收藏及图片上传记录、V3 消息与续期
    mapper/           MyBatis XML 查询
  src/test/           H2 集成测试
  requests/auth.http  可直接在 IDEA HTTP Client 执行的接口样例
```

## 先用演示配置运行

需要 JDK 17+ 和 Maven 3.6.3+。在项目根目录打开 PowerShell：

```powershell
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=demo"
```

启动后访问 `http://127.0.0.1:8080/api/health`。`data.status` 应为 `UP`。

`demo` 使用 H2 内存数据库，启动时自动建表，仍需先启动 Redis；**重启后用户和会话都会清空**。不需要安装、启动或配置 MySQL；不创建默认账号，先注册自己的测试账号。

在 IDEA 中运行：

1. 将 `backend/pom.xml` 添加为 Maven 项目并等待依赖导入。
2. 项目 SDK / Maven Runner 使用 JDK 17 或兼容版本。
3. 打开 `CampusShareApplication` 的运行配置，将 Active profiles 设置为 `demo`；也可以在 Program arguments 填写 `--spring.profiles.active=demo`。
4. 运行启动类，打开 `requests/auth.http`，依次执行健康检查、注册、登录、当前用户、退出。

## 切换 MySQL

MySQL 8.0+ 需要已启动。用你自己的数据库管理员账号建立一个专用数据库：

```sql
CREATE DATABASE campus_share CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

为该库配置一个有建表、索引和读写权限的开发账号。不要在 Java、YAML 或 Git 中写真实密码。在同一个 PowerShell 窗口设置环境变量：

```powershell
$env:DB_USERNAME = '你的数据库账号'
$env:DB_PASSWORD = '你的数据库密码'
# 若 MySQL 不在默认地址，才需要设置 DB_URL
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/campus_share?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC'
mvn spring-boot:run "-Dspring-boot.run.profiles=mysql"
```

在 IDEA 的运行配置中，也可将 Active profiles 设置为 `mysql`，并填写 Environment variables。Spring Boot 不会自动读取 `.env`。

Flyway 会创建 10 张业务及认证表并写入 5 个分类，另有一张 Flyway 历史表。已有数据库会依次执行尚未执行的迁移；V2 增加收藏和上传记录，V3 增加消息表及订单扩展字段。不要手动重复执行迁移，也不要修改已经执行过的迁移；后续结构修改添加 V4 及之后的新迁移。默认使用 `mysql` 配置；未设置数据库密码时启动会失败，避免误用演示数据库。

## API 约定

响应统一为 `{"code":"OK","message":"操作成功","data":...}`。错误使用对应 HTTP 状态及业务码，`data` 为 null。

| 方法 | 路径 | 认证 | 结果 |
| --- | --- | --- | --- |
| GET | `/api/health` | 无 | 数据库连通状态 |
| POST | `/api/auth/register` | 无 | 201，用户 ID、账号、昵称 |
| POST | `/api/auth/login` | 无 | 200，访问令牌、到期时间、用户信息 |
| GET | `/api/auth/me` | Bearer Token | 当前认证用户 |
| POST | `/api/auth/logout` | Bearer Token | 撤销当前令牌 |

注册请求：

```json
{"username":"student_01","password":"CampusPass123!","nickname":"林同学"}
```

登录只传 `username` 和 `password`。账号为 4–32 位字母、数字或下划线，统一转小写，大小写视为同一账号。昵称最多 32 字符。密码为 8–64 字符且 UTF-8 编码不超过 72 字节，不会自动去除密码中的空格。密码只保存 BCrypt 摘要。

登录成功的 `data` 示例：

```json
{
  "accessToken": "登录接口返回的43位随机令牌",
  "tokenType": "Bearer",
  "expiresAt": "2026-10-24T16:00:00Z",
  "user": {"id": 1, "username": "student_01", "nickname": "林同学"}
}
```

调用受保护接口时设置请求头 `Authorization: Bearer <accessToken>`。令牌有效期默认 8 小时，Redis 使用 token 的 SHA-256 摘要作为键，保存用户 ID 和到期时间，并原子设置 TTL。访问不会自动续期。退出删除当前会话，不影响其他登录；过期、已退出或所属账号被禁用时返回 401。认证时仍查询 MySQL 用户状态，保证禁用立即生效。原 auth_session 表仅为迁移历史保留，不再读写；升级后旧登录失效，需要重新登录。

不能通过传 `userId` 来指定当前用户。认证信息由过滤器验证，控制器使用 `@AuthenticationPrincipal AuthenticatedUser` 获取用户身份。JSON 额外字段会被拒绝；接口不返回密码摘要和令牌摘要。前端已通过 Vite 代理将 `/api` 转发至后端，不需要开放跨域。开发与预览代理默认连接 `http://127.0.0.1:8080`，可通过 `API_TARGET` 环境变量调整。生产部署需由网关或反向代理将同源 `/api` 转发至 Java 服务。

常用错误：400 `INVALID_ARGUMENT`、401 `INVALID_CREDENTIALS` / `UNAUTHORIZED`、403 `FORBIDDEN`、409 `USERNAME_EXISTS`、503 `SERVICE_UNAVAILABLE`。

## 验证

```powershell
cd backend
mvn test
mvn package
java -jar target/campus-share-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo
```

已通过 32 项集成测试，使用独立 H2 内存库，覆盖认证、物品权限、筛选分页、收藏隔离、图片上传、预约状态流转、并发审核冲突、相邻时段、重复操作、超时及日志失败时事务回滚。H2 通过不代表真实 MySQL 环境已经验证；切换 MySQL 后仍需在自己的数据库上执行接口样例。

如果本机 Maven 的镜像连接失败，可临时指定项目内的官方仓库配置，不必修改全局设置：

```powershell
mvn -s .mvn/central-settings.xml test
mvn -s .mvn/central-settings.xml spring-boot:run "-Dspring-boot.run.profiles=demo"
```

## 前后端一起运行

后端启动后，在另一个终端运行：

```powershell
cd "D:\desktop\校园闲置物品借用平台\frontend"
npm run dev
```

打开 Vite 输出的页面地址（默认 `http://127.0.0.1:5173`），先注册，再发布物品。初次启动没有示例物品。用另一个浏览器或隐私窗口注册第二个账号，提交申请；发布者进入“借出管理”审核。审核通过后即可提前确认领取，借用人申请归还，发布者确认归还后完成订单。订单列表的“刷新订单”可读取对方刚刚进行的操作。

如果之前运行的是旧后端，请先 Ctrl+C 停止，再重新启动。前端也需重新启动以加载代理配置。浏览器仅在 sessionStorage 保存当前标签页的令牌；刷新会调用 `/auth/me` 恢复身份。账号的发布、收藏和借用记录从服务端读取。演示配置仍是内存库，后端重启会清空记录；要长期保留数据请切换 MySQL，并保留上传目录。

图片保存在 `backend/data/uploads`（由后端工作目录决定），可用 `UPLOAD_DIRECTORY` 环境变量设置绝对路径。该目录已被 Git 忽略，部署时应使用持久化目录。接口限制 1MB，只接受 JPG、PNG、WebP 文件头；文件名由服务端生成，仅允许本人上传的图片关联到本人发布的物品。未被物品引用的上传文件暂不自动清理。

完整接口见 [API 说明](docs/api.md)，业务和状态图见 [业务说明](docs/business.md)，验证范围见 [验证记录](docs/verification.md)。Redis 已用于登录会话和接口限流；AI 尚未接入。


## MyBatis-Plus 使用方式

后端使用 `mybatis-plus-spring-boot3-starter`，配置前缀为 `mybatis-plus`，不要再同时引入原 MyBatis Starter。安装方式参考 [官方文档](https://baomidou.com/getting-started/install/)。

- `ItemEntity` 对应 `item`，`BorrowOrderEntity` 对应 `borrow_order`，通过 `@TableName` 指定表，`@TableId(type = IdType.AUTO)` 使用数据库自增主键。
- `ItemMapper` 和 `OrderMapper` 继承 `BaseMapper<T>`。物品与订单新增使用继承的 `insert` 并回填 ID，物品信息修改使用 `updateById`，上下架使用 `Wrappers.lambdaUpdate()` 限定更新字段。
- 时间使用现有注入的 `Clock` 在 Service 显式赋值，实体完整映射 `createdAt`、`updatedAt` 和状态；不再把临时参数 `now` 当成实体字段。
- `ItemMapper.findById` 返回含发布者、分类、图片的详情视图；`selectById` 返回单表实体，两者用途不同。
- 物品、订单列表沿用现有 XML 分页 SQL。预约行锁、冲突检测、状态条件更新、操作日志，以及认证、收藏、图片等现有 SQL 继续由 MyBatis-Plus 的会话工厂执行；不需要为了使用 Plus 重写所有 SQL。
- 数据库结构和接口协议不变，无需清空现有数据或重新执行建表脚本。

学习时可以先查看 `ItemServiceImpl.create()`、`update()` 和 `changeStatus()`，再对照 Mapper，观察基础 SQL 是怎样由 Plus 生成的。集成测试同时覆盖通用 CRUD 的字段映射、自增 ID，以及修改物品时保留原上架状态、归属和创建时间。


## Redis 会话与接口限流

无论 `mysql` 还是 `demo` 配置，现在都依赖 Redis。默认地址是 `127.0.0.1:6379`，数据库 0。如果你已有可用 Redis，直接配置连接即可；不要重复启动占用同一端口。

本机已安装 Docker Desktop 时，先启动 Docker Desktop，然后在项目根目录运行：

```powershell
docker compose -f compose.redis.yml up -d
docker compose -f compose.redis.yml exec redis redis-cli ping
```

返回 `PONG` 后启动 Java 后端。Compose 仅将端口绑定到本机，使用 AOF 和命名卷保存数据，适用于本地开发；远程部署应另外配置 ACL、网络访问及持久化策略。镜像使用 [Redis 官方镜像](https://hub.docker.com/_/redis)。停止服务用 `docker compose -f compose.redis.yml stop`，保留卷中的数据。

连接可通过 IDEA 运行配置的环境变量修改：`REDIS_HOST`、`REDIS_PORT`、`REDIS_USERNAME`、`REDIS_PASSWORD`、`REDIS_DATABASE`、`REDIS_KEY_PREFIX`。默认用户名、密码为空，前缀为 `campus-share`；Redis 密码不要写进项目文件。自定义 Compose 的 `REDIS_PORT` 时也要同步配置后端。Redis 连接及命令超时均为 2 秒。

会话键为 `campus-share:session:<token的SHA-256摘要>`，字符串值为 `用户ID:到期时间毫秒`，默认 TTL 为 8 小时，通过 `app.auth.token-ttl` 修改。Redis 缺失会话时认证失败；Redis 故障则返回 503，不回退到 MySQL 或内存会话。Redis 数据清空后需要重新登录。使用 demo 内存数据库时，重启仍需重新注册登录。

默认限流规则：

| 接口 | 维度 | 首次请求起的时间窗口 | 最多请求数 |
| --- | --- | --- | --- |
| POST /api/auth/login | 客户端 IP | 1 分钟 | 10 |
| POST /api/auth/register | 客户端 IP | 1 分钟 | 5 |
| POST /api/orders | 认证用户 ID | 1 分钟 | 10 |

这是从首次请求开始计时的固定窗口。登录失败、参数错误等到达对应控制器的请求也消耗次数；订单请求先认证，无认证返回 401。同一用户的不同 token 共用预约额度。超过限额返回 HTTP 429、业务码 `RATE_LIMITED` 和 `Retry-After` 秒数，拒绝请求不会延长窗口。登录、注册、预约使用独立计数键。其他查询、退出和订单状态操作不受这三项限流影响。

规则在 `application.yml` 的 `app.rate-limit.login/register/booking` 下配置，各有 `max-requests` 和 `window`，例如 `window: 1m`。Lua 脚本原子执行计数和设置过期时间，避免并发下超发及永久计数键；Redis 故障返回 503，阻止受限操作继续执行。实现使用 [Spring Data Redis 脚本接口](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html)。

IP 默认使用 TCP 连接的 `remoteAddr`，不信任请求自行传入的 `X-Forwarded-For` 或 `X-Real-IP`。Vite 代理下通常所有浏览器共用本机 IP 额度；正式反向代理部署需由可信代理覆盖转发头，并在服务器边界配置可信代理解析，不能直接信任互联网请求的转发头。

测试使用 test scope 的 embedded-redis，启动真实、仅绑定本机的临时 Redis，随机端口和随机键前缀，不使用开发 Redis、不执行 FLUSHDB。测试覆盖 TTL、退出删除、多会话隔离、禁用账号、限流维度和重置、12 路并发下的精确额度，以及 Redis 故障的 503 行为。测试依赖及其 Redis 二进制不会进入生产 JAR。
