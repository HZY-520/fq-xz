<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import Icon from './components/Icon.vue'
import ToastHost from './components/ToastHost.vue'
import { api } from './api.js'
import {
  ui,
  toggleTheme,
  startStatusPolling,
  stopStatusPolling,
  refreshStatus,
  refreshActiveJobs,
  toastOk,
  toastErr,
} from './store.js'

const route = useRoute()

const NAV = [
  { name: 'search', label: '搜索书籍', icon: 'search' },
  { name: 'jobs', label: '下载任务', icon: 'download', badge: true },
  { name: 'history', label: '下载历史', icon: 'history' },
  { name: 'config', label: '设置', icon: 'settings' },
  { name: 'about', label: '关于', icon: 'info' },
]

const pageTitle = computed(() => route.meta.title || '番茄小说下载器')
const pageSub = computed(() => route.meta.sub || '')

const locked = computed(() => ui.status?.locked === true)
const authed = ref(false)
const showLogin = computed(() => locked.value && !authed.value)
const loginPwd = ref('')
const loginBusy = ref(false)
const loginError = ref('')

const backendOk = computed(() => !!ui.status && !ui.statusError)

function closeSidebar() {
  ui.sidebarOpen = false
}

// 移动端：路由切换后自动收起抽屉
watch(
  () => route.fullPath,
  () => closeSidebar(),
)

async function doLogin() {
  loginBusy.value = true
  loginError.value = ''
  try {
    await api.login(loginPwd.value)
    authed.value = true
    loginPwd.value = ''
    toastOk('登录成功')
    await refreshStatus()
  } catch (err) {
    loginError.value =
      err.status === 401
        ? '密码错误'
        : err.status === 429
          ? '尝试过于频繁，已被临时限制，请稍后再试'
          : err.message
  } finally {
    loginBusy.value = false
  }
}

async function manualRefresh() {
  await refreshStatus()
  await refreshActiveJobs()
  if (ui.statusError) toastErr(`刷新失败：${ui.statusError}`)
}

// 全局快捷键：R 刷新（不影响输入框内输入）
function onKeydown(e) {
  if (e.target?.tagName === 'INPUT' || e.target?.tagName === 'TEXTAREA') return
  if (e.key === 'r' || e.key === 'R') manualRefresh()
}

onMounted(async () => {
  // 先探测后端是否开启密码锁；未锁定则视为已授权
  try {
    const s = await api.status()
    ui.status = s
    if (!s.locked) authed.value = true
  } catch (err) {
    ui.statusError = err.message
  }
  startStatusPolling(5000)
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  stopStatusPolling()
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="shell">
    <!-- 移动端侧边栏遮罩 -->
    <div class="sidebar-backdrop" :class="{ show: ui.sidebarOpen }" @click="closeSidebar" />

    <!-- ── 侧边栏 ─────────────────────────────────────────────── -->
    <aside class="sidebar" :class="{ open: ui.sidebarOpen }">
      <div class="brand">
        <div class="brand-mark"><Icon name="bookOpen" :size="19" /></div>
        <div class="brand-text">
          <div class="brand-title">小番茄</div>
          <div class="brand-sub">番茄小说下载器</div>
        </div>
      </div>

      <nav class="nav">
        <div class="nav-group-label">功能</div>
        <RouterLink
          v-for="item in NAV"
          :key="item.name"
          :to="{ name: item.name }"
          class="nav-item"
          :class="{ active: route.name === item.name }"
          @click="closeSidebar"
        >
          <Icon :name="item.icon" :size="16" />
          <span>{{ item.label }}</span>
          <span v-if="item.badge && ui.activeJobs > 0" class="nav-badge">{{ ui.activeJobs }}</span>
        </RouterLink>
      </nav>

      <div class="sidebar-foot">
        <button class="nav-item" @click="toggleTheme">
          <Icon :name="ui.theme === 'dark' ? 'sun' : 'moon'" :size="16" />
          <span>{{ ui.theme === 'dark' ? '切换到浅色' : '切换到深色' }}</span>
        </button>
        <div class="row text-xs muted" style="padding: 4px 10px">
          <span
            class="dot"
            :class="{ 'dot-pulse': backendOk }"
            :style="{ color: backendOk ? 'var(--success)' : 'var(--danger)' }"
          />
          <span>{{ backendOk ? '后端已连接' : '后端未连接' }}</span>
          <span v-if="ui.status" class="spacer mono">v{{ ui.status.version }}</span>
        </div>
      </div>
    </aside>

    <!-- ── 主区域 ─────────────────────────────────────────────── -->
    <div class="main">
      <header class="topbar">
        <button class="btn-icon menu-btn" title="菜单" @click="ui.sidebarOpen = !ui.sidebarOpen">
          <Icon name="menu" />
        </button>
        <div>
          <h1>{{ pageTitle }}</h1>
          <div v-if="pageSub" class="topbar-sub">{{ pageSub }}</div>
        </div>
        <div class="topbar-actions">
          <span v-if="ui.status?.prewarm_in_progress" class="badge badge-warning">
            <span class="dot dot-pulse" />预热中
          </span>
        </div>
      </header>

      <main class="content">
        <RouterView v-if="!showLogin" />
        <div v-else class="card" style="max-width: 420px; margin: 40px auto">
          <div class="card-body stack">
            <div class="center">
              <div style="font-size: 30px; color: var(--primary)"><Icon name="lock" :size="30" /></div>
              <h2 style="margin-top: 8px">需要密码</h2>
              <p class="muted text-sm">该服务已开启密码锁，请输入访问密码</p>
            </div>
            <div class="field">
              <label class="field-label" for="pwd">密码</label>
              <input
                id="pwd"
                v-model="loginPwd"
                class="input"
                type="password"
                placeholder="请输入密码"
                autocomplete="current-password"
                @keyup.enter="doLogin"
              />
            </div>
            <p v-if="loginError" class="text-sm" style="color: var(--danger)">{{ loginError }}</p>
            <button class="btn btn-primary" :disabled="loginBusy || !loginPwd" @click="doLogin">
              <span v-if="loginBusy" class="spinner" />
              {{ loginBusy ? '登录中…' : '登录' }}
            </button>
          </div>
        </div>
      </main>
    </div>

    <ToastHost />
  </div>
</template>

<style scoped>
/* 桌面端隐藏移动菜单按钮，窄屏显示 */
.menu-btn {
  display: none;
}

@media (max-width: 860px) {
  .menu-btn {
    display: inline-flex;
  }
}
</style>
