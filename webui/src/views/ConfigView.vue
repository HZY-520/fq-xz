<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import Icon from '../components/Icon.vue'
import { api } from '../api.js'
import { toastOk, toastErr, toastWarn } from '../store.js'

const tab = ref('common')
const loading = ref(true)

// 常用配置（/api/config）
const view = reactive({
  novel_format: 'txt',
  bulk_files: false,
  enable_audiobook: false,
  audiobook_format: 'mp3',
  ask_format_after_download: false,
})
const savingView = ref(false)

// 完整配置（/api/config/full）
const full = ref(null)
const savingFull = ref(false)
const fullError = ref('')

// 原始 YAML（/api/config/raw）
const yaml = ref('')
const yamlLoaded = ref(false)
const savingYaml = ref(false)

// 敏感字段：只显示“是否已设置”，不回显明文
const tokenTouched = ref(false)

const GROUPS = [
  {
    title: '下载与网络',
    icon: 'download',
    keys: [
      'max_workers',
      'request_timeout',
      'max_retries',
      'max_wait_time',
      'min_wait_time',
      'min_connect_timeout',
    ],
  },
  {
    title: '输出文件',
    icon: 'file',
    keys: [
      'novel_format',
      'bulk_files',
      'auto_clear_dump',
      'auto_open_downloaded_files',
      'save_path',
      'allow_overwrite_files',
      'preferred_book_name_field',
      'ask_format_after_download',
      'pdf_font_path',
    ],
  },
  {
    title: '正文来源',
    icon: 'server',
    keys: ['use_official_api', 'api_endpoints'],
  },
  {
    title: '有声小说（Edge TTS）',
    icon: 'play',
    keys: [
      'enable_audiobook',
      'audiobook_voice',
      'audiobook_rate',
      'audiobook_volume',
      'audiobook_pitch',
      'audiobook_format',
      'audiobook_concurrency',
      'audiobook_tts_provider',
      'audiobook_tts_api_url',
      'audiobook_tts_api_token',
      'audiobook_tts_model',
    ],
  },
  {
    title: '段评与图片',
    icon: 'book',
    keys: [
      'enable_segment_comments',
      'segment_comments_top_n',
      'segment_comments_workers',
      'download_comment_images',
      'download_comment_avatars',
      'media_download_workers',
      'blocked_media_domains',
      'force_convert_images_to_jpeg',
      'jpeg_retry_convert',
      'jpeg_quality',
      'convert_heic_to_jpeg',
      'keep_heic_original',
      'media_limit_per_chapter',
      'media_max_dimension_px',
    ],
  },
  {
    title: '其他',
    icon: 'settings',
    keys: ['old_cli'],
  },
]

const LABELS = {
  max_workers: '最大并发线程',
  request_timeout: '请求超时（秒）',
  max_retries: '最大重试次数',
  max_wait_time: '最大等待时间（毫秒）',
  min_wait_time: '最小等待时间（毫秒）',
  min_connect_timeout: '最小连接超时（秒）',
  novel_format: '输出格式（txt/epub）',
  bulk_files: '分章输出为多个文件',
  auto_clear_dump: '自动清理临时文件',
  auto_open_downloaded_files: '下载后自动打开',
  save_path: '自定义保存路径（留空用默认）',
  allow_overwrite_files: '允许覆盖已有文件',
  preferred_book_name_field: '书名优先字段',
  ask_format_after_download: '下载后询问输出格式',
  pdf_font_path: 'PDF 字体路径',
  use_official_api: '使用官方 API 获取正文',
  api_endpoints: '第三方接口地址池（每行一个）',
  enable_audiobook: '生成有声小说',
  audiobook_voice: '发音人',
  audiobook_rate: '语速（如 +0%）',
  audiobook_volume: '音量（如 +0%）',
  audiobook_pitch: '音调（如 +2Hz，可留空）',
  audiobook_format: '音频格式（mp3/wav）',
  audiobook_concurrency: '有声小说并发数',
  audiobook_tts_provider: 'TTS 提供方',
  audiobook_tts_api_url: 'TTS 接口地址',
  audiobook_tts_api_token: 'TTS 接口 Token',
  audiobook_tts_model: 'TTS 模型名',
  enable_segment_comments: '下载段评（需 epub 格式）',
  segment_comments_top_n: '段评取前 N 条',
  segment_comments_workers: '段评并发数',
  download_comment_images: '下载段评配图',
  download_comment_avatars: '下载段评头像',
  media_download_workers: '媒体下载并发数',
  blocked_media_domains: '屏蔽的媒体域名（每行一个）',
  force_convert_images_to_jpeg: '强制转 JPEG',
  jpeg_retry_convert: 'JPEG 转换失败重试',
  jpeg_quality: 'JPEG 质量（1-100）',
  convert_heic_to_jpeg: 'HEIC 转 JPEG',
  keep_heic_original: '保留 HEIC 原图',
  media_limit_per_chapter: '每章媒体上限（0 不限）',
  media_max_dimension_px: '图片最大边长（px）',
  old_cli: '使用老版 CLI 界面',
}

const HINTS = {
  max_workers: '建议保持 1，调高会加大上游接口压力',
  use_official_api: '关闭后强制走第三方接口，搜索与段评将不可用',
  enable_segment_comments: '仅在输出格式为 epub 时有效',
  audiobook_tts_api_token: '已设置时留空表示不修改',
  save_path: '容器内路径，例如 /data',
}

function typeOf(key) {
  const v = full.value?.[key]
  if (typeof v === 'boolean') return 'bool'
  if (typeof v === 'number') return 'number'
  if (Array.isArray(v)) return 'list'
  if (v === null) return 'null'
  return 'string'
}

function isSensitive(key) {
  return key === 'audiobook_tts_api_token'
}

function listToText(v) {
  return Array.isArray(v) ? v.join('\n') : ''
}

function textToList(t) {
  return String(t || '')
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
}

async function loadCommon() {
  const d = await api.configView()
  Object.assign(view, d)
}

async function loadFull() {
  fullError.value = ''
  try {
    full.value = await api.configFull()
  } catch (err) {
    fullError.value = err.message
  }
}

async function loadYaml() {
  try {
    const d = await api.configRaw()
    yaml.value = d?.yaml ?? ''
    yamlLoaded.value = true
  } catch (err) {
    toastErr(`读取 YAML 失败：${err.message}`)
  }
}

async function saveCommon() {
  savingView.value = true
  try {
    await api.saveConfigView({ ...view })
    toastOk('常用配置已保存')
    await loadFull()
  } catch (err) {
    toastErr(`保存失败：${err.message}`)
  } finally {
    savingView.value = false
  }
}

async function saveFull() {
  if (!full.value) return
  savingFull.value = true
  try {
    const payload = { ...full.value }
    // 敏感字段：未改动则清空，避免回写时把掩码值当真值
    if (isSensitive('audiobook_tts_api_token') && !tokenTouched.value) {
      payload.audiobook_tts_api_token = ''
    }
    await api.saveConfigFull(payload)
    toastOk('完整配置已保存')
    tokenTouched.value = false
    await Promise.all([loadCommon(), loadFull()])
  } catch (err) {
    toastErr(`保存失败：${err.status === 400 ? '配置校验未通过（字段值不合法）' : err.message}`)
  } finally {
    savingFull.value = false
  }
}

async function saveYaml() {
  savingYaml.value = true
  try {
    await api.saveConfigRaw(yaml.value)
    toastOk('YAML 配置已保存')
    await Promise.all([loadCommon(), loadFull()])
    yamlLoaded.value = false
  } catch (err) {
    toastErr(`保存失败：${err.status === 400 ? 'YAML 格式或内容不合法' : err.message}`)
  } finally {
    savingYaml.value = false
  }
}

function switchTab(t) {
  tab.value = t
  if (t === 'full' && !full.value) loadFull()
  if (t === 'yaml' && !yamlLoaded.value) loadYaml()
}

function resetToken() {
  if (!full.value) return
  full.value.audiobook_tts_api_token = ''
  tokenTouched.value = true
  toastWarn('已清空 Token 输入框，保存后将清除服务端 Token')
}

onMounted(async () => {
  try {
    await Promise.all([loadCommon(), loadFull()])
  } catch (err) {
    toastErr(`读取配置失败：${err.message}`)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="stack">
    <!-- 标签栏 -->
    <div class="row">
      <button
        class="btn"
        :class="tab === 'common' ? 'btn-primary' : 'btn-outline'"
        @click="switchTab('common')"
      >
        <Icon name="settings" :size="14" />常用设置
      </button>
      <button
        class="btn"
        :class="tab === 'full' ? 'btn-primary' : 'btn-outline'"
        @click="switchTab('full')"
      >
        <Icon name="server" :size="14" />完整配置
      </button>
      <button
        class="btn"
        :class="tab === 'yaml' ? 'btn-primary' : 'btn-outline'"
        @click="switchTab('yaml')"
      >
        <Icon name="file" :size="14" />YAML 原始配置
      </button>
    </div>

    <!-- ── 常用设置 ─────────────────────────────────────────── -->
    <div v-if="tab === 'common'" class="card">
      <div class="card-head">
        <Icon name="settings" :size="15" />
        <h2>常用设置</h2>
        <span class="text-xs muted spacer">与后端 Web UI 暴露的快捷配置一致</span>
      </div>
      <div class="card-body stack">
        <div class="form-grid">
          <div class="field">
            <label class="field-label" for="nf">输出格式</label>
            <select id="nf" v-model="view.novel_format" class="select">
              <option value="txt">txt（纯文本）</option>
              <option value="epub">epub（电子书，支持段评）</option>
            </select>
          </div>

          <div class="field">
            <label class="field-label" for="af">有声小说格式</label>
            <select id="af" v-model="view.audiobook_format" class="select">
              <option value="mp3">mp3</option>
              <option value="wav">wav</option>
            </select>
          </div>
        </div>

        <label class="switch">
          <input v-model="view.bulk_files" type="checkbox" />
          <span class="switch-track" />
          <span>分章输出为多个文件</span>
        </label>

        <label class="switch">
          <input v-model="view.enable_audiobook" type="checkbox" />
          <span class="switch-track" />
          <span>下载完成后生成有声小说（需要能访问微软 TTS）</span>
        </label>

        <label class="switch">
          <input v-model="view.ask_format_after_download" type="checkbox" />
          <span class="switch-track" />
          <span>下载后询问输出格式</span>
        </label>

        <div class="row">
          <p class="field-hint">修改后立即写入后端 <code>config.yml</code></p>
          <button class="btn btn-primary spacer" :disabled="savingView" @click="saveCommon">
            <span v-if="savingView" class="spinner" />
            <Icon v-else name="save" :size="14" />保存常用设置
          </button>
        </div>
      </div>
    </div>

    <!-- ── 完整配置 ─────────────────────────────────────────── -->
    <template v-if="tab === 'full'">
      <div v-if="!full && !fullError" class="card">
        <div class="card-body stack-sm">
          <div v-for="i in 6" :key="i" class="skeleton" style="height: 26px" />
        </div>
      </div>

      <div v-else-if="fullError" class="card">
        <div class="card-body row">
          <Icon name="alert" :size="16" style="color: var(--danger)" />
          <span>{{ fullError }}</span>
          <button class="btn btn-outline btn-sm spacer" @click="loadFull">重试</button>
        </div>
      </div>

      <template v-else>
        <div v-for="g in GROUPS" :key="g.title" class="card">
          <div class="card-head">
            <Icon :name="g.icon" :size="15" />
            <h2>{{ g.title }}</h2>
          </div>
          <div class="card-body form-grid">
            <template v-for="key in g.keys" :key="key">
              <div v-if="key in full" class="field">
                <label class="field-label" :for="`f-${key}`">{{ LABELS[key] || key }}</label>

                <!-- 布尔 -->
                <label v-if="typeOf(key) === 'bool'" class="switch" style="margin-top: 2px">
                  <input :id="`f-${key}`" v-model="full[key]" type="checkbox" />
                  <span class="switch-track" />
                  <span class="text-sm">{{ full[key] ? '开启' : '关闭' }}</span>
                </label>

                <!-- 数字 -->
                <input
                  v-else-if="typeOf(key) === 'number'"
                  :id="`f-${key}`"
                  v-model.number="full[key]"
                  class="input"
                  type="number"
                  step="any"
                />

                <!-- 数组 -->
                <textarea
                  v-else-if="typeOf(key) === 'list'"
                  :id="`f-${key}`"
                  :value="listToText(full[key])"
                  class="textarea"
                  style="min-height: 84px"
                  @input="full[key] = textToList($event.target.value)"
                />

                <!-- 敏感 token -->
                <template v-else-if="isSensitive(key)">
                  <input
                    :id="`f-${key}`"
                    v-model="full[key]"
                    class="input"
                    type="password"
                    :placeholder="full[key] ? '已设置（留空不修改）' : '未设置'"
                    @input="tokenTouched = true"
                  />
                  <div class="row" style="gap: 8px">
                    <span v-if="full[key] && !tokenTouched" class="badge badge-success">
                      <Icon name="check" :size="11" />已设置
                    </span>
                    <button
                      v-if="full[key]"
                      class="btn btn-sm btn-ghost"
                      style="padding: 2px 6px"
                      @click="resetToken"
                    >
                      清除
                    </button>
                  </div>
                </template>

                <!-- 字符串（null 也可编辑） -->
                <input
                  v-else
                  :id="`f-${key}`"
                  v-model="full[key]"
                  class="input"
                  type="text"
                  :placeholder="typeOf(key) === 'null' ? '（未设置）' : ''"
                />

                <span v-if="HINTS[key]" class="field-hint">{{ HINTS[key] }}</span>
              </div>
            </template>
          </div>
        </div>

        <div class="card">
          <div class="card-body row">
            <p class="field-hint">
              完整配置会整体覆盖后端 <code>config.yml</code>，保存前请确认数值合理；
              后端会做校验，非法值会返回 400 并拒绝保存。
            </p>
            <button class="btn btn-primary spacer" :disabled="savingFull" @click="saveFull">
              <span v-if="savingFull" class="spinner" />
              <Icon v-else name="save" :size="14" />保存完整配置
            </button>
          </div>
        </div>
      </template>
    </template>

    <!-- ── YAML ─────────────────────────────────────────────── -->
    <div v-if="tab === 'yaml'" class="card">
      <div class="card-head">
        <Icon name="file" :size="15" />
        <h2>YAML 原始配置</h2>
        <div class="row spacer">
          <button class="btn btn-outline btn-sm" @click="loadYaml">
            <Icon name="refresh" :size="13" />重新载入
          </button>
          <button class="btn btn-primary btn-sm" :disabled="savingYaml" @click="saveYaml">
            <span v-if="savingYaml" class="spinner" />
            <Icon v-else name="save" :size="13" />保存
          </button>
        </div>
      </div>
      <div class="card-body stack">
        <p class="field-hint">
          这里会带上后端生成的注释，是最完整的编辑方式。YAML 语法错误或字段非法都会被后端拒绝保存（返回 400），
          不会写坏配置文件。
        </p>
        <textarea
          v-model="yaml"
          class="textarea"
          style="min-height: 460px"
          spellcheck="false"
          placeholder="载入中…"
        />
      </div>
    </div>
  </div>
</template>
