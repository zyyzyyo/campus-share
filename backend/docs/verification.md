# 第 1 周后端验证记录

## 物品详情缓存与今日热门验证（2026-10-28）

Microsoft JDK 17 执行 Maven test，51项测试全部通过，包括新增10项 ItemRedisIntegrationTest。验证详情命中时不重复查询 ItemMapper、TTL与过期回源、修改及上下架提交后失效、回滚不暴露未提交数据、并发旧查询不能回填修改前数据、损坏缓存和 Redis 故障回源；验证仅详情浏览计数、本人查看和收藏不计数、同账号不同令牌去重、不信任转发头、12路并发同访客仅计一次、窗口过期可再次计数、热榜排序及最新元数据、下架与100条以上无效成员过滤、北京时间跨日切榜及键有效期、热榜故障降级。

执行 Maven package -DskipTests 成功生成生产 JAR（打包前已通过全部测试），Vue npm run build 通过。使用独立端口H2 demo、临时真实Redis、本机Edge headless执行 frontend/scripts/popular-smoke.cjs，通过空榜、本人排除、重复访客去重、热度排序、点击详情、修改后详情更新、下架过滤、热榜不可用及重试恢复的页面验证；无浏览器运行错误。390px手机页面无横向溢出，已查看桌面及手机截图，存放于 backend/target/browser-tests/popular-desktop.png 和 popular-mobile.png。

没有操作用户的真实MySQL或开发Redis，不新增数据库迁移或生产依赖。缓存与热榜测试基于独立Redis5测试进程，用户正式Redis7.4和真实MySQL环境尚未联调；缓存失效在Redis正常时即时执行，Redis故障时通过回源、实例暂时绕过缓存和TTL兜底，不提供跨实例强一致性保证。

## 消息、续期、逾期及订单边界验证（2026-10-28）

Microsoft JDK 17 执行 Maven package，41项集成测试全部通过，生产 JAR 打包成功。新增9项测试覆盖消息权限与已读隔离、重复动作去重、续期拒绝后重新申请、审批时复查冲突、归还使续期失效、逾期限制与归还解禁、每日催还去重、发布者取消原因及领取后禁止取消、提前领取不能越过已有预约、实际交付时段占用、通知失败时订单与日志回滚、续期审批与下一单审核的并发互斥，以及续期后新到期提醒和到期后续期失效。

Vue 前端 npm run build 通过。使用独立端口的 H2 demo 后端、临时 Redis 与本机 Edge headless 执行 frontend/scripts/business-smoke.cjs，通过双账号实际页面验证续期申请与审批、消息查看关联订单、全部已读、发布者填写取消原因、逾期时长、催还、申请归还和确认归还；浏览器无运行错误。390px 手机消息页面无横向溢出，并人工查看手机消息页、桌面订单页截图。脚本和截图分别位于 frontend/scripts/business-smoke.cjs 与 backend/target/browser-tests，测试依赖单独安装在 target 中，没有增加项目生产依赖。

测试使用独立演示账号和物品，没有操作真实 MySQL、开发 Redis 或已有业务数据。V3 迁移已在 H2 MySQL 兼容模式执行；用户真实 MySQL 环境的迁移与并发联调仍需单独验证。站内消息的未读数每30秒更新，外部推送未接入。

本文件前半部分为首次搭建时的历史记录；最新联调结果见文末。

验证日期：2026-10-24（Asia/Shanghai）。环境：Windows、Temurin JDK 25.0.3、Maven 3.6.3；编译目标 Java 17。

## 已完成的验证

`mvn -s .mvn/central-settings.xml test`：11 项集成测试全部通过，0 失败、0 错误、0 跳过。使用独立的 H2 测试库，Flyway V1 迁移执行成功。

`mvn -s .mvn/central-settings.xml -DskipTests package`：在上述测试通过后打包成功，生成可执行的 Spring Boot JAR。

另用 `java -jar` 临时启动 `demo` 配置，通过真实 HTTP 依次验证健康检查、注册、登录、当前身份查询、退出和退出后令牌返回 401。验证后停止了临时服务，没有留下正在运行的后端实例。

## 依赖下载问题

本机 Maven 的镜像在下载 `spring-boot-autoconfigure` 和 `jackson-databind` 时连接中断。已添加可选的 `.mvn/central-settings.xml`，不修改全局配置。旧下载器重试仍失败的两个依赖通过官方 Maven Central 下载并核对官方校验值后补齐缓存，随后编译、测试和打包成功。

## 验证边界

- 尚未连接用户的 MySQL；H2 兼容模式不能代替真实 MySQL 验证。
- 使用 JDK 25 生成 Java 17 目标字节码，尚未在独立 JDK 17 运行环境复验。
- 前端仍为本地演示数据，尚未与认证接口联调。
- 物品、预约、归还、并发控制和超时任务只有业务及表结构设计，尚未实现，不能算作已验证能力。

## 前后端联调与分层重构（2026-10-24）

Java 已按 `controller → service → service.impl → mapper` 分层；控制器不直接访问 Mapper 或 JdbcTemplate。实体、请求、响应、认证身份分别在 entity、dto、vo、security，SQL 位于 Mapper 注解和 resources/mapper XML。原 auth、health 混合业务包已移除。

干净构建 `mvn -s .mvn/central-settings.xml clean test`：21 项集成测试全部通过（11 项认证、10 项业务），0 失败、0 错误、0 跳过。覆盖真实数据库迁移、查询分页、同名账号权限隔离、图片上传与引用权限、收藏幂等、预约时段与状态、相邻时段、并发冲突审核、完整领取归还、超时及日志失败时事务回滚。数据库为 H2 MySQL 模式，尚未在真实 MySQL 环境验证。

`mvn -DskipTests package` 和 `npm run build` 均通过。使用打包后的 Java 服务（临时端口 18080）与 Vite /api 代理（临时端口 5174）运行 Python Playwright + Microsoft Edge 的真实浏览器测试，通过两组独立浏览器上下文验证注册登录、图片上传、发布修改上下架、搜索、收藏、申请、发布者审核、借用人取消、刷新后读取后端数据、同名账号隔离、故障提示与重试、退出及手机布局，未出现页面运行时错误。领取、归还、并发和超时由后端集成测试验证，没有用浏览器等待真实预约时间。

测试中的日志失败用 H2 CHECK 约束主动注入，因此测试日志出现预期的数据库异常堆栈；断言已确认订单和操作记录整体回滚，不属于未处理的测试失败。并发正确性的关键为物品行锁和 READ_COMMITTED：等待锁后重新读取订单与有效预约，避免旧快照。

演示配置仍为内存库，后端重启清空业务数据；真实持久化需配置 MySQL 并保留上传目录。Redis、AI、推送通知与生产部署尚未验证。


## IDEA 运行配置验证（2026-10-24）

已发现并配置本机注册的 ms-17（Microsoft JDK 17.0.20.1），因此此前“尚未在独立 JDK 17 环境复验”的限制已经解除。使用 JDK 17 与本机 Maven 3.6.3 运行 21 项集成测试，全部通过。共享运行配置保存在 .run，联合配置引用两个 Maven 子配置；项目本地 .idea 配置已链接 backend/pom.xml 并设置 SDK 与 Maven Runner。

使用与运行配置相同的 Maven/JDK/Node 启动方式，在临时端口验证前端 HTTP 200、经前端代理访问后端 health 为 UP、分类返回 5 条。验证后停止临时服务。共享配置的默认端口仍为后端 8080、前端 5173；5173 发现已有旧前端占用，需停止旧实例后点击运行。本次未通过图形界面点击 IDEA 运行按钮，已检查运行配置 XML 与关联关系并验证其对应命令。首次加载需重新打开项目。


## IDEA settings 中文路径修复（2026-10-24）

用户的实际 IDEA 运行仍出现 settings 路径乱码。最终两份配置清空用户设置文件覆盖，改用单个 ASCII 命令行参数 `--settings=.mvn/central-settings.xml`，工作目录为 backend；同时清空项目本地 IDEA 的旧绝对路径覆盖。通过当前 IDEA 插件自身的 XML 解析器验证 getOptions() 恰为这一项，并确认相对文件存在。使用截图同一套 IDEA 内置 Maven、JDK 17、JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 实际启动后端（临时端口 18081），健康接口返回 UP，随后停止测试服务。此验证沿用了实际 Maven 启动链路，不以单纯 XML 校验代替服务启动。


## MyBatis-Plus 迁移验证（2026-10-26）

替换为 MyBatis-Plus 3.5.17 的 Spring Boot 3 Starter，将配置前缀迁移为 `mybatis-plus`。物品、借用订单实体增加表名、自增主键、状态和时间字段映射，两个核心 Mapper 继承 BaseMapper；物品和订单新增、物品修改及上下架使用通用 CRUD 或 Lambda 更新条件。关联查询、行锁、预约冲突和状态条件更新保留自定义 SQL。

使用 Microsoft JDK 17 执行 `mvn package`，24 项集成测试通过并成功生成可执行 JAR。测试通过 argLine 显式加载本机缓存中的 Mockito Java Agent，避免测试环境动态附加受限。新增回归测试验证通用主键查询和实体映射，以及下架物品修改后仍保留状态、归属与创建时间；原认证、权限、图片、预约、并发审核、日志回滚、超时和提前领取测试继续通过。

此次数据库测试使用 H2 MySQL 兼容模式，没有操作用户的真实 MySQL 数据，也未修改表结构。真实 MySQL 环境仍需单独验证。


## Redis 会话与接口限流验证（2026-10-26）

会话迁移为 StringRedisTemplate 存储，使用 token 摘要作为键，一条 SET 原子设置会话及 TTL；默认 8 小时且不自动续期。退出删除当前会话，用户状态继续从 MySQL 检查，保留账号禁用立即生效的语义。SessionMapper 已删除，历史 auth_session 表仅保留结构，不再读写。升级后旧 token 失效。

新增 Redis Lua 固定窗口限流：登录每 IP 每分钟 10 次，注册 5 次，预约按用户 ID 10 次。规则可配置，超限返回 429 与 Retry-After；不信任客户端转发头；Redis 故障按 503 处理，不回退会话或放行受限写操作。

使用 Microsoft JDK 17 执行 clean package，32 项测试通过，生产 JAR 打包成功。独立临时真实 Redis 使用随机端口、仅绑定 127.0.0.1、随机测试前缀；没有操作用户 Redis 或 MySQL。验证真实会话 TTL、过期拒绝、退出删除、不同会话隔离、禁用账号、按 IP / 用户限流、窗口到期恢复、12 路并发仅放行 3 个，以及认证查询/会话写入/退出删除/限流故障时的 503 行为。故障处理测试通过 Spy 注入连接异常；Redis 正常路径和 Lua 在真实服务上执行。

本机测试宿主省略了 PROCESSOR_ARCHITECTURE，执行命令临时补齐 AMD64 后运行 embedded-redis；Mockito 通过 argLine 显式加载已有 Java Agent。没有更改系统环境变量。测试进程退出时停止临时 Redis。嵌入式测试 Redis 与生产 Redis 7.4 的部署环境不同，尚未在用户的真实 MySQL + Redis 环境联调。

compose.redis.yml 通过 docker compose config 静态验证，显式指定 ASCII 项目名以兼容中文项目目录，配置官方 redis:7.4-alpine 镜像、本机端口、AOF、noeviction 和命名卷。本机 Docker Engine 未运行，因此未实际启动此 Compose 服务。部署说明已更新至后端 README。
