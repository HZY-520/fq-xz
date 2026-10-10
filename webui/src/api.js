/**
 * 后端 API 客户端
 *
 * 对应后端（Rust/Axum）契约，全部使用相对路径，
 * 因此前端部署在任意域名下都由 Nginx 反代到后端，无需配置 API 地址。
 *
 * 认证：后端在密码锁模式下使用 Cookie（tomato_session / auth_token）会话，
 * 所以所有请求都要带 credentials: 'same-origin'。
 */

class ApiError extends Error {
  constructor(status, message, payload) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.payload = payload
  }
}

async function request(method, url, body, options = {}) {
  const init = {
    method,
    credentials: 'same-origin',
    headers: {},
    ...options,
  }

  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }

  let res
  try {
    res = await fetch(url, init)
  } catch (err) {
    throw new ApiError(0, `网络错误：无法连接后端（${err.message}）`, null)
  }

  const text = await res.text()
  let payload = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      payload = text
    }
  }

  if (!res.ok) {
    const msg =
      (payload && typeof payload === 'object' && payload.error) ||
      (typeof payload === 'string' && payload.slice(0, 200)) ||
      `请求失败（HTTP ${res.status}）`
    throw new ApiError(res.status, msg, payload)
  }

  return payload
}

const get = (url) => request('GET', url)
const post = (url, body) => request('POST', url, body)
const del = (url) => request('DELETE', url)

const qs = (params) => {
  const sp = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') sp.set(k, v)
  })
  const s = sp.toString()
  return s ? `?${s}` : ''
}

export const api = {
  // ── 状态 ──────────────────────────────────────────────
  status: () => get('/api/status'),

  // ── 认证 ──────────────────────────────────────────────
  login: (password) => post('/api/login', { password }),

  // ── 搜索 ──────────────────────────────────────────────
  // 返回 { items: [{ book_id, title, author, raw }] }
  search: (q) => get(`/api/search${qs({ q })}`),

  // ── 任务 ──────────────────────────────────────────────
  // 返回 { items: [JobInfo], done_retention_ms }
  // JobInfo: { id, book_id, title, author, state, progress, message,
  //            book_name_options, format_options, created_ms, updated_ms }
  // state: queued | running | done | failed | canceled
  listJobs: (params) => get(`/api/jobs${qs(params)}`),
  createJob: (bookId, rangeStart, rangeEnd) =>
    post('/api/jobs', {
      book_id: bookId,
      range_start: rangeStart ?? null,
      range_end: rangeEnd ?? null,
    }),
  cancelJob: (id) => post(`/api/jobs/${id}/cancel`),
  deleteJob: (id) => del(`/api/jobs/${id}`),
  submitBookName: (id, value) => post(`/api/jobs/${id}/book_name`, { value }),
  submitFormat: (id, value) => post(`/api/jobs/${id}/format`, { value }),

  // ── 历史 ──────────────────────────────────────────────
  // 返回 { items: [{ author, book_id, book_name, failed_chapters, progress,
  //                  selected_chapters, status, success_chapters, timestamp }],
  //        limit, keyword }
  history: (params) => get(`/api/history${qs(params)}`),

  // ── 书库 ──────────────────────────────────────────────
  // 返回 { root, path, items: [{ kind, name, rel_path, ext, size, modified_ms }],
  //        running, scanned, error, started_ms, updated_ms }
  library: (params) => get(`/api/library${qs(params)}`),

  // ── 配置 ──────────────────────────────────────────────
  configView: () => get('/api/config'),
  saveConfigView: (patch) => post('/api/config', patch),
  configFull: () => get('/api/config/full'),
  saveConfigFull: (cfg) => post('/api/config/full', cfg),
  configRaw: () => get('/api/config/raw'),
  saveConfigRaw: (yaml) => post('/api/config/raw', { yaml }),

  // ── 更新 ──────────────────────────────────────────────
  // 返回 { items: [...] } 形式，具体字段以实际为准
  updates: () => get('/api/updates'),
  appUpdate: () => get('/api/app_update'),
  selfUpdate: () => get('/api/self_update'),
  doSelfUpdate: () => post('/api/self_update'),

  // ── 文件下载（直接走浏览器，保留 Cookies）──────────────
  fileUrl: (relPath) => `/download/${encodePath(relPath)}`,
  zipUrl: (relPath) => `/download-zip/${encodePath(relPath)}`,

  // ── 预览 ──────────────────────────────────────────────
  preview: (bookId) => get(`/api/preview/${bookId}`),
  coverUrl: (bookId) => `/api/preview-cover-by-book/${bookId}`,

  // ── 删除书籍文件（后端无此能力，由 sidecar 提供）─────────
  // 预演：只返回将删除的清单，不做任何修改
  // 支持 { path }（相对 library_root 的路径）或 { bookId }
  deletePreview: ({ path, bookId } = {}) =>
    get(`/api/manage/delete${qs({ path, book_id: bookId, dry_run: 1 })}`),
  // 执行删除
  deleteBook: ({ path, bookId } = {}) =>
    post('/api/manage/delete', {
      path: path || null,
      book_id: bookId ? String(bookId) : null,
    }),
  manageHealth: () => get('/api/manage/health'),

  // ── 下载历史记录管理（后端无删除接口，由 sidecar 直接改 JSONL）──
  listHistoryRaw: (bookId) => get(`/api/manage/history${qs({ book_id: bookId })}`),
  // 精确删除单条历史记录（book_id + timestamp 共同定位）
  deleteHistory: ({ bookId, timestamp }) =>
    post('/api/manage/history/delete', {
      book_id: bookId ? String(bookId) : null,
      timestamp: timestamp || null,
    }),
}

function encodePath(p) {
  return String(p)
    .split('/')
    .map((seg) => encodeURIComponent(seg))
    .join('/')
}

export { ApiError }
