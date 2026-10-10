<script setup>
import { onMounted, ref } from 'vue'
import Icon from '../components/Icon.vue'
import { api } from '../api.js'

const version = ref('')

onMounted(async () => {
  try {
    const s = await api.status()
    version.value = s?.version || ''
  } catch {
    /* 忽略：关于页不依赖后端可用性 */
  }
})

const DEV_NAME = '离愁Lico'
const DEV_EMAIL = '2197958514@qq.com'
const REPO_URL = 'https://github.com/HZY-520/fq-xz'
const UPSTREAM_URL = 'https://github.com/zhongbai2333/Tomato-Novel-Downloader'
</script>

<template>
  <div class="stack">
    <!-- 应用信息 -->
    <div class="card">
      <div class="card-head">
        <Icon name="bookOpen" :size="16" />
        <span style="margin-left: 8px">小番茄</span>
      </div>
      <div class="card-body stack-sm">
        <div class="row" style="align-items: center; gap: 12px">
          <div
            class="brand-mark"
            style="width: 44px; height: 44px; font-size: 22px; display: flex; align-items: center; justify-content: center"
          >
            🍅
          </div>
          <div>
            <div style="font-size: 18px; font-weight: 700">小番茄</div>
            <div class="text-xs muted">番茄小说下载器 · 安卓版</div>
          </div>
        </div>
        <div class="row text-sm">
          <span class="muted">版本</span>
          <span class="spacer mono">{{ version ? 'v' + version : '—' }}</span>
        </div>
        <div class="row text-sm">
          <span class="muted">包名</span>
          <span class="spacer mono">com.fanqie.lico</span>
        </div>
      </div>
    </div>

    <!-- 开发者信息 -->
    <div class="card">
      <div class="card-head">
        <Icon name="user" :size="16" />
        <span style="margin-left: 8px">开发者</span>
      </div>
      <div class="card-body stack-sm">
        <div class="row text-sm">
          <span class="muted">开发者</span>
          <span class="spacer" style="font-weight: 600">{{ DEV_NAME }}</span>
        </div>
        <div class="row text-sm">
          <span class="muted">邮箱</span>
          <a class="spacer" :href="'mailto:' + DEV_EMAIL">{{ DEV_EMAIL }}</a>
        </div>
      </div>
    </div>

    <!-- 开源仓库 -->
    <div class="card">
      <div class="card-head">
        <Icon name="code" :size="16" />
        <span style="margin-left: 8px">开源仓库</span>
      </div>
      <div class="card-body stack-sm">
        <a class="btn btn-outline" :href="REPO_URL" target="_blank" rel="noreferrer">
          <Icon name="code" :size="14" />
          <span style="margin-left: 6px">本应用仓库</span>
          <span class="spacer text-xs muted truncate" style="max-width: 55%">{{ REPO_URL }}</span>
          <Icon name="external" :size="13" />
        </a>
        <a class="btn btn-outline" :href="UPSTREAM_URL" target="_blank" rel="noreferrer">
          <Icon name="external" :size="14" />
          <span style="margin-left: 6px">上游项目 TomN-Downloader</span>
        </a>
        <p class="text-xs muted" style="margin: 4px 0 0">
          本应用基于开源项目 Tomato-Novel-Downloader（作者 zhongbai2333）及其独立前端构建，
          下载与解析能力由内嵌的本地下载器后端提供，通过内置本地代理访问。
        </p>
      </div>
    </div>

    <!-- 免责声明 -->
    <div class="card">
      <div class="card-head">
        <Icon name="alert" :size="16" />
        <span style="margin-left: 8px">免责声明</span>
      </div>
      <div class="card-body">
        <p class="text-xs muted" style="margin: 0; line-height: 1.7">
          本应用仅供个人学习、研究与阅读使用，请勿将下载内容用于转载、分发或任何商业用途，
          亦不得用于任何违反法律法规或侵犯他人权益的活动。使用本应用所产生的任何后果由使用者自行承担。
        </p>
      </div>
    </div>
  </div>
</template>