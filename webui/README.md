# 番茄小说下载器 — Web UI（自研前端）

[Tomato-Novel-Downloader](https://github.com/zhongbai2333/Tomato-Novel-Downloader) 的**前后端分离**前端，
使用 Vue 3 + Vite 从零重写，替代后端内置的那份 Web UI。

> 后端容器完全不用改，仍作为纯 API 服务运行；本前端可独立部署、独立升级。

---

## 特性

- **6 个页面**：运行状态 / 搜索书籍 / 下载任务 / 下载历史 / 下载库 / 配置
- **书号与链接直查**：搜索框支持粘贴纯数字书号或番茄分享链接，直接定位到该书（不走搜索接口）
- **按书管理的下载库**：把「输出文件 + 有声小说目录 + 缓存目录」自动归并为一本书，支持**整本删除**
- **历史记录管理**：可删除单条下载历史；若服务器上还有文件，可选一并清理
- **完整配置编辑**：常用设置 / 完整 44 项配置（分 6 组）/ YAML 原始配置（带注释）三档
- **移动端适配**：手机、平板、桌面三档断点；表格自动转卡片、侧边栏变抽屉、弹窗变底部抽屉
- **深色 / 浅色主题**：可切换并记忆
- **零 UI 框架依赖**：仅 Vue 3 + vue-router，图标全部内置 SVG（不依赖 emoji 字体）
- **无 JS 报错**：已在真实 Chrome 下跑过 18 个「页面 × 视口」组合的渲染校验

产物体积：**gzip 后约 59 KB**（JS 42 KB + CSS 4.7 KB）。

---

## 目录结构

```
tomato-webui/
├── dist/                     # ✅ 构建产物（可直接部署）
│   ├── index.html
│   └── assets/               # 带内容 hash 的 JS/CSS
├── src/                      # 源码
│   ├── api.js                # 后端 API 客户端（封装全部接口）
│   ├── store.js              # 轻量全局状态 + 主题 + Toast + 格式化工具
│   ├── router.js             # 路由
│   ├── style.css             # 设计系统（变量 / 组件 / 响应式）
│   ├── App.vue               # 主壳（侧边栏 + 顶栏 + 登录）
│   ├── components/           # Icon（内置 SVG 图标集）、ToastHost
│   └── views/                # 6 个页面
├── index.html                # Vite 入口
├── vite.config.js            # 构建与开发代理配置
├── package.json
├── pnpm-lock.yaml
├── API.md                    # 📘 后端 API 完整文档（32 个接口）
├── DEPLOY.md                 # 部署说明
├── nginx-example.conf        # Nginx 配置范例
├── deploy.sh                 # 一键构建 + 部署脚本
└── verify-mobile.js          # 移动端渲染校验脚本（可选）
```

---

## 快速开始

### 方式一：直接部署（只用构建产物）

把 `dist/` 里的内容放到你的 Web 站点根目录即可。

```bash
# 假设站点根目录是 /www/wwwroot/example.com/web
mkdir -p /www/wwwroot/example.com/web
cp -r dist/. /www/wwwroot/example.com/web/
chown -R www:www /www/wwwroot/example.com/web
```

然后按 `nginx-example.conf` 配置 Nginx：静态文件由 Nginx 提供，
`/api/`、`/download/`、`/download-zip/` 反代到后端。

### 方式二：本地开发（热更新）

```bash
pnpm install
pnpm dev
```

开发服务器默认开在 `http://127.0.0.1:5173`，`vite.config.js` 里已配置
`/api`、`/download`、`/download-zip` 到 `http://127.0.0.1:18423` 的代理，
改代码即时生效，**不影响线上**。

### 方式三：重新构建并部署

```bash
pnpm install
pnpm build
# 构建产物在 dist/
```

或使用脚本（路径按需修改）：

```bash
bash deploy.sh
```

---

## 后端要求

本前端依赖后端提供以下能力（v2.4.15 实测通过）：

| 能力 | 接口 | 说明 |
|---|---|---|
| 状态与配置 | `/api/status`、`/api/config*` | 必需 |
| 搜索 / 预览 | `/api/search`、`/api/preview/*` | 搜索需后端以 `official-api` 构建 |
| 任务管理 | `/api/jobs*` | 必需，**并发上限为 1 个任务** |
| 历史与书库 | `/api/history`、`/api/library` | 必需 |
| 文件下载 | `/download/*`、`/download-zip/*` | 必需 |
| **删除能力** | `/api/manage/*` | **后端没有，需额外部署 sidecar**（见下） |

> 完整接口说明见 **[API.md](./API.md)**。

### 关于删除功能

上游后端 **没有任何删除接口**（只读挂载无法删除文件），本前端的「删除小说 / 删除历史记录」
依赖一个额外的 sidecar 服务，与后端共享同一份数据目录，只监听回环地址，
由 Nginx 反代 `/api/manage/` 访问。

部署方式与源码见 `API.md` 第 8 章。若你的环境不需要删除功能，
前端其余部分可正常工作（点击删除会提示服务不可用）。

---

## 环境变量与配置

前端本身**没有运行时环境变量**——所有配置都在构建期通过同源相对路径访问后端，
因此部署在任意域名下都无需修改代码。

需要改的地方只有 `vite.config.js` 里的**开发代理目标**（默认 `127.0.0.1:18423`）。

### 后端保存路径建议

后端程序默认把书存到容器工作目录 `/app`。若该目录没有持久化，
**容器重建会丢失全部已下载小说**。建议把后端 `save_path` 设为已持久化的路径
（例如 `/data/books`），并确保它挂载到宿主机。

---

## 技术栈

| 项 | 版本 |
|---|---|
| Vue | 3.5 |
| vue-router | 4.6 |
| Vite | 8.3 |
| Node | 需 ≥ 20（实测 v24.20.0） |
| 包管理 | pnpm（也可用 npm/yarn） |

---

## 移动端适配说明

三档断点：

| 断点 | 行为 |
|---|---|
| `> 1024px` | 桌面布局，固定侧边栏 |
| `≤ 1024px` | 收窄留白，书籍卡片网格自适应 |
| `≤ 860px` | **侧边栏变抽屉**（汉堡菜单 + 遮罩）、**表格转卡片**、弹窗变底部抽屉、统计卡两列 |
| `≤ 560px` | 统计卡单列、列名收窄 |

其他移动端细节：

- 输入框字号 16px（避免 iOS 聚焦时自动缩放）
- 按钮最小高度 38px，保证触摸目标
- 面包屑可横向滑动
- 弹窗头尾 sticky，长内容可滚动

### 自测脚本

```bash
# 需要 puppeteer（在项目外安装亦可）
PUPPETEER_CACHE_DIR=/root/.cache/puppeteer node verify-mobile.js https://你的域名
```

会逐页在 3 种视口下检查：横向溢出、JS 报错、移动端元素显隐，并输出截图。

---

## 说明

- 本前端仅调用后端接口，**不负责小说内容的获取与解析**，相关能力与合规声明见上游项目。
- 下载内容仅供**个人阅读**使用，请勿转载、分发或用于商业用途。
