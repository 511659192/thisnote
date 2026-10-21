<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { request } from '@thisnote/plugin-api'

interface NotesSettingsResponse {
  dataRoot: string
  activeDataRoot: string
  notesRoot: string
  activeNotesRoot: string
  restartRequired: boolean
  fontSize?: number
}

const dataRoot = ref('')
const activeDataRoot = ref('')
const notesRoot = ref('')
const activeNotesRoot = ref('')
const restartRequired = ref(false)
const fontSize = ref(15)
const loading = ref(true)
const saving = ref(false)
const savingRoot = ref(false)
const error = ref('')
const message = ref('')
const rootError = ref('')
const rootMessage = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const settings = await request<NotesSettingsResponse>('GET', '/api/notes/settings/get')
    applySettings(settings)
  } catch (e) {
    error.value = '读取笔记设置失败：' + (e as Error).message
  } finally {
    loading.value = false
  }
}

function applySettings(settings: NotesSettingsResponse) {
  dataRoot.value = settings.dataRoot
  activeDataRoot.value = settings.activeDataRoot
  notesRoot.value = settings.notesRoot
  activeNotesRoot.value = settings.activeNotesRoot
  restartRequired.value = settings.restartRequired
  fontSize.value = typeof settings.fontSize === 'number' && Number.isInteger(settings.fontSize) && settings.fontSize >= 10 && settings.fontSize <= 32
    ? settings.fontSize
    : 15
}

async function saveDataRoot(): Promise<void> {
  if (savingRoot.value) return
  const value = dataRoot.value.trim()
  if (!value) {
    rootError.value = '数据根目录不能为空'
    return
  }
  savingRoot.value = true
  rootError.value = ''
  rootMessage.value = ''
  try {
    const settings = await request<NotesSettingsResponse>('POST', '/api/notes/settings/update-root', { body: { dataRoot: value } })
    applySettings(settings)
    rootMessage.value = settings.restartRequired
      ? '目录已保存并初始化，重启后端后切换生效'
      : '目录已保存并初始化'
  } catch (e) {
    rootError.value = '保存目录失败：' + (e as Error).message
  } finally {
    savingRoot.value = false
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
    applySettings(settings)
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
    <section class="settings-block">
      <div class="settings-heading">
        <h3>笔记存储</h3>
        <p class="muted">数据根目录同时承载笔记与插件状态；笔记文件位于其中的 notes 子目录。</p>
      </div>
      <p v-if="loading" class="muted">正在读取设置…</p>
      <form v-else class="settings-form" @submit.prevent="saveDataRoot">
        <label>
          <span>数据根目录</span>
          <input
            v-model.trim="dataRoot"
            class="root-input"
            type="text"
            autocomplete="off"
            spellcheck="false"
            placeholder="例如 D:\thisnote-data"
          />
          <small class="muted">笔记目录：<span class="mono">{{ notesRoot }}</span></small>
        </label>
        <div class="storage-current">
          <p>当前生效：<span class="mono">{{ activeDataRoot }}</span></p>
          <p>当前笔记目录：<span class="mono">{{ activeNotesRoot }}</span></p>
          <p v-if="restartRequired" class="restart-hint">配置已变更，重启后端后切换生效。</p>
        </div>
        <div class="form-actions">
          <button type="submit" :disabled="savingRoot">{{ savingRoot ? '保存并初始化中…' : '保存并初始化' }}</button>
        </div>
      </form>
      <p v-if="rootMessage" class="feedback success">{{ rootMessage }}</p>
      <p v-if="rootError" class="feedback error">{{ rootError }}</p>
    </section>

    <div class="settings-divider" role="separator"></div>

    <section class="settings-block">
      <div class="settings-heading">
        <h3>笔记正文</h3>
        <p class="muted">字号作用于卡片正文的编辑、阅读、预览和自动高度测量。</p>
      </div>

      <form v-if="!loading" class="settings-form" @submit.prevent="save">
        <label>
          <span>正文字号</span>
          <div class="font-size-row">
            <input v-model.number="fontSize" type="number" min="10" max="32" step="1" />
            <span>px</span>
          </div>
        </label>

        <div class="form-actions">
          <button type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存设置' }}</button>
        </div>
      </form>
    </section>

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

.settings-block {
  display: grid;
  gap: 12px;
}

.settings-divider {
  border-top: 1px solid var(--c-border);
}

.root-input {
  width: 100%;
  box-sizing: border-box;
  padding: 7px 9px;
  color: var(--c-text);
  background: var(--c-surface, #fff);
  border: 1px solid var(--c-border);
  border-radius: 6px;
  font: inherit;
}

.root-input:focus {
  outline: none;
  border-color: var(--accent);
}

.storage-current {
  display: grid;
  gap: 4px;
  color: var(--c-text-muted, #8b9098);
  font-size: 12px;
}

.storage-current p {
  margin: 0;
  overflow-wrap: anywhere;
}

.restart-hint {
  color: var(--warning, #a15c00);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
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
