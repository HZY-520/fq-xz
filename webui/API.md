# 番茄小说下载器 — Web API 接口文档

> 面向「其他项目调用」场景整理的完整接口说明。
>
> - **服务地址**：`https://fq.huziyang.top`
> - **本地回环地址**（同机调用、绕过 Nginx）：`http://127.0.0.1:18423`
> - **接口基路径**：`/api/`
> - **数据格式**：请求/响应均为 `application/json; charset=utf-8`（图片、文件下载除外）
> - **后端版本**：v2.4.15（Docker 构建）
> - **文档整理时间**：2026-10-10

---

## 目录

1. [快速开始](#1-快速开始)
2. [认证机制](#2-认证机制)
3. [错误码约定](#3-错误码约定)
4. [业务约束（重要）](#4-业务约束重要)
5. [接口清单](#5-接口清单)
   - [5.1 系统状态](#51-系统状态)
   - [5.2 认证](#52-认证)
   - [5.3 搜索](#53-搜索)
   - [5.4 书籍预览](#54-书籍预览)
   - [5.5 下载任务](#55-下载任务)
   - [5.6 下载历史](#56-下载历史)
   - [5.7 书库浏览](#57-书库浏览)
   - [5.8 文件下载](#58-文件下载)
   - [5.9 配置读取与修改](#59-配置读取与修改)
   - [5.10 更新检查](#510-更新检查)
6. [典型调用流程](#6-典型调用流程)
7. [多语言调用示例](#7-多语言调用示例)
8. [删除接口（扩展能力）](#8-删除接口扩展能力非上游原生)

---

## 1. 快速开始

最常用的三个接口，直接可用于你的项目：

```bash
# 1) 搜索书籍（拿到 book_id）
curl -s "https://fq.huziyang.top/api/search?q=凡人修仙传"

# 2) 用 book_id 创建下载任务
curl -s -X POST "https://fq.huziyang.top/api/jobs" \
     -H "Content-Type: application/json" \
     -d '{"book_id":"7137526579449564198"}'

# 3) 轮询任务进度
curl -s "https://fq.huziyang.top/api/jobs"
```

---

## 2. 认证机制

服务支持**两种模式**，由后端启动时是否设置密码决定。可通过 `GET /api/status` 的 `locked` 字段判断当前模式。

| 模式 | `locked` | 调用要求 |
|---|---|---|
| **无密码锁**（当前状态） | `false` | 任何请求都可直接调用，**无需任何认证头** |
| **密码锁** | `true` | 需先登录拿到会话 Cookie，或使用请求头密码 |

### 2.1 无密码锁

直接用，无额外处理。

### 2.2 密码锁模式

**方式 A：Cookie 会话（推荐）**

```bash
# 登录，Cookie 存入 jar
curl -s -c cookies.txt -X POST "https://fq.huziyang.top/api/login" \
     -H "Content-Type: application/json" \
     -d '{"password":"你的密码"}'

# 后续请求带上 Cookie
curl -s -b cookies.txt "https://fq.huziyang.top/api/jobs"
```

登录成功会下发两个同值 Cookie，有效期 **24 小时**：
- `tomato_session=<token>`（`HttpOnly`、`SameSite=Lax`）
- `auth_token=<token>`（兼容旧客户端）

**方式 B：请求头密码（仅适合脚本，更简单）**

```bash
curl -s -H "x-tomato-password: 你的密码" "https://fq.huziyang.top/api/jobs"
```

> 后端会对密码做 `SHA-256` 后比对。

### 2.3 认证豁免路径

密码锁模式下，以下路径**不需要认证**：`/`、`/assets/*`、`/api/login`。其余所有接口（含 `GET /api/status`）都需要。

### 2.4 登录失败保护

- 密码错误或留空会累计失败次数，超限后该 IP 会被**临时锁定**，返回 `429`。
- 连续失败过多会触发更长的 `429`（自带冷却时间）。

---

## 3. 错误码约定

| HTTP 状态码 | 含义 | 典型场景 |
|---|---|---|
| `200` | 成功 | — |
| `204` | 成功但无响应体 | 预览封面清理完成 |
| `400` | 请求参数非法 | `book_id` 为空或无法解析；章节范围不合法；配置校验不通过；路径非法 |
| `401` | 未认证 | 密码锁模式下未登录；密码错误 |
| `403` | 禁止访问 | 下载的文件扩展名不在白名单；路径越界（目录穿越） |
| `404` | 资源不存在 | 文件不存在；任务 ID 不存在 |
| `405` | 方法不允许 | 用错了 HTTP 方法（说明路径存在但方法不对） |
| `415` | 请求体类型不支持 | **POST 请求没有带 `Content-Type: application/json`** |
| `429` | 请求过频 | **已有任务在跑**（并发限制）；登录尝试过频 |
| `500` | 服务端内部错误 | 预览/下载计划准备失败 |
| `502` | 上游接口失败 | 搜索时番茄官方接口不可用 |
| `503` | 服务不可用 | 搜索并发限制已满 |

> ⚠️ **所有 POST 接口都必须带 `Content-Type: application/json`**，否则后端直接返回 `415`（实测确认）。
> 即使请求体是 `{}` 也要带上这个请求头。

错误响应体统一形如：

```json
{ "error": "错误描述" }
```

部分接口直接返回纯文本错误（如 `unauthorized`）。

---

## 4. 业务约束（重要）

调用前务必了解，否则容易踩坑：

| 约束 | 说明 |
|---|---|
| **任务并发 = 1** | 同时只允许 **1 个**活跃任务（`queued` 或 `running`）。已有任务时新建返回 **429**。这是后端防止 API 被滥用的硬限制 |
| **已完成任务保留 2 小时** | `GET /api/jobs` 默认自动隐藏 2 小时前完成的任务；传 `all=true` 可看全部 |
| **CLI 不能新建下载** | 后端命令行模式已禁用 `--download`，首次下载必须走 Web UI 或 API 的 `POST /api/jobs` |
| **章节范围需成对** | `range_start` 与 `range_end` 必须同时提供；只给一个返回 `400`。范围超出总章节数时后端会**忽略范围**按全书下载（不报错） |
| **下载文件类型白名单** | 仅允许 `epub`、`txt`、`mp3`、`wav` 四种扩展名，其余返回 `403` |
| **路径安全** | 所有文件接口都会做 `canonicalize` + 前缀校验，目录穿越（如 `../`）返回 `400`/`403` |
| **搜索依赖 official-api** | 若后端以 `no-official-api` 构建，搜索返回 `{"items":[],"error":"search requires official-api feature"}` |
| **敏感字段** | `GET /api/config/full` 会返回 `audiobook_tts_api_token` 明文，注意脱敏存储与日志 |
| **书籍目录已迁移** | 后端 `save_path` 已设为 `/data/books`（持久化卷）。程序默认保存目录是容器工作目录 `/app`，而 `/app` 无法整目录挂载（会覆盖容器内的程序二进制与 entrypoint），**不设 `save_path` 的话容器重建会丢失全部已下载小说**。因此 `/api/library` 的 `root` 显示为 `/data/books` 属正常 |

---

## 5. 接口清单

### 5.1 系统状态

#### `GET /api/status`

获取服务运行状态与基础配置摘要。

**参数**：无

**响应示例**

```json
{
  "version": "2.4.15",
  "docker_build": true,
  "prewarm_in_progress": false,
  "prewarm_error": null,
  "save_dir": "/app",
  "bind_addr": "0.0.0.0:18423",
  "bind_addrs": ["0.0.0.0:18423"],
  "locked": false,
  "config": {
    "old_cli": false,
    "use_official_api": true,
    "save_path": "",
    "api_endpoints_len": 0
  }
}
```

**字段说明**

| 字段 | 类型 | 说明 |
|---|---|---|
| `version` | string | 后端版本号 |
| `docker_build` | bool | 是否为 Docker 构建（Docker 构建禁用程序自更新） |
| `prewarm_in_progress` | bool | 是否正在预热（初始化缓存） |
| `prewarm_error` | string \| null | 预热错误信息 |
| `save_dir` | string | 实际保存根目录（容器内路径） |
| `bind_addr` / `bind_addrs` | string / array | 监听地址 |
| `locked` | bool | **是否开启密码锁**（决定你要不要传认证） |
| `config.use_official_api` | bool | 是否使用官方 API 获取正文 |
| `config.api_endpoints_len` | number | 已配置的第三方接口地址数量 |

---

### 5.2 认证

#### `POST /api/login`

登录并获取会话 Cookie。

**请求体**

```json
{ "password": "你的密码" }
```

**响应**

```json
{ "ok": true, "locked": true }
```

未开启密码锁时恒为成功：

```json
{ "ok": true, "locked": false }
```

**响应头**：`Set-Cookie: tomato_session=...; auth_token=...`（`Max-Age` 86400 秒）

**错误**：`401` 密码错误/为空；`429` 尝试过频或 IP 被锁

---

### 5.3 搜索

#### `GET /api/search`

按关键词搜索书籍（需要 `official-api` 构建）。

**Query 参数**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `q` | string | 是 | 搜索关键词（会 `trim`；空串直接返回空列表） |

**响应示例**

```json
{
  "items": [
    {
      "book_id": "7137526579449564198",
      "title": "凡人仙修传",
      "author": "帝元啊",
      "raw": { "...": "番茄官方接口原始返回，字段极多" }
    }
  ]
}
```

**`raw` 中常用的字段**（实测存在，可直接取用）

| 字段 | 说明 |
|---|---|
| `abstract` / `book_abstract_v2` | 简介 |
| `thumb_url` | 封面图 URL |
| `detail_page_thumb_url` / `expand_thumb_url` | 备用封面 |
| `tags` / `pure_category_tags` | 标签（逗号分隔） |
| `category` | 分类 |
| `read_cnt_text` | 阅读量文案，如 `2.6万人在读` |
| `read_count` | 阅读量数值 |
| `score` | 评分 |
| `serial_count` | 章节数 |
| `word_number` | 字数 |
| `creation_status` | `0` 完结 / `1` 连载 |
| `last_chapter_title` | 最新章节名 |

**错误**：`502` 上游搜索失败（含脱敏后的原因）；`503` 并发限制已满

**注意**：搜索受并发限制（最多 2 个同时进行的上游请求）。

---

### 5.4 书籍预览

#### `GET /api/preview/{book_id}`

获取单本书的详情（不创建下载任务），适合做「详情页」。

**路径参数**：`book_id` — 书籍 ID（支持纯数字 ID 或分享链接/文本，后端会尝试解析）

**响应示例**

```json
{
  "book_id": "7137526579449564198",
  "book_name": "凡人仙修传",
  "author": "帝元啊",
  "category": "东方仙侠",
  "description": "【传统东方玄幻……】",
  "tags": ["玄幻", "奇幻仙侠", "仙侠", "无女主"],
  "cover_url": "/api/preview-cover/78998ddd399114bcc51ea37d124a06d06ea80452",
  "detail_cover_url": "/api/preview-cover/78998ddd399114bcc51ea37d124a06d06ea80452",
  "finished": true,
  "chapter_count": 553,
  "word_count": 869877,
  "score": 7.5,
  "read_count": "25967",
  "read_count_text": "2.6万人在读",
  "first_chapter_title": "第1章 兔子美，惹人追",
  "last_chapter_title": "第553章 阻逃离，找杰西（完）",
  "original_book_name": "帝元啊的第一本书"
}
```

> `cover_url` 是**相对路径**，用你的服务域名拼完整地址即可。

#### `GET /api/preview-cover/{key}`

按封面缓存 key 取封面图（`image/jpeg`）。`key` 来自 `preview` 返回的 `cover_url`。

#### `GET /api/preview-cover-by-book/{book_id}`

直接按书籍 ID 取封面图，**最省事的封面接口**。

```bash
curl -o cover.jpg "https://fq.huziyang.top/api/preview-cover-by-book/7137526579449564198"
# 实测：200，约 175KB，Content-Type: image/jpeg
```

#### `POST /api/preview/{book_id}/cleanup`

清理预览过程产生的封面临时文件（仅当目录内只含封面且无 `status.json` 时才删除）。

**响应**：`204 No Content`（无响应体）

> 注意：若配置中 `auto_clear_dump` 为 `false`，会直接返回 `204` 且不做任何清理。

---

### 5.5 下载任务

#### `GET /api/jobs`

列出任务。

**Query 参数**

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `id` | number | — | 按任务 ID 精确过滤 |
| `name` | string | — | 按书名或书 ID 模糊过滤（忽略大小写） |
| `all` | bool | `false` | `true` 时返回全部（含 2 小时前已完成的任务） |

**响应示例**

```json
{
  "items": [
    {
      "id": 1,
      "book_id": "7137526579449564198",
      "title": "凡人仙修传",
      "author": "帝元啊",
      "state": "running",
      "progress": {
        "group_done": 3,
        "group_total": 28,
        "saved_chapters": 120,
        "chapter_total": 553,
        "save_phase": "TextSave",
        "comment_fetch": 0,
        "comment_total": 0,
        "comment_saved": 0,
        "audiobook_generated": 0,
        "audiobook_skipped": 0,
        "audiobook_failed": 0
      },
      "message": null,
      "book_name_options": null,
      "format_options": null,
      "created_ms": 1791588603850,
      "updated_ms": 1791589000000
    }
  ],
  "done_retention_ms": 7200000
}
```

**`state` 取值**（`snake_case`）

| 值 | 含义 |
|---|---|
| `queued` | 排队中 |
| `running` | 下载中 |
| `done` | 已完成 |
| `failed` | 失败 |
| `canceled` | 已取消 |

**`progress.save_phase`**：`TextSave`（保存正文）| `Audiobook`（生成有声小说）

**`book_name_options` / `format_options`**：非空时表示**后端正在等待你为这个任务做选择**（见下两个接口）。每项形如 `{ "label": "...", "value": "..." }`。

---

#### `POST /api/jobs` ⭐ 创建下载任务

**请求体**

```json
{
  "book_id": "7137526579449564198",
  "range_start": null,
  "range_end": null
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `book_id` | string | 是 | 书籍 ID（支持分享链接/文本，后端会解析） |
| `range_start` | number \| null | 否 | 起始章节（从 1 开始） |
| `range_end` | number \| null | 否 | 结束章节 |

**响应（200）**

```json
{ "id": 2, "book_id": "7137526579449564198", "state": "queued" }
```

**错误**

| 状态码 | 原因 |
|---|---|
| `400` | `book_id` 为空或无法解析；章节范围非法（只给一个、或 `start<1`、或 `start>end`） |
| `429` | 已有任务在排队/下载中（并发限制 1） |

**行为说明**

- 创建后任务**异步**在后台线程执行，接口立即返回。
- 立即开始「准备下载计划」，随后写入任务元信息（书名、作者）。
- 下载过程中后端可能通过 `book_name_options` / `format_options` 向你提问。
- 支持断点续传（`DownloadMode::Resume`），失败章节会自动重试一次。

---

#### `POST /api/jobs/{id}/book_name`

提交书名选择（当 `book_name_options` 非空时调用）。

```json
{ "value": "凡人仙修传" }
```

响应：`{ "ok": true }`；任务不存在返回 `404`。

#### `POST /api/jobs/{id}/format`

提交输出格式选择（当 `format_options` 非空时调用）。

```json
{ "value": "epub" }
```

响应：`{ "ok": true }`；任务不存在返回 `404`。

> 两个接口的 `value` 传 `null` 表示使用默认值。

#### `POST /api/jobs/{id}/cancel`

请求取消任务（**会同时从任务列表移除**）。

响应：`{ "ok": true }`；`404` 任务不存在。

#### `DELETE /api/jobs/{id}`

从列表移除任务记录（不取消正在运行的任务）。

响应：`{ "ok": true }`；`404` 任务不存在。

---

### 5.6 下载历史

#### `GET /api/history`

读取历史下载记录。

**Query 参数**

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `limit` | number | `100` | 返回条数，自动限制在 `1..=1000` |
| `q` | string | — | 按书名关键词过滤 |

**响应示例**

```json
{
  "items": [
    {
      "book_id": "7085252860136393758",
      "book_name": "我都元婴期了，你跟我说开学？",
      "author": "妙妙酱丷",
      "status": "success",
      "progress": "成功 2555/2555 章，失败 0 章",
      "selected_chapters": 2555,
      "success_chapters": 2555,
      "failed_chapters": 0,
      "timestamp": "2026-10-09T23:29:55.310502171Z"
    }
  ],
  "limit": 100,
  "keyword": null
}
```

> `timestamp` 是 **RFC3339 / ISO-8601** 字符串，注意时区为 UTC。

---

### 5.7 书库浏览

#### `GET /api/library`

浏览已下载的文件（**异步扫描**，首次请求会触发扫描，需轮询）。

**Query 参数**

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `path` | string | `""` | 相对保存根目录的子路径（空 = 根目录） |
| `name` | string | — | 按文件名/文件夹名模糊过滤（忽略大小写） |
| `start` | bool | `true` | 是否触发一次新扫描；轮询时传 `false` |

**响应示例**

```json
{
  "root": "/app",
  "path": "",
  "items": [
    {
      "kind": "file",
      "name": "我都元婴期了，你跟我说开学？.epub",
      "rel_path": "我都元婴期了，你跟我说开学？.epub",
      "ext": "epub",
      "size": 9697295,
      "modified_ms": 1791588594375
    },
    {
      "kind": "dir",
      "name": "我都元婴期了，你跟我说开学？_audio",
      "rel_path": "我都元婴期了，你跟我说开学？_audio",
      "ext": "",
      "size": 0,
      "modified_ms": 1791588594375
    }
  ],
  "running": false,
  "scanned": 2,
  "error": null,
  "started_ms": 1791588603850,
  "updated_ms": 1791588603851
}
```

**字段说明**

| 字段 | 说明 |
|---|---|
| `running` | 扫描是否进行中（`true` 时建议 1~2 秒后重试） |
| `scanned` | 已扫描条目数 |
| `error` | 扫描错误信息（`null` 表示正常） |
| `kind` | `file` 或 `dir` |
| `ext` | 文件扩展名（`epub`/`txt`/`mp3`/`wav`），目录为空串 |

**注意**

- 只列出 `epub` / `txt` / `mp3` / `wav` 四类文件。
- 根目录下以「纯数字书籍 ID」命名且含 `status.json` 等标记的**内部缓存目录会被自动隐藏**。
- 目录的 `size` 恒为 `0`（不做递归统计，避免 IO 放大）。

---

### 5.8 文件下载

#### `GET /download/{rel_path}`

下载单个文件（流式）。

- `rel_path` 取自 `GET /api/library` 返回的 `rel_path`，**需做 URL 编码**（`/` 保留为路径分隔符）。
- 成功返回 `200`，响应头：

```
content-type: application/epub+zip
content-disposition: attachment; filename="____________.epub"; filename*=UTF-8''%E6%88%91...
```

**MIME 映射**：`epub` → `application/epub+zip`，`txt` → `text/plain; charset=utf-8`，`mp3` → `audio/mpeg`，`wav` → `audio/wav`

**错误**：`400` 路径非法；`403` 扩展名不在白名单或路径越界；`404` 文件不存在

#### `GET /download-zip/{rel_path}`

把文件或整个目录**打包成 zip** 下载（临时文件在响应结束后自动清理）。

- 目录会保持内部层级结构。
- 适合「文件夹内还有文件夹」的有声小说场景。

**错误**：同 `/download/`

---

### 5.9 配置读取与修改

配置分三层，按需要选择：

| 接口 | 定位 | 适用场景 |
|---|---|---|
| `/api/config` | **精简视图**（5 个字段） | 只关心输出格式、有声小说开关 |
| `/api/config/full` | **完整配置**（44 个字段，JSON） | 结构化读写全部配置 |
| `/api/config/raw` | **YAML 原文**（带注释） | 需要完整注释或低频字段 |

#### `GET /api/config`

```json
{
  "novel_format": "txt",
  "bulk_files": false,
  "enable_audiobook": false,
  "audiobook_format": "mp3",
  "ask_format_after_download": false
}
```

#### `POST /api/config`

**局部更新**（只传要改的字段，未传的保持不变）。

```json
{
  "novel_format": "epub",
  "enable_audiobook": true,
  "audiobook_format": "mp3",
  "bulk_files": false,
  "ask_format_after_download": false
}
```

| 字段 | 可选值 |
|---|---|
| `novel_format` | `txt`、`epub` |
| `audiobook_format` | `mp3`、`wav` |

**响应**：`{ "ok": true }`；非法值返回 `400`

> 约束：`enable_segment_comments` 为真但 `novel_format != "epub"` 时，后端会**自动关闭段评**。

#### `GET /api/config/full`

返回完整配置对象（44 个字段）。当前实测字段：

```json
{
  "old_cli": false,
  "max_workers": 1,
  "request_timeout": 15,
  "max_retries": 3,
  "max_wait_time": 1200,
  "min_wait_time": 1000,
  "min_connect_timeout": 3.05,
  "novel_format": "txt",
  "bulk_files": false,
  "auto_clear_dump": true,
  "auto_open_downloaded_files": false,
  "enable_audiobook": false,
  "audiobook_voice": "zh-CN-XiaoxiaoNeural",
  "audiobook_rate": "+0%",
  "audiobook_volume": "+0%",
  "audiobook_pitch": "",
  "audiobook_format": "mp3",
  "audiobook_concurrency": 24,
  "audiobook_tts_provider": "edge",
  "audiobook_tts_api_url": "",
  "audiobook_tts_api_token": "",
  "audiobook_tts_model": "",
  "save_path": "",
  "use_official_api": true,
  "api_endpoints": [],
  "enable_segment_comments": false,
  "segment_comments_top_n": 10,
  "segment_comments_workers": 32,
  "download_comment_images": true,
  "download_comment_avatars": true,
  "media_download_workers": 8,
  "blocked_media_domains": ["p-passport-sign.bytedance.net"],
  "force_convert_images_to_jpeg": false,
  "jpeg_retry_convert": true,
  "jpeg_quality": 90,
  "convert_heic_to_jpeg": true,
  "keep_heic_original": false,
  "first_line_indent_em": 2.0,
  "media_limit_per_chapter": 0,
  "media_max_dimension_px": 1280,
  "allow_overwrite_files": true,
  "preferred_book_name_field": "book_name",
  "ask_format_after_download": false,
  "pdf_font_path": null
}
```

> ⚠️ `audiobook_tts_api_token` 是**明文返回**的敏感字段，调用方请勿写入日志或前端暴露。

#### `POST /api/config/full`

**整体覆盖**保存（必须提交完整对象）。

- 请求体：完整的配置对象（结构同 `GET`）
- 响应：`{ "ok": true }`
- 校验失败返回 `400`

> 建议流程：先 `GET` → 修改目标字段 → 原样 `POST` 回整个对象，避免漏字段被重置为默认值。

#### `GET /api/config/raw`

```json
{
  "yaml": "# 是否使用老版本命令行界面\nold_cli: false\n# 最大并发线程数\nmax_workers: 128\n...",
  "generated": "..."
}
```

#### `POST /api/config/raw`

```json
{ "yaml": "old_cli: false\nmax_workers: 1\n..." }
```

- 后端会做 YAML 解析 + 配置校验，**非法则返回 `400` 且不写入**（不会写坏配置文件）
- 响应：`{ "ok": true }`

---

### 5.10 更新检查

#### `GET /api/updates`

检查本地已下载书籍的**章节更新**。

```json
{
  "running": true,
  "scanned": 0,
  "total": 0,
  "save_dir": "/app",
  "updates": [],
  "no_updates": [],
  "error": null,
  "started_ms": 1791590775827,
  "updated_ms": 1791590775827
}
```

| 字段 | 说明 |
|---|---|
| `running` | 检查是否进行中，`true` 时轮询本接口即可 |
| `updates` | 有新章节的书籍列表 |
| `no_updates` | 确认无更新的书籍列表 |

#### `GET /api/app_update`

检查**程序自身**是否有新版本。

```json
{
  "current": "2.4.15",
  "current_tag": "v2.4.15",
  "latest_tag": "v2.4.15",
  "latest_name": "Docker build",
  "latest_url": null,
  "latest_body": "Docker 构建已禁用程序自更新，请通过重新拉取镜像进行升级。",
  "published_at": null,
  "has_update": false,
  "docker_build": true
}
```

> Docker 构建下 `has_update` 恒为 `false`，升级请重新拉取镜像。

#### `GET /api/self_update`

查询自更新进度状态。

```json
{
  "state": "idle",
  "stage": "idle",
  "percent": 0,
  "message": "尚未开始",
  "updated_ms": 1791588097275
}
```

`state` 取值：`idle` / `running` / `done` / `failed`

#### `POST /api/self_update`

触发程序自更新（**Docker 构建下不可用**）。

---

## 6. 典型调用流程

### 6.1 完整下载一本书

```text
① GET  /api/status                    → 确认服务可用、查看 locked 判断是否需要认证
② GET  /api/search?q=关键词            → 拿到 book_id
③ POST /api/jobs {book_id}            → 创建任务，拿到 id
④ GET  /api/jobs                      → 轮询 state / progress（建议 2 秒一次）
      ├─ 若 book_name_options 非空 → POST /api/jobs/{id}/book_name
      └─ 若 format_options   非空 → POST /api/jobs/{id}/format
⑤ 直到 state == "done"
⑥ GET  /api/library                   → 找到新生成的文件 rel_path
⑦ GET  /download/{rel_path}           → 下载文件
```

### 6.2 只下载指定章节

```bash
curl -X POST "https://fq.huziyang.top/api/jobs" \
     -H "Content-Type: application/json" \
     -d '{"book_id":"7137526579449564198","range_start":1,"range_end":100}'
```

### 6.3 定时更新本地已有书籍

后端 CLI 已禁止新建下载，但你可以用 API 实现「更新」：对每本书 `POST /api/jobs` 相同 `book_id`（下载模式为断点续传，已存在章节会跳过）。

```bash
#!/usr/bin/env bash
# 每分钟检查一次，串行提交（因为并发限制是 1）
for bid in 7137526579449564198 7085252860136393758; do
  until curl -s "https://fq.huziyang.top/api/jobs" | grep -q '"state":"running"\|"state":"queued"' ; do
    :
  done
  curl -s -X POST "https://fq.huziyang.top/api/jobs" \
       -H "Content-Type: application/json" -d "{\"book_id\":\"$bid\"}"
  # 等待该任务完成
  while curl -s "https://fq.huziyang.top/api/jobs" | grep -q '"state":"running"\|"state":"queued"'; do
    sleep 5
  done
done
```

> 更稳妥的做法：轮询 `GET /api/jobs` 判断活跃任务数，而不是靠 shell 字符串匹配。

---

## 7. 多语言调用示例

### 7.1 JavaScript / TypeScript

```ts
const BASE = 'https://fq.huziyang.top'

/** 无密码锁时无需任何认证；有密码锁时用请求头 */
const HEADERS: Record<string, string> = {
  'Content-Type': 'application/json',
  // 'x-tomato-password': process.env.TOMATO_PASSWORD!,
}

async function createJob(bookId: string, range?: [number, number]) {
  const res = await fetch(`${BASE}/api/jobs`, {
    method: 'POST',
    headers: HEADERS,
    body: JSON.stringify({
      book_id: bookId,
      range_start: range?.[0] ?? null,
      range_end: range?.[1] ?? null,
    }),
  })
  if (res.status === 429) throw new Error('已有任务在下载，请等待完成')
  if (!res.ok) throw new Error(`创建失败: ${res.status} ${await res.text()}`)
  return res.json() as Promise<{ id: number; book_id: string; state: string }>
}

/** 轮询直到任务结束 */
async function waitJob(id: number, onTick?: (j: any) => void) {
  for (;;) {
    const r = await fetch(`${BASE}/api/jobs?id=${id}`, { headers: HEADERS })
    const { items } = await r.json()
    const job = items[0]
    if (!job) throw new Error('任务已不存在')
    onTick?.(job)

    // 后端提问时及时回答，否则任务会一直等待
    if (job.book_name_options?.length) {
      await fetch(`${BASE}/api/jobs/${id}/book_name`, {
        method: 'POST', headers: HEADERS,
        body: JSON.stringify({ value: job.book_name_options[0].value }),
      })
    }
    if (job.format_options?.length) {
      await fetch(`${BASE}/api/jobs/${id}/format`, {
        method: 'POST', headers: HEADERS,
        body: JSON.stringify({ value: job.format_options[0].value }),
      })
    }

    if (['done', 'failed', 'canceled'].includes(job.state)) return job
    await new Promise((r) => setTimeout(r, 2000))
  }
}
```

### 7.2 Python

```python
import time, requests

BASE = "https://fq.huziyang.top"
SESSION = requests.Session()
# 有密码锁时：
# SESSION.headers["x-tomato-password"] = "你的密码"
# 或 SESSION.post(f"{BASE}/api/login", json={"password": "你的密码"})

def status():
    return SESSION.get(f"{BASE}/api/status", timeout=10).json()

def search(keyword: str):
    return SESSION.get(f"{BASE}/api/search", params={"q": keyword}, timeout=30).json()["items"]

def create_job(book_id: str, range_start=None, range_end=None):
    r = SESSION.post(f"{BASE}/api/jobs", json={
        "book_id": book_id,
        "range_start": range_start,
        "range_end": range_end,
    }, timeout=30)
    if r.status_code == 429:
        raise RuntimeError("已有任务在下载，请等待完成")
    r.raise_for_status()
    return r.json()

def wait_job(job_id: int, timeout=3600):
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = SESSION.get(f"{BASE}/api/jobs", params={"id": job_id}, timeout=15).json()["items"][0]
        # 回答后端的提问
        if job.get("book_name_options"):
            SESSION.post(f"{BASE}/api/jobs/{job_id}/book_name",
                         json={"value": job["book_name_options"][0]["value"]}, timeout=15)
        if job.get("format_options"):
            SESSION.post(f"{BASE}/api/jobs/{job_id}/format",
                         json={"value": job["format_options"][0]["value"]}, timeout=15)
        if job["state"] in ("done", "failed", "canceled"):
            return job
        time.sleep(2)
    raise TimeoutError("等待任务超时")

def library(path=""):
    return SESSION.get(f"{BASE}/api/library", params={"path": path, "start": "false"}, timeout=30).json()

def download(rel_path: str, dest: str):
    from urllib.parse import quote
    url = f"{BASE}/download/" + "/".join(quote(s) for s in rel_path.split("/"))
    with SESSION.get(url, stream=True, timeout=120) as r:
        r.raise_for_status()
        with open(dest, "wb") as f:
            for chunk in r.iter_content(1 << 16):
                f.write(chunk)
```

### 7.3 反向代理注意事项（若你的项目也在同一台机器）

你的项目若与下载器同机，建议直接调用 `http://127.0.0.1:18423`，可绕过 Nginx 与 HTTPS 开销：

```python
BASE = "http://127.0.0.1:18423"
```

---

## 附：接口速查表

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | 服务状态与基础配置 |
| POST | `/api/login` | 登录（密码锁模式） |
| GET | `/api/search?q=` | 搜索书籍 |
| GET | `/api/preview/{book_id}` | 书籍详情 |
| GET | `/api/preview-cover/{key}` | 封面图（按缓存 key） |
| GET | `/api/preview-cover-by-book/{book_id}` | 封面图（按书籍 ID） |
| POST | `/api/preview/{book_id}/cleanup` | 清理预览临时文件（204） |
| GET | `/api/jobs` | 任务列表（`id`/`name`/`all`） |
| POST | `/api/jobs` | **创建下载任务** |
| POST | `/api/jobs/{id}/book_name` | 提交书名选择 |
| POST | `/api/jobs/{id}/format` | 提交格式选择 |
| POST | `/api/jobs/{id}/cancel` | 取消任务并移除 |
| DELETE | `/api/jobs/{id}` | 移除任务记录 |
| GET | `/api/history` | 下载历史（`limit`/`q`） |
| GET | `/api/library` | 书库浏览（`path`/`name`/`start`） |
| GET | `/download/{rel_path}` | 下载单个文件（流式） |
| GET | `/download-zip/{rel_path}` | 打包 zip 下载 |
| GET | `/api/config` | 精简配置 |
| POST | `/api/config` | 局部更新配置 |
| GET | `/api/config/full` | 完整配置（44 项） |
| POST | `/api/config/full` | 整体覆盖配置 |
| GET | `/api/config/raw` | YAML 原文（带注释） |
| POST | `/api/config/raw` | 保存 YAML |
| GET | `/api/updates` | 检查书籍更新 |
| GET | `/api/app_update` | 检查程序新版本 |
| GET | `/api/self_update` | 自更新进度 |
| POST | `/api/self_update` | 触发自更新（Docker 不可用） |
| GET | `/api/manage/health` | **删除服务**健康检查 |
| GET | `/api/manage/delete?...&dry_run=1` | **删除预演**（只返回清单） |
| POST | `/api/manage/delete` | **执行删除**（小说 + 文件 + 缓存） |
| GET | `/api/manage/history` | **列出原始历史记录** |
| POST | `/api/manage/history/delete` | **删除下载历史记录** |

**共 32 个接口**（含 2 个文件流接口）。

---

## 8. 删除接口（扩展能力，非上游原生）

> ⚠️ **这部分不是上游后端提供的**。上游 v2.4.15 **没有任何删除接口**，只读挂载无法实现文件删除。
> 因此额外部署了一个极小的 sidecar 服务 `tomato-manage`（Python stdlib，无第三方依赖），
> 与后端**共享同一份书籍目录**，只监听回环地址 `127.0.0.1:18425`，
> 经 Nginx 反代 `/api/manage/` 暴露。
>
> 源码：`/www/wwwroot/fq.huziyang.top/manage/delete_service.py`

### 8.1 `GET /api/manage/health`

```json
{ "ok": true, "library_root": "/books", "token": false }
```

### 8.2 `GET /api/manage/delete` — 删除预演（只读，安全）

**必须先预演再执行**，用于在删除前展示将影响哪些文件。

**Query 参数**（二选一）

| 参数 | 说明 |
|---|---|
| `book_id` | 书籍 ID（纯数字），按 ID 定位缓存目录 |
| `path` | 相对书籍根目录的路径（文件名或目录名） |
| `dry_run` | 传 `1` 时只返回清单，**不做任何删除** |

**响应示例**

```json
{
  "dry_run": true,
  "would_delete": [
    { "name": "7085252860136393758", "rel_path": "7085252860136393758", "kind": "dir", "size": 71470279 },
    { "name": "我都元婴期了，你跟我说开学？.epub", "rel_path": "我都元婴期了，你跟我说开学？.epub", "kind": "file", "size": 9697295 }
  ],
  "total_size": 81167574
}
```

### 8.3 `POST /api/manage/delete` — 执行删除

**请求体**

```json
{ "book_id": "7085252860136393758", "path": "我都元婴期了，你跟我说开学？.epub" }
```

两个字段都可选，但至少给一个。同时给出时会**合并**两者的清理范围。

**响应示例**

```json
{
  "ok": true,
  "deleted": ["7085252860136393758", "我都元婴期了，你跟我说开学？.epub"],
  "failed": [],
  "total_size": 81167574
}
```

**错误码**

| 状态码 | 含义 |
|---|---|
| `400` | `path` 非法（含 `..`、越界）或文件类型不允许 |
| `401` | 开启了 `MANAGE_TOKEN` 但未携带 `X-Manage-Token` 头 |
| `404` | 目标不存在 |
| `409` | **该书正在下载中**，需先取消任务 |
| `207` | 部分成功（存在删除失败项，见 `failed`） |

### 8.4 删除范围规则（重要）

后端的数据组织有两套命名，删除服务会**双向关联**，确保一次清干净：

| 数据 | 命名 | 说明 |
|---|---|---|
| 输出文件 | `<书名>.epub` / `.txt` / `.mp3` / `.wav` | 用**书名**命名 |
| 有声小说目录 | `<书名>_audio/` | 用**书名**命名 |
| 缓存目录 | `<书号>/` 或 `<书号>_<书名>/` | 用**书号**命名，内含 `status.json` / `downloaded_chapters.jsonl` / `images/` |

- 传 `book_id`：先定位缓存目录，再读缓存目录里的 **`status.json → book_name`** 反查同书名的输出文件与音频目录；
- 传 `path`（书名）：先删该文件，再找同名缓存目录与音频目录；
- 传 `path`（缓存目录）：读取目录内 `status.json` 反查书名，连带删除输出文件。

**实测**：删除《我都元婴期了，你跟我说开学？》时，一次清理了 `epub`（9.7MB）+ 缓存目录（71.5MB），共 **77.4MB**，容器内外文件同步消失。

### 8.5 安全设计

1. 仅绑定 `127.0.0.1:18425`，不暴露公网，由 Nginx 精确匹配 `^~ /api/manage/` 转发；
2. 所有路径在书籍根目录下做 `..` 段拒绝 + `canonicalize` 前缀校验，防目录穿越；
3. 可选 `MANAGE_TOKEN` 环境变量：设置后请求必须带 `X-Manage-Token` 头；
4. 删除前读取后端 `/api/jobs`，**拒绝删除正在下载的书**（409）；
5. 文件扩展名白名单（仅 `epub`/`txt`/`mp3`/`wav`）。

### 8.6 调用示例

```bash
# 1) 先预演，确认要删什么
curl -s "https://fq.huziyang.top/api/manage/delete?book_id=7085252860136393758&dry_run=1"

# 2) 确认无误后执行
curl -s -X POST "https://fq.huziyang.top/api/manage/delete" \
     -H "Content-Type: application/json" \
     -d '{"book_id":"7085252860136393758"}'
```

> 注意：删除文件**不会**删除 `/api/history` 里的历史记录（那是后端内部数据，无删除接口），
> 历史页仍会显示该书记录，这是预期行为。如需一起清理历史记录，见 8.7。

### 8.7 下载历史记录的删除

后端 `GET /api/history` 是**只读**的，但历史数据本身是磁盘上的一个 JSONL 文件：

```
/data/logs/download_history.jsonl     # 每行一条 JSON
```

后端每次请求都**重新读取该文件**（不缓存），所以**改文件即刻生效，无需重启后端**。
删除服务因此可以直接操作这个文件。

#### `GET /api/manage/history`

列出服务器上的原始历史记录（顺序与 `/api/history` 一致，新的在前）。

| Query | 说明 |
|---|---|
| `book_id` | 可选，只看某本书的记录 |

```json
{
  "items": [
    {
      "timestamp": "2026-10-10T00:30:21.429239682Z",
      "book_id": "7137526579449564198",
      "book_name": "凡人仙修传",
      "author": "帝元啊",
      "selected_chapters": 2,
      "success_chapters": 2,
      "failed_chapters": 0,
      "progress": "成功 2/2 章，失败 0 章",
      "status": "success"
    }
  ],
  "total": 1,
  "file": "/data/logs/download_history.jsonl"
}
```

#### `POST /api/manage/history/delete`

**请求体**

```json
{
  "book_id": "7137526579449564198",
  "timestamp": "2026-10-10T00:30:21.429239682Z"
}
```

| 字段 | 说明 |
|---|---|
| `book_id` | 按书籍 ID 匹配 |
| `timestamp` | 精确匹配某一条记录的时间戳 |

- **两个都给**：只删除**这一条**记录（同书多次下载会产生多条，用于精确删除）；
- **只给 `book_id`**：删除该书的**全部**历史记录；
- **只给 `timestamp`**：删除该时间戳的记录。

**响应示例**

```json
{
  "ok": true,
  "deleted_count": 1,
  "deleted": [
    { "book_id": "7137526579449564198", "book_name": "凡人仙修传", "timestamp": "2026-10-10T00:30:21.429239682Z" }
  ],
  "remaining": 1,
  "history_file": "/data/logs/download_history.jsonl"
}
```

**错误码**

| 状态码 | 含义 |
|---|---|
| `400` | 既没给 `book_id` 也没给 `timestamp` |
| `404` | 历史文件不存在，或没有匹配的记录 |
| `500` | 写入历史文件失败 |

**实现细节（安全）**

- 逐行读取，只删匹配行，**无法解析的坏行原样保留**（不会误伤数据）；
- 写入采用「写临时文件 + `os.replace()` 原子替换」，避免写坏或读到半截文件；
- 后端以 `O_APPEND` 追加写，与原子替换不冲突（极端并发下最多丢失一条刚追加的记录）。

#### 前端行为

历史页每条记录的「删除」按钮现在做的是：

1. **删除这条历史记录**（主操作）；
2. 若服务器上还检测到该书的文件（`dry_run` 预演有结果），会显示一个开关，
   **可选**「同时删除服务器上的文件」——默认关闭，避免误删。

> 历史页原来的报错「目标不存在」，原因是该记录对应的文件早已被删除，
> 而当时按钮走的是文件删除逻辑。现在记录删除与文件删除已解耦。


---

## 免责声明

本 API 属于「番茄小说下载器」项目（<https://github.com/zhongbai2333/Tomato-Novel-Downloader>）的服务端能力。
下载内容仅供**个人阅读**使用，请勿转载、分发或用于商业用途；使用本项目产生的一切后果由使用者自行承担。
