import { reactive } from 'vue'
import { api } from './api.js'

/* ── 主题 ─────────────────────────────────────────────────────────────── */
const THEME_KEY = 'tomato-theme'

export const ui = reactive({
  theme: localStorage.getItem(THEME_KEY) || 'dark',
  sidebarOpen: false,
  toasts: [],
  status: null,
  statusError: null,
  activeJobs: 0,
  lastUpdated: null,
})

export function applyTheme(theme) {
  ui.theme = theme
  document.documentElement.setAttribute('data-theme', theme)
  localStorage.setItem(THEME_KEY, theme)
}

export function toggleTheme() {
  applyTheme(ui.theme === 'dark' ? 'light' : 'dark')
}

applyTheme(ui.theme)

/* ── Toast 通知 ───────────────────────────────────────────────────────── */
let toastSeq = 0

export function toast(message, type = 'info', timeout = 4000) {
  const id = ++toastSeq
  ui.toasts.push({ id, message: String(message), type })
  if (timeout > 0) {
    setTimeout(() => dismissToast(id), timeout)
  }
  return id
}

export function dismissToast(id) {
  const i = ui.toasts.findIndex((t) => t.id === id)
  if (i !== -1) ui.toasts.splice(i, 1)
}

export const toastOk = (m) => toast(m, 'success')
export const toastErr = (m) => toast(m, 'error', 7000)
export const toastWarn = (m) => toast(m, 'warning', 6000)

/* ── 全局状态轮询 ─────────────────────────────────────────────────────── */
let statusTimer = null

export async function refreshStatus() {
  try {
    const data = await api.status()
    ui.status = data
    ui.statusError = null
    ui.lastUpdated = Date.now()
  } catch (err) {
    ui.statusError = err.message
  }
}

export async function refreshActiveJobs() {
  try {
    const data = await api.listJobs()
    ui.activeJobs = (data.items || []).filter(
      (j) => j.state === 'queued' || j.state === 'running',
    ).length
  } catch {
    /* 轮询失败静默，避免打扰 */
  }
}

export function startStatusPolling(intervalMs = 5000) {
  if (statusTimer) return
  refreshStatus()
  refreshActiveJobs()
  statusTimer = setInterval(() => {
    refreshStatus()
    refreshActiveJobs()
  }, intervalMs)
}

export function stopStatusPolling() {
  if (statusTimer) {
    clearInterval(statusTimer)
    statusTimer = null
  }
}

/* ── 格式化工具 ───────────────────────────────────────────────────────── */
export function formatBytes(bytes) {
  if (!bytes || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const val = bytes / 1024 ** i
  return `${val >= 100 || i === 0 ? Math.round(val) : val.toFixed(1)} ${units[i]}`
}

export function formatTime(ms) {
  if (!ms) return '-'
  const d = new Date(ms)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export function formatRelative(ms) {
  if (!ms) return '-'
  const diff = Date.now() - ms
  if (diff < 0) return '刚刚'
  const s = Math.floor(diff / 1000)
  if (s < 60) return `${s} 秒前`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} 分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 小时前`
  return `${Math.floor(h / 24)} 天前`
}

/* ── 任务状态映射 ─────────────────────────────────────────────────────── */
export const JOB_STATE = {
  queued: { label: '排队中', badge: 'badge-info', progress: 'indeterminate' },
  running: { label: '下载中', badge: 'badge-primary', progress: '' },
  done: { label: '已完成', badge: 'badge-success', progress: 'done' },
  failed: { label: '失败', badge: 'badge-danger', progress: '' },
  canceled: { label: '已取消', badge: 'badge-warning', progress: '' },
}

export function jobStateInfo(state) {
  return JOB_STATE[state] || { label: state || '未知', badge: '', progress: '' }
}
