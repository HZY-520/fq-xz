<script setup>
import { computed, onMounted, ref } from 'vue'
import Icon from '../components/Icon.vue'
import { api } from '../api.js'
import { toastOk, toastErr, toastWarn, formatRelative, formatBytes } from '../store.js'

const items = ref([])
const loading = ref(true)
const keyword = ref('')
const limit = ref(100)

let searchTimer = null

// 删除确认弹窗
const delDialog = ref(null)
const deleting = ref(false)

async function askDelete(h) {
  // 默认行为：删除「这条历史记录」；若服务器上还有文件，可勾选一并删除
  delDialog.value = {
    book: h,
    targets: [],
    totalSize: 0,
    loading: true,
    error: '',
    alsoFiles: false,
    hasFiles: false,
    // 该书记录数（同书多次下载会有多条）
    sameBookCount: items.value.filter((x) => x.book_id === h.book_id).length,
  }
  try {
    // 预演文件清理范围；文件已不存在时后端会 404，这里视为"没有文件"而非错误
    const plan = await api.deletePreview({ bookId: h.book_id })
    delDialog.value.targets = plan.would_delete || []
    delDialog.value.totalSize = plan.total_size || 0
    delDialog.value.hasFiles = (plan.would_delete || []).length > 0
  } catch (err) {
    if (err.status === 404) {
      delDialog.value.hasFiles = false
    } else {
      delDialog.value.error = err.message
    }
  } finally {
    delDialog.value.loading = false
  }
}

async function doDelete() {
  const d = delDialog.value
  if (!d) return
  deleting.value = true
  const book = d.book
  const msgs = []

  try {
    // 1) 删除历史记录（按 book_id + timestamp 精确定位这一条）
    if (d.deleteRecord !== false) {
      try {
        const r = await api.deleteHistory({
          bookId: book.book_id,
          timestamp: book.timestamp,
        })
        msgs.push(`已删除 ${r.deleted_count || 1} 条历史记录`)
      } catch (err) {
        // 记录不存在不算致命错误
        if (err.status !== 404) throw err
        msgs.push('历史记录已不存在')
      }
    }

    // 2) 可选：一并删除服务器上的文件
    if (d.alsoFiles) {
      try {
        const res = await api.deleteBook({ bookId: book.book_id })
        msgs.push(`已清理 ${(res.deleted || []).length} 项文件（${formatBytes(res.total_size || 0)}）`)
      } catch (err) {
        if (err.status === 409) {
          toastWarn('文件删除被跳过：该书正在下载中')
        } else if (err.status === 404) {
          // 没有文件，忽略
        } else {
          toastWarn(`文件删除失败：${err.message}`)
        }
      }
    }

    toastOk(msgs.join('；') || '操作完成')
    delDialog.value = null
    await load()
  } catch (err) {
    toastErr(`删除失败：${err.message}`)
  } finally {
    deleting.value = false
  }
}

const stats = computed(() => {
  const total = items.value.length
  const success = items.value.filter((h) => h.status === 'success').length
  const chapters = items.value.reduce((s, h) => s + (h.success_chapters || 0), 0)
  const failed = items.value.reduce((s, h) => s + (h.failed_chapters || 0), 0)
  return { total, success, chapters, failed }
})

async function load() {
  loading.value = true
  try {
    const data = await api.history({ limit: limit.value, q: keyword.value.trim() || undefined })
    items.value = data.items || []
  } catch (err) {
    toastErr(`加载历史失败：${err.message}`)
  } finally {
    loading.value = false
  }
}

function onKeywordInput() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(load, 350)
}

function exportJson() {
  const blob = new Blob([JSON.stringify(items.value, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `tomato-history-${new Date().toISOString().slice(0, 10)}.json`
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(load)
</script>

<template>
  <div class="stack">
    <div class="stat-grid">
      <div class="stat">
        <div class="stat-label"><Icon name="book" :size="13" />记录数</div>
        <div class="stat-value">{{ stats.total }}</div>
      </div>
      <div class="stat">
        <div class="stat-label"><Icon name="check" :size="13" />成功</div>
        <div class="stat-value" style="color: var(--success)">{{ stats.success }}</div>
      </div>
      <div class="stat">
        <div class="stat-label"><Icon name="download" :size="13" />累计章节</div>
        <div class="stat-value">{{ stats.chapters.toLocaleString() }}</div>
      </div>
      <div class="stat">
        <div class="stat-label"><Icon name="alert" :size="13" />失败章节</div>
        <div class="stat-value" :style="{ color: stats.failed ? 'var(--danger)' : undefined }">
          {{ stats.failed }}
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <Icon name="history" :size="15" />
        <h2>下载历史</h2>
        <div class="row spacer">
          <div style="width: 190px">
            <input
              v-model="keyword"
              class="input"
              style="padding: 6px 10px; font-size: 13px"
              type="search"
              placeholder="按书名筛选"
              @input="onKeywordInput"
            />
          </div>
          <select v-model.number="limit" class="select" style="width: 100px; padding: 6px 8px" @change="load">
            <option :value="50">50 条</option>
            <option :value="100">100 条</option>
            <option :value="300">300 条</option>
            <option :value="1000">1000 条</option>
          </select>
          <button class="btn btn-outline btn-sm" :disabled="!items.length" @click="exportJson">
            <Icon name="save" :size="13" />导出
          </button>
        </div>
      </div>

      <div v-if="loading" class="card-body stack-sm">
        <div v-for="i in 5" :key="i" class="skeleton" style="height: 18px" />
      </div>

      <div v-else-if="items.length" class="table-wrap">
        <table class="responsive">
          <thead>
            <tr>
              <th style="width: 34%">书名 / 作者</th>
              <th>状态</th>
              <th style="width: 26%">进度</th>
              <th>章节</th>
              <th>时间</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="(h, i) in items" :key="`${h.book_id}-${h.timestamp}-${i}`">
              <td data-label="">
                <div class="truncate" style="max-width: 300px; font-weight: 600">
                  {{ h.book_name || '未知书名' }}
                </div>
                <div class="text-xs muted">{{ h.author || '未知作者' }}</div>
              </td>
              <td data-label="状态">
                <span
                  class="badge"
                  :class="h.status === 'success' ? 'badge-success' : 'badge-danger'"
                >
                  {{ h.status === 'success' ? '成功' : h.status || '异常' }}
                </span>
              </td>
              <td data-label="进度" class="text-sm">{{ h.progress || '—' }}</td>
              <td data-label="章节" class="text-sm">
                <span style="color: var(--success)">{{ h.success_chapters ?? 0 }}</span>
                <template v-if="h.failed_chapters">
                  / <span style="color: var(--danger)">{{ h.failed_chapters }} 失败</span>
                </template>
              </td>
              <td data-label="时间" class="text-sm muted" style="white-space: nowrap">
                {{ formatRelative(new Date(h.timestamp).getTime()) }}
              </td>
              <td data-label="操作">
                <div class="row" style="gap: 6px; justify-content: flex-end">
                  <button
                    class="btn btn-sm btn-danger"
                    title="删除该书及其文件"
                    @click="askDelete(h)"
                  >
                    <Icon name="trash" :size="12" />删除
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else class="empty">
        <div class="empty-icon"><Icon name="inboxOff" :size="34" /></div>
        <div>暂无下载历史</div>
        <RouterLink class="btn btn-primary mt-16" :to="{ name: 'search' }">
          <Icon name="search" :size="14" />去搜索书籍
        </RouterLink>
      </div>
    </div>

    <!-- ═══ 删除确认弹窗 ═══ -->
    <div v-if="delDialog" class="modal-mask" @click.self="!deleting && (delDialog = null)">
      <div class="modal">
        <div class="modal-head" style="color: var(--danger)">
          <Icon name="alert" :size="16" style="margin-right: 8px" />
          删除历史记录
        </div>
        <div class="modal-body">
          <div class="field">
            <span class="field-label">记录内容</span>
            <div class="text-sm" style="font-weight: 600">{{ delDialog.book.book_name }}</div>
            <div class="text-xs muted">
              {{ delDialog.book.author || '未知作者' }} ·
              {{ new Date(delDialog.book.timestamp).toLocaleString('zh-CN') }}
            </div>
          </div>

          <div class="field">
            <span class="field-label">将执行</span>
            <div class="text-sm">删除这条下载历史记录</div>
            <div v-if="delDialog.sameBookCount > 1" class="field-hint">
              注意：这本书共有 {{ delDialog.sameBookCount }} 条记录，本次只删除当前选中的这一条。
            </div>
          </div>

          <div v-if="delDialog.loading" class="stack-sm">
            <span class="field-label">检查服务器文件…</span>
            <div class="skeleton" style="height: 20px" />
          </div>

          <template v-else>
            <label v-if="delDialog.hasFiles" class="switch">
              <input v-model="delDialog.alsoFiles" type="checkbox" />
              <span class="switch-track" />
              <span>同时删除服务器上的文件</span>
            </label>

            <div v-if="delDialog.alsoFiles && delDialog.targets.length" class="field">
              <div class="field-label">
                将一并删除 {{ delDialog.targets.length }} 项
                <span class="muted">（共 {{ formatBytes(delDialog.totalSize) }}）</span>
              </div>
              <div
                class="stack-sm"
                style="
                  max-height: 180px;
                  overflow: auto;
                  border: 1px solid var(--border);
                  border-radius: 8px;
                  padding: 10px;
                  background: var(--surface-2);
                "
              >
                <div v-for="t in delDialog.targets" :key="t.rel_path" class="row text-sm" style="gap: 8px">
                  <Icon
                    :name="t.kind === 'dir' ? 'folder' : 'file'"
                    :size="15"
                    style="flex: none; color: var(--text-3)"
                  />
                  <span class="truncate" style="flex: 1; min-width: 0" :title="t.rel_path">{{ t.name }}</span>
                  <span class="muted text-xs" style="flex: none">{{ formatBytes(t.size) }}</span>
                </div>
              </div>
            </div>

            <p v-else-if="!delDialog.hasFiles" class="field-hint">
              服务器上没有这本书的文件（可能已删除或未下载到本地），本次只清理历史记录。
            </p>

            <p v-if="delDialog.error" class="text-sm" style="color: var(--danger)">
              {{ delDialog.error }}
            </p>
          </template>
        </div>
        <div class="modal-foot">
          <button class="btn btn-ghost" :disabled="deleting" @click="delDialog = null">取消</button>
          <button
            class="btn btn-danger"
            :disabled="deleting || delDialog.loading"
            @click="doDelete"
          >
            <span v-if="deleting" class="spinner" />
            <Icon v-else name="trash" :size="13" />
            {{ deleting ? '删除中…' : '确认删除记录' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
