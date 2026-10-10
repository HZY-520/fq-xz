# 部署说明

本文档说明如何把本前端部署到一台已有「番茄小说下载器」后端服务的机器上。

---

## 一、架构总览

```
浏览器
  │
  ├─ /                  → Nginx 静态文件（本前端 dist/）
  ├─ /assets/*.js|css   → Nginx 静态文件（带 hash，可长缓存）
  ├─ /api/*             → 反代 127.0.0.1:18423（后端）
  ├─ /download/*        → 反代 127.0.0.1:18423（文件下载）
  ├─ /download-zip/*    → 反代 127.0.0.1:18423（目录打包）
  └─ /api/manage/*      → 反代 127.0.0.1:18425（删除服务 sidecar，可选）
```

要点：

- **后端不需要任何改动**，仍监听自己的端口（示例用 18423）。
- 前端是纯静态文件，**不用 Node 运行时**，不需要 PM2/Systemd。
- 后端与 sidecar 都应只绑定 **127.0.0.1**，公网流量统一经 Nginx。

---

## 二、纯前端部署（不支持删除功能）

### 1. 放置文件

```bash
WEB_ROOT=/www/wwwroot/example.com/web   # 改成你的站点根目录

mkdir -p "$WEB_ROOT"
cp -r dist/. "$WEB_ROOT/"
chown -R www:www "$WEB_ROOT"
ls -la "$WEB_ROOT"        # 应有 index.html 和 assets/
```

> ⚠️ **关键**：Nginx 的 `root` 必须指向**存放 index.html 的那个目录**。
> 静态资源要放在 `$WEB_ROOT/assets/` 下（与 index.html 同级），否则 JS/CSS 会 404。

### 2. 配置 Nginx

参考 `nginx-example.conf`，把其中的域名、路径、后端端口替换成你的。
配置好后：

```bash
nginx -t && nginx -s reload
```

### 3. 验证

```bash
curl -sI https://你的域名/ | head -1                    # 期望 200
curl -s  https://你的域名/api/status | head -c 200      # 期望后端 JSON
```

浏览器打开首页，检查各页面是否正常、控制台是否报错。

---

## 三、加入删除功能（sidecar）

后端**没有删除接口**，删除能力由一个小服务提供。

### 1. 需要的数据目录结构

sidecar 需要访问后端的两处数据：

| 用途 | 容器内路径 | 说明 |
|---|---|---|
| 书籍目录 | `/data/books` | 后端 `save_path` 指向的位置 |
| 下载历史 | `/data/logs/download_history.jsonl` | 后端自动生成 |

因此建议后端 `save_path` 设为 `/data/books`，并把宿主机目录挂到 `/data`。

> **重要**：不要把宿主机的书籍目录挂到容器的 `/app`。
> 官方镜像把程序二进制 `tomato-novel-downloader` 和 `entrypoint.sh` 也放在 `/app`，
> 整目录挂载会覆盖它们，导致容器无法启动。

### 2. docker-compose 片段

```yaml
services:
  # ── 后端（原样，只加持久化挂载）──
  tomato-novel-webui:
    image: zhongbai233/tomato-novel-downloader-webui:latest
    container_name: tomato-novel-webui
    restart: unless-stopped
    ports:
      - "127.0.0.1:18423:18423"          # 只绑回环
    environment:
      TOMATO_WEB_ADDR: "0.0.0.0:18423"
      TOMATO_DATA_DIR: "/data"
      TZ: "Asia/Shanghai"
    volumes:
      - /你的路径/tomato-data:/data      # 持久化：配置 + 日志 + 书籍

  # ── 删除服务 ──
  tomato-manage:
    image: python:3.12-alpine
    container_name: tomato-manage
    restart: unless-stopped
    ports:
      - "127.0.0.1:18425:18425"          # 只绑回环，供宿主机 Nginx 反代
    environment:
      LIBRARY_ROOT: "/data/books"        # 书籍目录
      MANAGE_DATA_DIR: "/data"           # 历史文件所在（/data/logs/...）
      MANAGE_BIND: "0.0.0.0"
      MANAGE_PORT: "18425"
      BACKEND_BASE: "http://tomato-novel-webui:18423"   # 用于"下载中"检查
      TZ: "Asia/Shanghai"
      # MANAGE_TOKEN: "自定义密钥"        # 可选：设置后请求需带 X-Manage-Token
    volumes:
      - /你的路径/tomato-data:/data
      - /你的路径/manage/delete_service.py:/app_manage/delete_service.py:ro
    command: ["python3", "/app_manage/delete_service.py"]
    depends_on:
      - tomato-novel-webui
```

> 注意：后端 `save_path` 需设置为 `/data/books`（可在前端「配置」页里改，
> 或直接编辑 `tomato-data/config.yml` 后重启容器）。

### 3. 启动与验证

```bash
# 把 delete_service.py 放到 /你的路径/manage/ 下
docker compose up -d
docker compose ps

# 健康检查（应返回 library_root 与 history_file）
curl -s http://127.0.0.1:18425/api/manage/health
```

### 4. Nginx 增加转发

在 `nginx-example.conf` 对应位置已有 `/api/manage/` 段，注意它**必须写在 `/api/` 之前**
（因为 `^~ /api/` 会吃掉所有 `/api/` 前缀的请求）。

```bash
nginx -t && nginx -s reload
curl -s https://你的域名/api/manage/health
```

---

## 四、更新前端

```bash
pnpm install
pnpm build
cp -r dist/. /你的站点根目录/
chown -R www:www /你的站点根目录/
```

静态资源带内容 hash，浏览器会自动拉新；旧的 hash 文件可以清理：

```bash
# 只保留当前 index.html 引用到的资源
cd /你的站点根目录/assets
for f in *; do grep -q "$f" ../index.html || rm -f "$f"; done
```

---

## 五、常见问题

| 现象 | 原因与处理 |
|---|---|
| 页面空白、JS 404 | `index.html` 与 `assets/` 不在同一目录；或 Nginx `root` 指错 |
| 刷新子路由 404 | 缺少 SPA 回退：`location / { try_files $uri $uri/ /index.html; }` |
| `/api/*` 返回 404/502 | 反代目标端口不对，或后端未启动；`curl 127.0.0.1:18423/api/status` 自查 |
| `/api/manage/*` 返回 404 | sidecar 未启动，或 Nginx 里该段被 `/api/` 抢先匹配（顺序问题） |
| 删除提示"服务不可用" | sidecar 未部署；前端其余功能不受影响 |
| 删除提示"目标不存在" | 该记录对应的文件已不在服务器上（属正常，可直接删记录） |
| 书籍在容器重启后消失 | 后端 `save_path` 未指向持久化目录，见第三节第 1 点 |
| 输入框在 iOS 上被放大 | 已处理（输入框 16px）；若自定义样式请保持 ≥16px |
