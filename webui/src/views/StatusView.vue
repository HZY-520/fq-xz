<script setup>
import { computed, onMounted, ref } from 'vue'
import Icon from '../components/Icon.vue'
import { api } from '../api.js'
import { ui, refreshStatus, toastErr, formatRelative } from '../store.js'

const jobs = ref([])
const history = ref([])
const loading = ref(false)

const s = computed(() => ui.status)

const activeJobs = computed(() =>
  jobs.value.filter((j) => j.state === 'queued' || j.state === 'running'),
)
const recentJobs = computed(() => jobs.value.slice(0, 5))

const totalChapters = computed(() =>
  history.value.reduce((sum, h) => sum + (h.success_chapters || 0), 0),
)

async function load() {
  loading.value = true
  try {
    const [j, h] = await Promise.all([
      api.listJobs(),
      api.history({ limit: 100 }),
    ])
    jobs.value = j.items || []
    history.value = h.items || []
  } catch (err) {
    toastErr(`加载概况失败：${err.message}`)
  } finally {
    loading.value = false
  }
}

function progressPercent(job) {
  const p = job.progress
  if (!p) return 0
  const total = p.chapter_total || 0
  const done = p.saved_chapters || 0
  if (!total) return 0
  return Math.min(100, Math.round((done / total) * 100))
}

onMounted(async () => {
  await refreshStatus()
  await load()
})
</script>

<template>
  <div class="stack">
    <!-- 后端不可用提示 -->
    <div v-if="ui.statusError" class="card">
      <div class="card-body row">
        <Icon name="alert" :size="18" style="color: var(--danger)" />
        <div>
          <b>无法连接后端服务</b>
          <div class="text-sm muted">{{ ui.statusError }}</div>
        </div>
        <button class="btn btn-outline spacer" @click="refreshStatus">重试</button>
      </div>
    </div>

    <!-- 运行中任务 -->
    <div v-if="activeJobs.length" class="card">
      <div class="card-head">
        <Icon name="download" :size="15" style="color: var(--primary)" />
        <h2>正在下载</h2>
        <span class="badge badge-primary spacer">{{ activeJobs.length }} 个任务</span>
        <RouterLink class="btn btn-sm btn-outline" :to="{ name: 'jobs' }">查看详情</RouterLink>
      </div>
      <div class="card-body stack">
        <div v-for="job in activeJobs" :key="job.id" class="stack-sm">
          <div class="row">
            <b class="truncate">{{ job.title || job.book_id }}</b>
            <span class="badge badge-info spacer">{{
              job.state === 'queued' ? '排队中' : '下载中'
            }}</span>
          </div>
          <div class="progress">
            <div
              class="progress-bar"
              :class="{ indeterminate: job.state === 'queued' }"
              :style="{ width: progressPercent(job) + '%' }"
            />
          </div>
          <div class="text-xs muted">
            <template v-if="job.progress">
              已保存 {{ job.progress.saved_chapters }} / {{ job.progress.chapter_total }} 章
              · 分组 {{ job.progress.group_done }}/{{ job.progress.group_total }}
            </template>
            <template v-else>等待后端准备…</template>
          </div>
        </div>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-grid">
      <div class="stat">
        <div class="stat-label"><Icon name="server" :size="13" />后端版本</div>
        <div class="stat-value sm">{{ s?.version || '—' }}</div>
        <div class="stat-hint">
          {{ s?.docker_build ? 'Docker 构建' : '本地构建' }} ·
          {{ s?.locked ? '已开启密码锁' : '未开启密码锁' }}
        </div>
      </div>

      <div class="stat">
        <div class="stat-label"><Icon name="clock" :size="13" />当前任务</div>
        <div class="stat-value">{{ activeJobs.length }}</div>
        <div class="stat-hint">历史记录 {{ history.length }} 本</div>
      </div>

      <div class="stat">
        <div class="stat-label"><Icon name="book" :size="13" />累计成功章节</div>
        <div class="stat-value">{{ totalChapters.toLocaleString() }}</div>
        <div class="stat-hint">基于最近 100 条历史</div>
      </div>

      <div class="stat">
        <div class="stat-label"><Icon name="dashboard" :size="13" />监听地址</div>
        <div class="stat-value sm mono">{{ s?.bind_addr || '—' }}</div>
        <div class="stat-hint">容器内监听端口</div>
      </div>
    </div>

    <!-- 服务详情 -->
    <div class="card">
      <div class="card-head">
        <Icon name="server" :size="15" />
        <h2>服务信息</h2>
        <span v-if="ui.lastUpdated" class="text-xs muted spacer">
          更新于 {{ formatRelative(ui.lastUpdated) }}
        </span>
      </div>
      <div class="card-body">
        <table class="kv">
          <tbody>
            <tr>
              <td style="width: 160px" class="muted">保存目录</td>
              <td class="mono">{{ s?.save_dir || '—' }}</td>
            </tr>
            <tr>
              <td class="muted">监听地址</td>
              <td class="mono">{{ s?.bind_addrs?.join(', ') || s?.bind_addr || '—' }}</td>
            </tr>
            <tr>
              <td class="muted">正文接口模式</td>
              <td>
                <span class="badge" :class="s?.config?.use_official_api ? 'badge-success' : 'badge-warning'">
                  {{ s?.config?.use_official_api ? '官方 API' : '第三方 API' }}
                </span>
              </td>
            </tr>
            <tr>
              <td class="muted">第三方接口池</td>
              <td>
                <span class="badge" :class="s?.config?.api_endpoints_len ? 'badge-info' : ''">
                  {{ s?.config?.api_endpoints_len || 0 }} 个地址
                </span>
              </td>
            </tr>
            <tr>
              <td class="muted">预热状态</td>
              <td>
                <span v-if="s?.prewarm_in_progress" class="badge badge-warning">
                  <span class="dot dot-pulse" />进行中
                </span>
                <span v-else-if="s?.prewarm_error" class="badge badge-danger">
                  {{ s.prewarm_error }}
                </span>
                <span v-else class="badge badge-success">正常</span>
              </td>
            </tr>
            <tr>
              <td class="muted">自定义保存路径</td>
              <td class="mono">{{ s?.config?.save_path || '（使用默认）' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 最近历史 -->
    <div class="card">
      <div class="card-head">
        <Icon name="history" :size="15" />
        <h2>最近完成</h2>
        <RouterLink class="btn btn-sm btn-outline spacer" :to="{ name: 'history' }">
          全部历史
        </RouterLink>
      </div>
      <div v-if="recentJobs.length || history.length" class="table-wrap">
        <table class="responsive">
          <thead>
            <tr>
              <th>书名</th>
              <th>状态</th>
              <th>进度</th>
              <th>时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="h in history.slice(0, 5)" :key="h.book_id + h.timestamp">
              <td data-label="">
                <div class="truncate" style="max-width: 320px">{{ h.book_name }}</div>
                <div class="text-xs muted">{{ h.author }}</div>
              </td>
              <td data-label="状态">
                <span class="badge" :class="h.status === 'success' ? 'badge-success' : 'badge-danger'">
                  {{ h.status === 'success' ? '成功' : h.status }}
                </span>
              </td>
              <td data-label="进度" class="text-sm">{{ h.progress }}</td>
              <td data-label="时间" class="text-sm muted">{{ formatRelative(new Date(h.timestamp).getTime()) }}</td>
            </tr>
            <tr v-if="!history.length">
              <td colspan="4" class="muted center">暂无下载历史</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty">
        <div class="empty-icon"><Icon name="books" :size="34" /></div>
        <div>还没有下载记录</div>
        <RouterLink class="btn btn-primary mt-16" :to="{ name: 'search' }">
          <Icon name="search" :size="14" />去搜索书籍
        </RouterLink>
      </div>
    </div>
  </div>
</template>
