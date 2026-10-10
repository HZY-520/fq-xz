# 删除服务（sidecar）

上游后端 **没有删除接口**，本服务用于补上「删除小说 / 删除历史记录」能力。

## 它做什么

与后端**共享同一份数据目录**，通过 HTTP 暴露有限的操作：

| 接口 | 作用 |
|---|---|
| `GET  /api/manage/health` | 健康检查 |
| `GET  /api/manage/delete?...&dry_run=1` | 删除预演（只返回清单） |
| `POST /api/manage/delete` | 删除小说文件 / 缓存 / 有声小说目录 |
| `GET  /api/manage/history` | 列出原始下载历史记录 |
| `POST /api/manage/history/delete` | 删除下载历史记录 |

## 为什么需要它

- 后端只提供只读的文件接口，无法删除文件；
- 下载历史是磁盘上的 JSONL 文件（`/data/logs/download_history.jsonl`），
  后端每次请求都重新读取，**改文件即刻生效、无需重启后端**。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `LIBRARY_ROOT` | `/app` | 书籍目录（应与后端 `save_path` 一致，如 `/data/books`） |
| `MANAGE_DATA_DIR` | `/data` | 数据根目录（历史文件在其 `logs/` 下） |
| `MANAGE_BIND` | `127.0.0.1` | 监听地址（容器内用 `0.0.0.0`，由宿主机 Nginx 反代） |
| `MANAGE_PORT` | `18425` | 监听端口 |
| `BACKEND_BASE` | `http://127.0.0.1:18423` | 后端地址，用于「下载中」检查 |
| `MANAGE_TOKEN` | 空 | 设置后请求必须带 `X-Manage-Token` 头 |

## 安全设计

1. 只监听回环地址，由 Nginx 反代 `/api/manage/`，不对公网暴露端口；
2. 所有路径在书籍根目录下做 `..` 段拒绝 + `canonicalize` 前缀校验，防目录穿越；
3. 文件扩展名白名单（仅 `epub`/`txt`/`mp3`/`wav`）；
4. 删除前读取后端 `/api/jobs`，**拒绝删除正在下载的书**（返回 409）；
5. 历史文件写入采用「临时文件 + `os.replace()` 原子替换」，无法解析的坏行原样保留；
6. 支持 `dry_run=1` 预演，前端会先预演再让你确认。

## 部署

见上级目录 `DEPLOY.md` 第三节。

## 依赖

**仅 Python 3 标准库**，无需 pip 安装任何包（可用 `python:3.12-alpine` 镜像直接跑）。
