<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import Icon from '../components/Icon.vue'
import { api } from '../api.js'
import {
  toastOk,
  toastErr,
  toastWarn,
  jobStateInfo,
  formatRelative,
  refreshActiveJobs,
} from '../store.js'

const jobs = ref([])
const loading = ref(true)
const showAll = ref(false)
const busyId = ref(null)
// 记录用户已选择过的任务，避免选择器选完还显示
const chosen = ref({})

let timer = null

const activeJobs = computed(() =>
  jobs.value.filter((j) => j.state === 'queued' || j.state === 'running'),
)
const finishedJobs = computed(() =>
  jobs.value.filter((j) => j.state !== 'queued' && j.state !== 'running'),
)

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const data = await api.listJobs({ all: showAll.value ? 'true' : undefined })
    jobs.value = data.items || []
  } catch (err) {
    if (!silent) toastErr(`加载任务失败：${err.message}`)
  } finally {
    loading.value = false
  }
}

function percent(job) {
  const p = job.progress
  if (!p || !p.chapter_total) return 0
  return Math.min(100, Math.round(((p.saved_chapters || 0) / p.chapter_total) * 100))
}

function hasPicker(job) {
  if (chosen.value[job.id]) return false
  return !!(job.book_name_options?.length || job.format_options?.length)
}

async function cancel(job) {
  busyId.value = job.id
  try {
    await api.cancelJob(job.id)
    toastOk('已请求取消任务')
    await load(true)
    await refreshActiveJobs()
  } catch (err) {
    toastErr(`取消失败：${err.message}`)
  } finally {
    busyId.value = null
  }
}

async function remove(job) {
  busyId.value = job.id
  try {
    await api.deleteJob(job.id)
    toastOk('任务已删除')
    await load(true)
    await refreshActiveJobs()
  } catch (err) {
    toastErr(`删除失败：${err.message}`)
  } finally {
    busyId.value = null
  }
}

async function pickBookName(job, value) {
  try {
    await api.submitBookName(job.id, value)
    chosen.value = { ...chosen.value, [job.id]: true }
    toastOk('已提交书名选择')
    await load(true)
  } catch (err) {
    toastErr(`提交失败：${err.message}`)
  }
}

async function pickFormat(job, value) {
  try {
    await api.submitFormat(job.id, value)
    chosen.value = { ...chosen.value, [job.id]: true }
    toastOk('已提交格式选择')
    await load(true)
  } catch (err) {
    toastErr(`提交失败：${err.message}`)
  }
}

function toggleAll() {
  showAll.value = !showAll.value
  load()
}

onMounted(() => {
  load()
  timer = setInterval(() => load(true), 2000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="stack">
    <div class="row row-wrap">
      <span class="badge badge-primary">{{ activeJobs.length }} 个进行中</span>
      <span class="badge">{{ finishedJobs.length }} 个已结束</span>
      <div class="spacer" />
      <label class="switch">
        <input :checked="showAll" type="checkbox" @change="toggleAll" />
        <span class="switch-track" />
        <span class="text-sm">显示全部（含 2 小时前已完成）</span>
      </label>
      <button class="btn btn-outline btn-sm" @click="load()">
        <Icon name="refresh" :size="13" />刷新
      </button>
    </div>

    <p class="text-xs muted">
      并发限制：后端同时只允许 <b>1 个</b> 任务（排队或下载中），完成后才会自动清理 2 小时以上的已完成记录。
    </p>

    <!-- 加载中 -->
    <div v-if="loading && !jobs.length" class="card">
      <div class="card-body stack-sm">
        <div class="skeleton" style="width: 40%" />
        <div class="skeleton" style="width: 100%; height: 8px" />
        <div class="skeleton" style="width: 60%" />
      </div>
    </div>

    <!-- 任务列表 -->
    <div v-else-if="jobs.length" class="stack">
      <article v-for="job in jobs" :key="job.id" class="job">
        <div class="job-head">
          <span class="badge" :class="jobStateInfo(job.state).badge">
            <span
              v-if="job.state === 'running'"
              class="dot dot-pulse"
            />
            {{ jobStateInfo(job.state).label }}
          </span>
          <b class="job-title" style="flex: 1; min-width: 120px">
            {{ job.title || `任务 #${job.id}` }}
          </b>
          <span class="job-meta mono">#{{ job.id }}</span>
          <button
            v-if="job.state === 'queued' || job.state === 'running'"
            class="btn btn-sm btn-danger"
            :disabled="busyId === job.id"
            @click="cancel(job)"
          >
            <Icon name="x" :size="12" />取消
          </button>
          <button
            v-else
            class="btn btn-sm btn-ghost"
            :disabled="busyId === job.id"
            @click="remove(job)"
          >
            <Icon name="trash" :size="12" />删除
          </button>
        </div>

        <div class="job-meta" style="margin-top: 4px">
          <span v-if="job.author">{{ job.author }} · </span>
          <span class="mono">{{ job.book_id }}</span>
          · 更新于 {{ formatRelative(job.updated_ms) }}
        </div>

        <!-- 进度条 -->
        <div v-if="job.state === 'running' || job.state === 'queued'" class="mt-8">
          <div class="progress">
            <div
              class="progress-bar"
              :class="{ indeterminate: job.state === 'queued', done: percent(job) === 100 }"
              :style="job.state === 'queued' ? {} : { width: percent(job) + '%' }"
            />
          </div>
        </div>
        <div v-else-if="job.state === 'done'" class="mt-8">
          <div class="progress">
            <div class="progress-bar done" style="width: 100%" />
          </div>
        </div>

        <!-- 进度详情 -->
        <div v-if="job.progress" class="job-stats">
          <span>
            章节 <b>{{ job.progress.saved_chapters }}</b> / {{ job.progress.chapter_total }}
          </span>
          <span>
            分组 <b>{{ job.progress.group_done }}</b> / {{ job.progress.group_total }}
          </span>
          <span v-if="job.progress.comment_total">
            段评 <b>{{ job.progress.comment_saved }}</b> / {{ job.progress.comment_total }}
          </span>
          <span v-if="job.progress.audiobook_generated || job.progress.audiobook_failed">
            音频 生成 <b>{{ job.progress.audiobook_generated }}</b>
            <template v-if="job.progress.audiobook_skipped">
              · 跳过 {{ job.progress.audiobook_skipped }}
            </template>
            <template v-if="job.progress.audiobook_failed">
              · 失败 <b style="color: var(--danger)">{{ job.progress.audiobook_failed }}</b>
            </template>
          </span>
          <span v-if="job.progress.save_phase === 'Audiobook'" class="badge badge-info">
            正在生成有声小说
          </span>
        </div>

        <!-- 失败信息 -->
        <div v-if="job.message" class="mt-8">
          <div
            class="text-sm"
            :style="{ color: job.state === 'failed' ? 'var(--danger)' : 'var(--text-2)' }"
            style="word-break: break-word"
          >
            {{ job.message }}
          </div>
        </div>

        <!-- 书名选择器 -->
        <div v-if="job.book_name_options?.length && hasPicker(job)" class="mt-16">
          <div class="text-sm" style="font-weight: 600; margin-bottom: 6px">
            后端检测到多个书名，请选择：
          </div>
          <div class="row row-wrap">
            <button
              v-for="opt in job.book_name_options"
              :key="opt.value"
              class="btn btn-sm btn-outline"
              @click="pickBookName(job, opt.value)"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>

        <!-- 格式选择器 -->
        <div v-if="job.format_options?.length && hasPicker(job)" class="mt-16">
          <div class="text-sm" style="font-weight: 600; margin-bottom: 6px">请选择输出格式：</div>
          <div class="row row-wrap">
            <button
              v-for="opt in job.format_options"
              :key="opt.value"
              class="btn btn-sm btn-primary"
              @click="pickFormat(job, opt.value)"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>
      </article>
    </div>

    <!-- 空态 -->
    <div v-else class="card">
      <div class="empty">
        <div class="empty-icon"><Icon name="inbox" :size="34" /></div>
        <div>当前没有任务</div>
        <RouterLink class="btn btn-primary mt-16" :to="{ name: 'search' }">
          <Icon name="search" :size="14" />去搜索书籍
        </RouterLink>
      </div>
    </div>
  </div>
</template>
