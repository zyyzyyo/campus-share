# 第 1 周后端验证记录

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
