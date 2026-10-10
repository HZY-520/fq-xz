<script setup>
import { computed, ref } from 'vue'
import Icon from '../components/Icon.vue'
import { api } from '../api.js'
import { toastOk, toastErr, toastWarn, ui, refreshActiveJobs } from '../store.js'

const keyword = ref('')
const loading = ref(false)
const searched = ref(false)
const error = ref('')
const items = ref([])

// 直接识别「书号」或「分享链接」的结果
const direct = ref(null) // { book_id, title, author, ... }
const directLoading = ref(false)
const coverFailed = ref({}) // book_id -> true，封面加载失败

/**
 * 从输入中提取书籍 ID：
 *   - 纯数字         7137526579449564198
 *   - 番茄链接       https://fanqienovel.com/page/7137...
 *   - 分享文本       复制来的带链接文本
 * 识别不到则返回 null（按关键词搜索处理）
 */
function extractBookId(input) {
  const s = String(input || '').trim()
  if (!s) return null
  if (/^\d{6,}$/.test(s)) return s
  // 链接形式：/page/<id>、book_id=<id>、item_id=<id> 等
  const patterns = [
    /fanqienovel\.com\/page\/(\d{6,})/i,
    /[?&](?:book_id|bookId|item_id|id)=(\d{6,})/,
    /\/(\d{15,})(?:[/?#]|$)/,
  ]
  for (const re of patterns) {
    const m = s.match(re)
    if (m) return m[1]
  }
  // 兜底：文本里若有超长数字串，取最长的一个
  const nums = s.match(/\d{15,}/g)
  if (nums?.length) return nums.sort((a, b) => b.length - a.length)[0]
  return null
}

async function loadDirect(bookId) {
  directLoading.value = true
  direct.value = null
  error.value = ''
  searched.value = true
  try {
    const d = await api.preview(bookId)
    direct.value = d
  } catch (err) {
    error.value =
      err.status === 404
        ? `未找到书号为 ${bookId} 的书籍，请确认书号是否正确`
        : err.message
  } finally {
    directLoading.value = false
  }
}

/** 统一取封面：优先官方 CDN，失败则回退到后端封面接口 */
function coverOf(book) {
  const id = book.book_id || book.id
  if (id && coverFailed.value[id]) return `/api/preview-cover-by-book/${id}`
  const raw = book.raw || {}
  return (
    raw.thumb_url ||
    raw.detail_page_thumb_url ||
    raw.expand_thumb_url ||
    (id ? `/api/preview-cover-by-book/${id}` : '')
  )
}

function onCoverError(book) {
  const id = book.book_id || book.id
  if (!id || coverFailed.value[id]) return
  coverFailed.value = { ...coverFailed.value, [id]: true }
}

/** 预览数据也没有 raw，统一做字段兼容 */
function directAsBook(d) {
  return {
    book_id: d.book_id,
    title: d.book_name,
    author: d.author,
    raw: {
      abstract: d.description,
      tags: (d.tags || []).join(','),
      category: d.category,
      read_count_text: d.read_count_text,
      score: d.score,
      serial_count: d.chapter_count,
      word_number: d.word_count,
      creation_status: d.finished ? 0 : 1,
      last_chapter_title: d.last_chapter_title,
    },
  }
}

// 下载弹窗
const dialog = ref(null) // { book }
const rangeEnabled = ref(false)
const rangeStart = ref('')
const rangeEnd = ref('')
const submitting = ref(false)

const canSubmit = computed(() => ui.activeJobs < 1)

function abstractOf(book) {
  const raw = book.raw || {}
  return raw.abstract || raw.book_abstract_v2 || ''
}

function tagsOf(book) {
  const raw = book.raw || {}
  const t = raw.tags || raw.pure_category_tags || ''
  return String(t)
    .split(',')
    .map((x) => x.trim())
    .filter(Boolean)
}

function readCountOf(book) {
  return (book.raw || {}).read_cnt_text || (book.raw || {}).sub_info || ''
}

function scoreOf(book) {
  const v = (book.raw || {}).score
  return v ? Number(v).toFixed(1) : ''
}

function chaptersOf(book) {
  return (book.raw || {}).serial_count || ''
}

function wordsOf(book) {
  const n = (book.raw || {}).word_number
  if (!n) return ''
  return n >= 10000 ? `${(n / 10000).toFixed(1)} 万字` : `${n} 字`
}

function finishedOf(book) {
  // creation_status: 0 完结 / 1 连载（番茄接口惯例，展示时容错）
  const raw = book.raw || {}
  const st = raw.creation_status
  if (st === 0) return { text: '已完结', cls: 'badge-success' }
  if (st === 1) return { text: '连载中', cls: 'badge-warning' }
  return null
}

async function doSearch() {
  const q = keyword.value.trim()
  if (!q) return

  // 书号 / 链接 → 直接预览，不走关键词搜索
  const bid = extractBookId(q)
  if (bid) {
    direct.value = null
    items.value = []
    await loadDirect(bid)
    return
  }

  direct.value = null
  loading.value = true
  error.value = ''
  searched.value = true
  try {
    const data = await api.search(q)
    items.value = data.items || []
    if (data.error) error.value = data.error
  } catch (err) {
    error.value = err.message
    items.value = []
  } finally {
    loading.value = false
  }
}

function openDialog(book) {
  dialog.value = { book }
  rangeEnabled.value = false
  rangeStart.value = ''
  rangeEnd.value = ''
}

function closeDialog() {
  dialog.value = null
}

async function confirmDownload() {
  const book = dialog.value?.book
  if (!book) return

  let start = null
  let end = null
  if (rangeEnabled.value) {
    start = Number(rangeStart.value)
    end = Number(rangeEnd.value)
    if (!start || !end || start < 1 || end < start) {
      toastWarn('章节范围无效：起始需 ≥1 且不大于结束章节')
      return
    }
  }

  submitting.value = true
  try {
    await api.createJob(book.book_id, start, end)
    toastOk(`已创建任务：${book.title || book.book_id}`)
    closeDialog()
    await refreshActiveJobs()
  } catch (err) {
    if (err.status === 429) {
      toastWarn('同时只允许 1 个下载任务，请等待当前任务完成')
    } else if (err.status === 400) {
      toastErr('书籍 ID 无法识别，请换一本试试')
    } else {
      toastErr(`创建任务失败：${err.message}`)
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="stack">
    <!-- 搜索区 -->
    <div class="card">
      <div class="card-body">
        <div class="row row-wrap">
          <div style="flex: 1; min-width: 240px">
            <input
              v-model="keyword"
              class="input"
              type="search"
              placeholder="书名 / 作者，或直接粘贴书号、分享链接"
              @keyup.enter="doSearch"
            />
          </div>
          <button class="btn btn-primary" :disabled="loading || !keyword.trim()" @click="doSearch">
            <span v-if="loading" class="spinner" />
            <Icon v-else name="search" :size="15" />
            {{ loading ? '搜索中…' : '搜索' }}
          </button>
        </div>
        <p class="field-hint mt-8">
          支持三种输入：<b>书名/作者</b>（关键词搜索）、<b>纯数字书号</b>（如 7137526579449564198）、
          <b>番茄分享链接</b>（如 fanqienovel.com/page/&lt;书号&gt;）——后两者会直接定位到该书，不受搜索接口影响。
        </p>
      </div>
    </div>

    <!-- 错误 -->
    <div v-if="error" class="card">
      <div class="card-body row">
        <Icon name="alert" :size="17" style="color: var(--danger); flex: none" />
        <div style="min-width: 0">
          <b>搜索失败</b>
          <div class="text-sm muted" style="word-break: break-word">{{ error }}</div>
        </div>
      </div>
    </div>

    <!-- ═══ 直接书号/链接结果 ═══ -->
    <div v-if="directLoading" class="book-grid">
      <div class="book-card">
        <div class="book-cover skeleton" />
        <div class="book-main stack-sm">
          <div class="skeleton" style="width: 70%" />
          <div class="skeleton" style="width: 40%" />
          <div class="skeleton" style="width: 90%" />
        </div>
      </div>
    </div>

    <div v-else-if="direct" class="book-grid">
      <article class="book-card">
        <img
          v-if="coverOf(directAsBook(direct))"
          class="book-cover"
          :src="coverOf(directAsBook(direct))"
          :alt="direct.book_name"
          loading="lazy"
          referrerpolicy="no-referrer"
          @error="onCoverError(directAsBook(direct))"
        />
        <div v-else class="book-cover center" style="display: grid; place-items: center"><Icon name="image" :size="22" /></div>

        <div class="book-main">
          <div class="book-title">{{ direct.book_name || '未知书名' }}</div>
          <div class="book-meta">
            <span>{{ direct.author || '未知作者' }}</span>
            <span v-if="direct.read_count_text">{{ direct.read_count_text }}</span>
            <span v-if="direct.score">评分 {{ Number(direct.score).toFixed(1) }}</span>
          </div>
          <div class="row row-wrap" style="gap: 6px">
            <span v-if="direct.category" class="badge">{{ direct.category }}</span>
            <span class="badge" :class="direct.finished ? 'badge-success' : 'badge-warning'">
              {{ direct.finished ? '已完结' : '连载中' }}
            </span>
            <span v-for="t in direct.tags || []" :key="t" class="badge">{{ t }}</span>
          </div>
          <p class="book-abstract">{{ direct.description }}</p>
          <div class="book-meta">
            <span v-if="direct.chapter_count">共 {{ direct.chapter_count }} 章</span>
            <span v-if="direct.word_count">{{ (direct.word_count / 10000).toFixed(1) }} 万字</span>
            <span v-if="direct.last_chapter_title" class="truncate" style="max-width: 200px">
              最新：{{ direct.last_chapter_title }}
            </span>
          </div>
          <div class="book-actions">
            <button
              class="btn btn-primary btn-sm"
              :disabled="!canSubmit"
              @click="openDialog(directAsBook(direct))"
            >
              <Icon name="download" :size="13" />下载
            </button>
            <button class="btn btn-outline btn-sm" @click="keyword = ''; direct = null; searched = false">
              <Icon name="close" :size="12" />清除
            </button>
            <span class="text-xs muted mono spacer truncate" style="max-width: 130px">
              {{ direct.book_id }}
            </span>
          </div>
        </div>
      </article>
    </div>

    <!-- 加载骨架 -->
    <div v-if="loading" class="book-grid">
      <div v-for="i in 6" :key="i" class="book-card">
        <div class="book-cover skeleton" />
        <div class="book-main stack-sm">
          <div class="skeleton" style="width: 70%" />
          <div class="skeleton" style="width: 40%" />
          <div class="skeleton" style="width: 90%" />
        </div>
      </div>
    </div>

    <!-- 结果 -->
    <div v-else-if="items.length">
      <div class="row" style="margin-bottom: 12px">
        <span class="badge badge-primary">共 {{ items.length }} 个结果</span>
        <span v-if="!canSubmit" class="badge badge-warning">
          已有任务在跑，需等待完成才能新建
        </span>
      </div>
      <div class="book-grid">
        <article v-for="book in items" :key="book.book_id" class="book-card">
          <img
            v-if="coverOf(book)"
            class="book-cover"
            :src="coverOf(book)"
            :alt="book.title"
            loading="lazy"
            decoding="async"
            referrerpolicy="no-referrer"
            @error="onCoverError(book)"
          />
          <div v-else class="book-cover center" style="display: grid; place-items: center"><Icon name="image" :size="22" /></div>

          <div class="book-main">
            <div class="book-title">{{ book.title || '未知书名' }}</div>
            <div class="book-meta">
              <span>{{ book.author || '未知作者' }}</span>
              <span v-if="readCountOf(book)">{{ readCountOf(book) }}</span>
              <span v-if="scoreOf(book)">评分 {{ scoreOf(book) }}</span>
            </div>
            <div class="row row-wrap" style="gap: 6px">
              <span v-if="finishedOf(book)" class="badge" :class="finishedOf(book).cls">
                {{ finishedOf(book).text }}
              </span>
              <span v-for="t in tagsOf(book).slice(0, 3)" :key="t" class="badge">{{ t }}</span>
            </div>
            <p class="book-abstract">{{ abstractOf(book) }}</p>
            <div class="book-meta">
              <span v-if="chaptersOf(book)">{{ chaptersOf(book) }} 章</span>
              <span v-if="wordsOf(book)">{{ wordsOf(book) }}</span>
            </div>
            <div class="book-actions">
              <button class="btn btn-primary btn-sm" :disabled="!canSubmit" @click="openDialog(book)">
                <Icon name="download" :size="13" />下载
              </button>
              <a
                class="btn btn-outline btn-sm"
                :href="`https://fanqienovel.com/page/${book.book_id}`"
                target="_blank"
                rel="noopener noreferrer"
              >
                <Icon name="external" :size="12" />原页
              </a>
              <span class="text-xs muted mono spacer truncate" style="max-width: 120px">
                {{ book.book_id }}
              </span>
            </div>
          </div>
        </article>
      </div>
    </div>

    <!-- 空态（书号直查命中或加载中时不显示） -->
    <div v-else-if="searched && !direct && !directLoading" class="card">
      <div class="empty">
        <div class="empty-icon"><Icon name="search" :size="34" /></div>
        <div>没有找到匹配的书籍，换个关键词试试</div>
      </div>
    </div>
    <div v-else class="card">
      <div class="empty">
        <div class="empty-icon"><Icon name="bookOpen" :size="34" /></div>
        <div>输入书名开始搜索</div>
        <p class="text-xs muted mt-8">搜索结果来自番茄小说官方接口，仅供个人阅读使用</p>
      </div>
    </div>

    <!-- 下载弹窗 -->
    <div v-if="dialog" class="modal-mask" @click.self="closeDialog">
      <div class="modal">
        <div class="modal-head">
          下载设置
          <button class="btn-icon spacer" style="border: none" @click="closeDialog">
            <Icon name="close" :size="15" />
          </button>
        </div>
        <div class="modal-body">
          <div class="row">
            <img
              v-if="coverOf(dialog.book)"
              class="book-cover"
              :src="coverOf(dialog.book)"
              alt=""
              referrerpolicy="no-referrer"
              @error="onCoverError(dialog.book)"
            />
            <div style="min-width: 0">
              <b>{{ dialog.book.title }}</b>
              <div class="text-sm muted">{{ dialog.book.author }}</div>
              <div class="text-xs muted mono">{{ dialog.book.book_id }}</div>
            </div>
          </div>

          <label class="switch">
            <input v-model="rangeEnabled" type="checkbox" />
            <span class="switch-track" />
            <span>只下载指定章节范围</span>
          </label>

          <div v-if="rangeEnabled" class="form-grid">
            <div class="field">
              <label class="field-label" for="rs">起始章节</label>
              <input id="rs" v-model="rangeStart" class="input" type="number" min="1" placeholder="1" />
            </div>
            <div class="field">
              <label class="field-label" for="re">结束章节</label>
              <input id="re" v-model="rangeEnd" class="input" type="number" min="1" placeholder="100" />
            </div>
            <p class="field-hint" style="grid-column: 1 / -1">
              留空则下载全书。范围需同时填写，且结束章节不得超过该书总章节数，否则后端会忽略范围按全书下载。
            </p>
          </div>

          <p class="field-hint">
            输出格式（txt/epub）与有声小说等设置由
            <RouterLink :to="{ name: 'config' }">配置页</RouterLink> 决定；下载过程中后端可能还会询问章节名与格式。
          </p>
        </div>
        <div class="modal-foot">
          <button class="btn btn-ghost" @click="closeDialog">取消</button>
          <button class="btn btn-primary" :disabled="submitting" @click="confirmDownload">
            <span v-if="submitting" class="spinner" />
            {{ submitting ? '创建中…' : '开始下载' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
