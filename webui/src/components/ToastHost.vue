<script setup>
import { ui, dismissToast } from '../store.js'
import Icon from './Icon.vue'

const ICONS = { success: 'check', error: 'alert', warning: 'alert', info: 'alert' }
</script>

<template>
  <div class="toasts">
    <div v-for="t in ui.toasts" :key="t.id" class="toast" :class="t.type">
      <Icon :name="ICONS[t.type] || 'alert'" :size="15" style="margin-top: 2px; flex: none" />
      <span style="min-width: 0; word-break: break-word">{{ t.message }}</span>
      <button class="toast-close" title="关闭" @click="dismissToast(t.id)">×</button>
    </div>
  </div>
</template>
