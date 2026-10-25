# IDEA 启动

仅保留一个共享运行配置：后端 - MySQL，直接运行 CampusShareApplication，使用 Java 17 和 mysql 环境。

1. 在 Navicat 创建空数据库 campus_share，字符集 utf8mb4。
2. IDEA 选择“后端 - MySQL”，进入“编辑配置”，环境变量填写 DB_USERNAME 和 DB_PASSWORD，使用自己的 MySQL 用户名和密码。填写密码前取消“存储为项目文件”，将个人配置保存在本机，避免密码进入 Git。
3. 确认模块 campus-share-backend，点击运行。Flyway 自动执行 db/migration 中的脚本建表，不必手动执行。
4. IDEA 底部终端从项目根目录执行：

```powershell
cd frontend
npm run dev
```

打开终端显示的前端地址。

如果找不到模块，右键 backend/pom.xml → 添加为 Maven 项目，等待导入完成。
如果仍显示旧配置，关闭并重新打开项目。先停止旧后端，避免占用 8080 端口。
旧的 scripts/start-backend.ps1 是 demo 演示脚本，不用于 MySQL；日常使用上述唯一运行配置。
