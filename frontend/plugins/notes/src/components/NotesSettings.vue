<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { request } from '@thisnote/plugin-api'

interface NotesSettingsResponse {
  rootDir: string
  fontSize?: number
}

const rootDir = ref('')
const fontSize = ref(15)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const message = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const settings = await request<NotesSettingsResponse>('GET', '/api/notes/settings/get')
    rootDir.value = settings.rootDir
    fontSize.value = typeof settings.fontSize === 'number' && Number.isInteger(settings.fontSize) && settings.fontSize >= 10 && settings.fontSize <= 32
      ? settings.fontSize
      : 15
  } catch (e) {
    error.value = '读取笔记设置失败：' + (e as Error).message
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  const value = Number(fontSize.value)
  if (!Number.isInteger(value) || value < 10 || value > 32) {
    error.value = '笔记正文字号必须在 10 到 32 之间'
    return
  }
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const settings = await request<NotesSettingsResponse>('POST', '/api/notes/settings/update', { body: { fontSize: value } })
    fontSize.value = typeof settings.fontSize === 'number' ? settings.fontSize : value
    message.value = '笔记设置已保存'
  } catch (e) {
    error.value = '保存失败：' + (e as Error).message
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="notes-settings">
    <div class="settings-heading">
      <h3>笔记正文</h3>
      <p class="muted">字号作用于卡片正文的编辑、阅读、预览和自动高度测量。</p>
    </div>

    <p v-if="loading" class="muted">正在读取设置…</p>
    <form v-else class="settings-form" @submit.prevent="save">
      <label>
        <span>正文字号</span>
        <div class="font-size-row">
          <input v-model.number="fontSize" type="number" min="10" max="32" step="1" />
          <span>px</span>
        </div>
        <small class="muted">当前笔记根目录：{{ rootDir }}</small>
      </label>

      <div class="form-actions">
        <button type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存设置' }}</button>
      </div>
    </form>

    <p v-if="message" class="feedback success">{{ message }}</p>
    <p v-if="error" class="feedback error">{{ error }}</p>
  </section>
</template>

<style scoped>
.notes-settings {
  display: grid;
  gap: 12px;
  color: var(--c-text);
}

.settings-heading h3 {
  margin: 0 0 4px;
}

.settings-heading p {
  margin: 0;
}

.muted {
  color: var(--c-text-muted, #8b9098);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.settings-form {
  display: grid;
  gap: 12px;
  max-width: 620px;
}

.settings-form label {
  display: grid;
  gap: 5px;
  font-size: 13px;
}

.font-size-row {
  display: flex;
  align-items: center;
  gap: 7px;
}

.font-size-row input {
  width: 84px;
  box-sizing: border-box;
  padding: 6px 9px;
  color: var(--c-text);
  background: var(--c-surface, #fff);
  border: 1px solid var(--c-border);
  border-radius: 6px;
  font: inherit;
}

.form-actions button {
  padding: 7px 11px;
  color: var(--c-text);
  background: var(--c-surface, #fff);
  border: 1px solid var(--c-border);
  border-radius: 6px;
  cursor: pointer;
}

.form-actions button:disabled {
  opacity: 0.55;
  cursor: wait;
}

.feedback {
  margin: 0;
  font-size: 12px;
}

.success {
  color: var(--c-success, #278449);
}

.error {
  color: var(--c-danger, #c33);
}
</style>