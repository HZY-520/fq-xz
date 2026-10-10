<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import Icon from '../components/Icon.vue'
import { api } from '../api.js'
import { toastOk, toastErr, toastWarn, formatBytes, formatRelative } from '../store.js'

const path = ref('')
const data = ref(null)
const loading = ref(true)
const nameFilter = ref('')

// 删除确认弹窗
const delDialog = ref(null) // { title, subtitle, targets: [], totalSize, payload, busy }
const deleting = ref(false)

let timer = null

const items = computed(() => data.value?.items || [])
const root = computed(() => data.value?.root || '')
const running = computed(() => data.value?.running === true)
const scanError = computed(() => data.value?.error)

const crumbs = computed(() => {
  const segs = String(path.value).split('/').filter(Boolean)
  const out = [{ label: '根目录', path: '' }]
  let acc = ''
  for (const s of segs) {
    acc = acc ? `${acc}/${s}` : s
    out.push({ label: s, path: acc })
  }
  return out
})

const fileCount = computed(() => items.value.filter((i) => i.kind === 'file').length)
const dirCount = computed(() => items.value.filter((i) => i.kind === 'dir').length)
const totalSize = computed(() =>
  items.value.reduce((s, i) => s + (i.kind === 'file' ? i.size || 0 : 0), 0),
)

/**
 * 把根目录条目按"书"聚合：
 *   <书名>.epub / .txt / .mp3  +  <书名>_audio/  +  <书ID>/ 缓存目录
 * 归为同一本书，便于一次性删除整本书。
 */
const books = computed(() => {
  if (path.value) return []

  const map = new Map()
  const touch = (name) => {
    if (!map.has(name)) {
      map.set(name, { name, files: [], audioDirs: [], cacheDirs: [], size: 0, modified: 0 })
    }
    return map.get(name)
  }

  for (const it of items.value) {
    if (it.kind === 'file') {
      const ext = (it.ext || '').toLowerCase()
      const name = it.name.replace(/\.[^.]+$/, '')
      const b = touch(name)
      b.files.push(it)
      b.size += it.size || 0
      b.modified = Math.max(b.modified, it.modified_ms || 0)
    } else if (it.kind === 'dir') {
      if (it.name.endsWith('_audio')) {
        const b = touch(it.name.slice(0, -'_audio'.length))
        b.audioDirs.push(it)
        b.modified = Math.max(b.modified, it.modified_ms || 0)
      } else if (/^\d+$/.test(it.name)) {
        // 纯数字目录 = 书籍缓存目录，无法反查书名，单独列出
        const b = touch(it.name)
        b.cacheDirs.push(it)
        b.isCacheOnly = true
        b.modified = Math.max(b.modified, it.modified_ms || 0)
      } else {
        const b = touch(it.name)
        b.extraDirs = b.extraDirs || []
        b.extraDirs.push(it)
        b.modified = Math.max(b.modified, it.modified_ms || 0)
      }
    }
  }

  // 过滤：只显示有可下载产物 或 有音频 或 有缓存目录的条目
  return [...map.values()]
    .filter((b) => b.files.length || b.audioDirs.length || b.cacheDirs.length || b.extraDirs?.length)
    .map((b) => ({
      ...b,
      formats: [...new Set(b.files.map((f) => (f.ext || '').toLowerCase()))],
      // 可用于「浏览」的路径：缓存目录 > 音频目录 > 首个文件所在目录
      browsablePath: b.cacheDirs[0]
        ? { rel_path: b.cacheDirs[0].rel_path }
        : b.audioDirs[0]
          ? { rel_path: b.audioDirs[0].rel_path }
          : b.files[0]
            ? { rel_path: b.files[0].rel_path }
            : null,
    }))
    .sort((a, b) => (b.modified || 0) - (a.modified || 0))
})

async function load(start = true) {
  try {
    const d = await api.library({
      path: path.value || undefined,
      name: nameFilter.value.trim() || undefined,
      start: start ? 'true' : 'false',
    })
    data.value = d
  } catch (err) {
    toastErr(`读取书库失败：${err.message}`)
  } finally {
    loading.value = false
  }
}

function openDir(item) {
  path.value = item.rel_path
  nameFilter.value = ''
  loading.value = true
  load()
}

function goto(p) {
  path.value = p
  nameFilter.value = ''
  loading.value = true
  load()
}

function extIcon(item) {
  if (item.kind === 'dir') return 'folder'
  switch ((item.ext || '').toLowerCase()) {
    case 'epub':
      return 'bookOpen'
    case 'txt':
      return 'file'
    case 'mp3':
    case 'wav':
      return 'music'
    default:
      return 'package'
  }
}

/* ── 删除流程 ──────────────────────────────────────────────────────── */

async function confirmDelete(opts) {
  // opts: { title, subtitle, payload }
  delDialog.value = { ...opts, targets: [], totalSize: 0, loading: true }
  try {
    const plan = await api.deletePreview(opts.payload)
    delDialog.value.targets = plan.would_delete || []
    delDialog.value.totalSize = plan.total_size || 0
  } catch (err) {
    delDialog.value.targets = []
    delDialog.value.error = err.message
  } finally {
    delDialog.value.loading = false
  }
}

/** 删除整本书（文件 + 音频目录 + 缓存目录） */
function askDeleteBook(book) {
  confirmDelete({
    title: `删除《${book.name}》`,
    subtitle: '将同时删除该书的所有下载文件、有声小说与缓存目录',
    payload: { bookId: /^\d+$/.test(book.name) ? book.name : undefined, path: book.name },
    kind: 'book',
  })
}

/** 删除单个文件（含同名音频/缓存） */
function askDeleteFile(item) {
  confirmDelete({
    title: `删除「${item.name}」`,
    subtitle: '将同时删除同名的有声小说目录与缓存目录',
    payload: { path: item.rel_path },
    kind: 'file',
  })
}

/** 只删缓存目录 */
function askDeleteCache(book) {
  confirmDelete({
    title: `删除缓存目录「${book.name}」`,
    subtitle: '仅清理下载缓存，保留已输出的书文件',
    payload: { path: book.cacheDirs[0]?.rel_path || book.name },
    kind: 'cache',
  })
}

async function doDelete() {
  const d = delDialog.value
  if (!d) return
  deleting.value = true
  try {
    const res = await api.deleteBook(d.payload)
    const n = (res.deleted || []).length
    toastOk(`删除完成，共清理 ${n} 项（${formatBytes(res.total_size || 0)}）`)
    if (res.failed?.length) {
      toastWarn(`有 ${res.failed.length} 项删除失败，请查看服务日志`)
    }
    delDialog.value = null
    loading.value = true
    await load()
  } catch (err) {
    if (err.status === 409) {
      toastWarn(err.message || '该书正在下载中，请先取消任务')
    } else if (err.status === 404) {
      toastWarn('目标文件已不存在，已刷新列表')
      delDialog.value = null
      await load()
    } else if (err.status === 401) {
      toastErr('删除服务认证失败')
    } else {
      toastErr(`删除失败：${err.message}`)
    }
  } finally {
    deleting.value = false
  }
}

let filterTimer = null
function onFilterInput() {
  clearTimeout(filterTimer)
  filterTimer = setTimeout(() => load(false), 300)
}

onMounted(() => {
  load()
  timer = setInterval(() => {
    if (running.value) load(false)
  }, 1500)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="stack">
    <!-- 面包屑与操作 -->
    <div class="row row-wrap">
      <div class="crumbs">
        <template v-for="(c, i) in crumbs" :key="c.path">
          <span v-if="i" class="muted">/</span>
          <button
            class="crumb"
            :class="{ current: i === crumbs.length - 1 }"
            @click="i !== crumbs.length - 1 && goto(c.path)"
          >
            {{ c.label }}
          </button>
        </template>
      </div>
      <div class="spacer" />
      <span v-if="running" class="badge badge-info"><span class="dot dot-pulse" />扫描中</span>
      <button class="btn btn-outline btn-sm" :disabled="running" @click="load()">
        <Icon name="refresh" :size="13" />重新扫描
      </button>
    </div>

    <!-- 统计 -->
    <div class="stat-grid">
      <div class="stat">
        <div class="stat-label"><Icon name="book" :size="13" />{{ path ? '目录' : '书籍' }}</div>
        <div class="stat-value">{{ path ? dirCount : books.length }}</div>
      </div>
      <div class="stat">
        <div class="stat-label"><Icon name="file" :size="13" />文件</div>
        <div class="stat-value">{{ fileCount }}</div>
      </div>
      <div class="stat">
        <div class="stat-label"><Icon name="archive" :size="13" />当前层大小</div>
        <div class="stat-value sm">{{ formatBytes(totalSize) }}</div>
      </div>
      <div class="stat">
        <div class="stat-label"><Icon name="server" :size="13" />后端根目录</div>
        <div class="stat-value sm mono truncate" :title="root">{{ root || '—' }}</div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <Icon name="library" :size="15" />
        <h2>{{ path ? '文件列表' : '我的书籍' }}</h2>
        <div class="spacer" style="width: 190px; max-width: 45vw">
          <input
            v-model="nameFilter"
            class="input"
            style="padding: 6px 10px; font-size: 13px"
            type="search"
            placeholder="筛选名称"
            @input="onFilterInput"
          />
        </div>
      </div>

      <div v-if="scanError" class="card-body">
        <div class="row text-sm" style="color: var(--danger)">
          <Icon name="alert" :size="15" />扫描出错：{{ scanError }}
        </div>
      </div>

      <!-- 加载骨架 -->
      <div v-if="loading && !items.length" class="card-body stack-sm">
        <div v-for="i in 4" :key="i" class="skeleton" style="height: 54px" />
      </div>

      <!-- ═══ 根目录：按书分组 ═══ -->
      <template v-else-if="!path && books.length">
        <!-- 桌面端：表格 -->
        <div class="table-wrap desktop-only">
          <table class="responsive">
            <thead>
              <tr>
                <th>书名</th>
                <th style="width: 130px">格式</th>
                <th style="width: 110px">大小</th>
                <th style="width: 130px">修改时间</th>
                <th style="width: 220px" />
              </tr>
            </thead>
            <tbody>
              <tr v-for="b in books" :key="b.name">
                <td data-label="">
                  <div class="row" style="gap: 8px; min-width: 0">
                    <Icon :name="b.isCacheOnly ? 'package' : 'bookOpen'" :size="16" style="flex: none; color: var(--text-3)" />
                    <div style="min-width: 0">
                      <div class="truncate" style="font-weight: 600" :title="b.name">
                        {{ b.isCacheOnly ? `缓存目录 ${b.name}` : b.name }}
                      </div>
                      <div class="text-xs muted">
                        <template v-if="b.files.length">{{ b.files.length }} 个文件</template>
                        <template v-if="b.audioDirs.length"> · 含有声小说</template>
                        <template v-if="b.cacheDirs.length"> · 含缓存</template>
                      </div>
                    </div>
                  </div>
                </td>
                <td data-label="格式">
                  <span v-for="f in b.formats" :key="f" class="badge badge-primary" style="margin-right: 4px">
                    {{ f }}
                  </span>
                  <span v-if="b.audioDirs.length" class="badge badge-info">audio</span>
                  <span v-if="!b.formats.length && !b.audioDirs.length" class="muted text-sm">仅缓存</span>
                </td>
                <td data-label="大小" class="text-sm muted">
                  {{ b.size ? formatBytes(b.size) : '—' }}
                </td>
                <td data-label="时间" class="text-sm muted" style="white-space: nowrap">
                  {{ b.modified ? formatRelative(b.modified) : '—' }}
                </td>
                <td data-label="操作">
                  <div class="row" style="gap: 6px; justify-content: flex-end">
                    <a
                      v-if="b.files.length"
                      class="btn btn-sm btn-primary"
                      :href="api.fileUrl(b.files[0].rel_path)"
                      :download="b.files[0].name"
                    >
                      <Icon name="download" :size="12" />下载
                    </a>
                    <button
                      v-if="b.browsablePath"
                      class="btn btn-sm btn-outline"
                      @click="openDir(b.browsablePath)"
                    >
                      <Icon name="folder" :size="12" />浏览
                    </button>
                    <button class="btn btn-sm btn-danger" @click="askDeleteBook(b)">
                      <Icon name="trash" :size="12" />删除
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 移动端：卡片 -->
        <div class="card-list mobile-only">
          <div v-for="b in books" :key="b.name" class="m-item">
            <div class="m-item-head">
              <Icon :name="b.isCacheOnly ? 'package' : 'bookOpen'" :size="20" class="m-item-icon" />
              <div style="min-width: 0; flex: 1">
                <div class="m-item-title">
                  {{ b.isCacheOnly ? `缓存目录 ${b.name}` : b.name }}
                </div>
                <div class="m-item-meta">
                  <span v-for="f in b.formats" :key="f" class="badge badge-primary">{{ f }}</span>
                  <span v-if="b.audioDirs.length" class="badge badge-info">audio</span>
                  <span v-if="b.size">{{ formatBytes(b.size) }}</span>
                  <span v-if="b.modified">{{ formatRelative(b.modified) }}</span>
                </div>
              </div>
            </div>
            <div class="m-item-actions">
              <a
                v-if="b.files.length"
                class="btn btn-sm btn-primary"
                :href="api.fileUrl(b.files[0].rel_path)"
                :download="b.files[0].name"
              >
                <Icon name="download" :size="12" />下载
              </a>
              <button
                v-if="b.browsablePath"
                class="btn btn-sm btn-outline"
                @click="openDir(b.browsablePath)"
              >
                <Icon name="folder" :size="12" />浏览
              </button>
              <button class="btn btn-sm btn-danger" @click="askDeleteBook(b)">
                <Icon name="trash" :size="12" />删除
              </button>
            </div>
          </div>
        </div>
      </template>

      <!-- ═══ 子目录：文件列表 ═══ -->
      <template v-else-if="items.length">
        <div class="table-wrap desktop-only">
          <table class="responsive">
            <thead>
              <tr>
                <th>名称</th>
                <th style="width: 110px">大小</th>
                <th style="width: 130px">修改时间</th>
                <th style="width: 250px" />
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in items" :key="item.rel_path">
                <td data-label="">
                  <div class="row" style="gap: 8px; min-width: 0">
                    <Icon :name="extIcon(item)" :size="16" style="flex: none; color: var(--text-3)" />
                    <span
                      class="truncate"
                      style="font-weight: 600"
                      :title="item.name"
                      :style="{ cursor: item.kind === 'dir' ? 'pointer' : 'default' }"
                      @click="item.kind === 'dir' ? openDir(item) : null"
                    >
                      {{ item.name }}
                    </span>
                  </div>
                </td>
                <td data-label="大小" class="text-sm muted">
                  {{ item.kind === 'file' ? formatBytes(item.size) : '—' }}
                </td>
                <td data-label="时间" class="text-sm muted" style="white-space: nowrap">
                  {{ item.modified_ms ? formatRelative(item.modified_ms) : '—' }}
                </td>
                <td data-label="操作">
                  <div class="row" style="gap: 6px; justify-content: flex-end">
                    <button
                      v-if="item.kind === 'dir'"
                      class="btn btn-sm btn-outline"
                      @click="openDir(item)"
                    >
                      <Icon name="folder" :size="12" />打开
                    </button>
                    <a
                      v-if="item.kind === 'file'"
                      class="btn btn-sm btn-primary"
                      :href="api.fileUrl(item.rel_path)"
                      :download="item.name"
                    >
                      <Icon name="download" :size="12" />下载
                    </a>
                    <a class="btn btn-sm btn-outline" :href="api.zipUrl(item.rel_path)" title="打包 zip 下载">
                      <Icon name="archive" :size="12" />
                    </a>
                    <button
                      class="btn btn-sm btn-danger"
                      :title="item.kind === 'dir' ? '删除该目录' : '删除该文件'"
                      @click="askDeleteFile(item)"
                    >
                      <Icon name="trash" :size="12" />
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="card-list mobile-only">
          <div v-for="item in items" :key="item.rel_path" class="m-item">
            <div class="m-item-head">
              <Icon :name="extIcon(item)" :size="20" class="m-item-icon" />
              <div style="min-width: 0; flex: 1">
                <div class="m-item-title">{{ item.name }}</div>
                <div class="m-item-meta">
                  <span>{{ item.kind === 'file' ? formatBytes(item.size) : '目录' }}</span>
                  <span v-if="item.modified_ms">{{ formatRelative(item.modified_ms) }}</span>
                </div>
              </div>
            </div>
            <div class="m-item-actions">
              <button v-if="item.kind === 'dir'" class="btn btn-sm btn-outline" @click="openDir(item)">
                <Icon name="folder" :size="12" />打开
              </button>
              <a
                v-if="item.kind === 'file'"
                class="btn btn-sm btn-primary"
                :href="api.fileUrl(item.rel_path)"
                :download="item.name"
              >
                <Icon name="download" :size="12" />下载
              </a>
              <a class="btn btn-sm btn-outline" :href="api.zipUrl(item.rel_path)">
                <Icon name="archive" :size="12" />zip
              </a>
              <button class="btn btn-sm btn-danger" @click="askDeleteFile(item)">
                <Icon name="trash" :size="12" />删除
              </button>
            </div>
          </div>
        </div>
      </template>

      <!-- 空态 -->
      <div v-else class="empty">
        <div class="empty-icon"><Icon name="folderOpen" :size="34" /></div>
        <div>{{ path ? '当前目录为空' : '还没有下载任何书籍' }}</div>
        <p class="text-xs muted mt-8">
          下载完成的小说会出现在这里；以书籍 ID 命名的缓存目录会被自动归入对应书籍。
        </p>
        <RouterLink v-if="!path" class="btn btn-primary mt-16" :to="{ name: 'search' }">
          <Icon name="search" :size="14" />去搜索书籍
        </RouterLink>
      </div>
    </div>

    <!-- ═══ 删除确认弹窗 ═══ -->
    <div v-if="delDialog" class="modal-mask" @click.self="!deleting && (delDialog = null)">
      <div class="modal">
        <div class="modal-head" style="color: var(--danger)">
          <Icon name="alert" :size="16" style="margin-right: 8px" />
          {{ delDialog.title }}
        </div>
        <div class="modal-body">
          <p class="text-sm muted">{{ delDialog.subtitle }}</p>

          <div v-if="delDialog.loading" class="stack-sm">
            <div class="skeleton" style="height: 20px" />
            <div class="skeleton" style="height: 20px" />
          </div>

          <template v-else-if="delDialog.targets.length">
            <div>
              <div class="field-label" style="margin-bottom: 6px">
                将删除以下 {{ delDialog.targets.length }} 项
                <span class="muted">（共 {{ formatBytes(delDialog.totalSize) }}）</span>
              </div>
              <div
                class="stack-sm"
                style="
                  max-height: 210px;
                  overflow: auto;
                  border: 1px solid var(--border);
                  border-radius: 8px;
                  padding: 10px;
                  background: var(--surface-2);
                "
              >
                <div v-for="t in delDialog.targets" :key="t.rel_path" class="row text-sm" style="gap: 8px">
                  <Icon :name="t.kind === 'dir' ? 'folder' : 'file'" :size="15" style="flex: none; color: var(--text-3)" />
                  <span class="truncate" style="flex: 1; min-width: 0" :title="t.rel_path">
                    {{ t.name }}
                  </span>
                  <span class="muted text-xs" style="flex: none">{{ formatBytes(t.size) }}</span>
                </div>
              </div>
            </div>
            <p class="field-hint" style="color: var(--danger)">此操作不可恢复。</p>
          </template>

          <p v-else-if="delDialog.error" class="text-sm" style="color: var(--danger)">
            {{ delDialog.error }}
          </p>
          <p v-else class="text-sm muted">没有找到可删除的文件。</p>
        </div>
        <div class="modal-foot">
          <button class="btn btn-ghost" :disabled="deleting" @click="delDialog = null">取消</button>
          <button
            class="btn btn-danger"
            :disabled="deleting || delDialog.loading || !delDialog.targets.length"
            @click="doDelete"
          >
            <span v-if="deleting" class="spinner" />
            <Icon v-else name="trash" :size="13" />
            {{ deleting ? '删除中…' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
