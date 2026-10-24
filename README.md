# 同学借一下 · 校园物品借用与预约平台

目前已完成 Vue 3 前端体验版，位于 `frontend/`。后端尚未接入。

## 启动前端

需要 Node.js 20.19+ 或 22.12+（当前环境 Node.js 24 可用）。

```powershell
cd frontend
npm install
npm run dev
```

在浏览器打开终端显示的本地地址，默认 http://127.0.0.1:5173 。

```powershell
npm run build
npm run preview
```

## 已有功能

- 发现物品：关键词搜索、分类、校区筛选、只看可借、排序。
- 物品详情：描述、领取位置、预约时间填写与校验。
- 本地体验身份：昵称进入、退出，不是真实注册登录。
- 发布物品：分类、描述、位置、可选图片上传，上架与下架。
- 我的借用：查看当前昵称提交的预约申请，取消待审核申请。
- 我的收藏：收藏与取消收藏。
- 借用指南、空状态、操作提示，适配手机与桌面。

演示数据保存在当前浏览器 localStorage；更换浏览器或清除站点数据会重置。发布内容只在本机可见，不能当作多人平台使用。示例图片来自 Unsplash，字体来自 Google Fonts，需要联网；图片加载失败时显示占位图，字体有系统回退。

## 结构

```text
frontend/
  public/hero.svg       原创共享物品插画
  src/App.vue          页面与本地交互
  src/data.js          示例物品
  src/style.css        响应式样式
  src/main.js          Vue 入口
  vite.config.js       Vite 配置
```

## 后续对接

将本地示例身份、物品与订单替换成后端 API。服务端必须从认证信息获取身份，并负责权限、审核、预约冲突、状态流转与持久化。当前页面不模拟审核成功，也不保证真实可借时段；提交申请仅记录“待审核”。AI 推荐留到业务闭环后实现。

## 浏览器检查（可选）

已在本机 Edge 验证筛选、搜索、收藏、体验登录、预约、取消申请、发布、下架、刷新保留与 390px 手机布局。测试使用独立浏览器上下文，不会修改日常浏览器的数据。

如需重新检查，在前端开发服务启动后，另开终端：

```powershell
cd frontend
npm install --no-save --package-lock=false playwright
node scripts/smoke.mjs
```

此脚本使用本机 Microsoft Edge，需要先安装 Edge。
