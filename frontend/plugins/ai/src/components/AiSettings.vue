<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { aiApi, type AiSettings } from '../api/aiApi'

const providers = [
  { value: 'opencode-go', label: 'OpenCode Go', model: 'glm-5.3-flash' },
  { value: 'claude', label: 'Claude API', model: 'claude-sonnet-4-6' },
  { value: 'openai', label: 'OpenAI', model: 'gpt-4.1-mini' },
]

const settings = ref<AiSettings>({
  provider: 'opencode-go',
  model: 'glm-5.3-flash',
  maxTextChars: 1_000_000,
  keyConfigured: false,
})
const savedProvider = ref(settings.value.provider)
const apiKey = ref('')
const clearApiKey = ref(false)
const loading = ref(true)
const saving = ref(false)
const testing = ref(false)
const error = ref('')
const message = ref('')

const modelPlaceholder = computed(
  () => providers.find((provider) => provider.value === settings.value.provider)?.model ?? '',
)
const providerChanged = computed(() => settings.value.provider !== savedProvider.value)
const keyStatusText = computed(() => {
  if (providerChanged.value) return '保存切换后才能确认新 Provider 的 key 状态。'
  return settings.value.keyConfigured ? '当前 Provider 已配置 key。' : '当前 Provider 尚未配置 key。'
})

let providerBeforeSelection = settings.value.provider

function providerSelectionChanged(event: Event): void {
  const previous = providerBeforeSelection
  const provider = (event.currentTarget as HTMLSelectElement).value
  providerBeforeSelection = provider
  const previousDefault = providers.find((item) => item.value === previous)?.model
  if (!settings.value.model.trim() || settings.value.model.trim() === previousDefault) {
    settings.value.model = providers.find((item) => item.value === provider)?.model ?? ''
  }
  settings.value.provider = provider
  apiKey.value = ''
  clearApiKey.value = false
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    settings.value = await aiApi.settings()
    savedProvider.value = settings.value.provider
    providerBeforeSelection = settings.value.provider
  } catch (e) {
    error.value = '读取 AI 设置失败：' + (e as Error).message
  } finally {
    loading.value = false
  }
}

async function save(): Promise<boolean> {
  if (!settings.value.model.trim()) {
    error.value = '请填写模型名称'
    return false
  }
  const maxTextChars = Number(settings.value.maxTextChars)
  if (!Number.isInteger(maxTextChars) || maxTextChars < 1_000 || maxTextChars > 1_000_000) {
    error.value = '标签抽取文本上限必须是 1,000 到 1,000,000 之间的整数'
    return false
  }
  settings.value.maxTextChars = maxTextChars
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    settings.value = await aiApi.saveSettings({
      provider: settings.value.provider,
      model: settings.value.model.trim(),
      apiKey: apiKey.value.trim() || undefined,
      clearApiKey: clearApiKey.value,
      maxTextChars,
    })
    savedProvider.value = settings.value.provider
    providerBeforeSelection = settings.value.provider
    apiKey.value = ''
    clearApiKey.value = false
    message.value = 'AI 设置已保存'
    return true
  } catch (e) {
    error.value = '保存失败：' + (e as Error).message
    return false
  } finally {
    saving.value = false
  }
}

async function testConnection(): Promise<void> {
  if (!(await save())) return
  testing.value = true
  error.value = ''
  message.value = ''
  try {
    const result = await aiApi.test()
    if (result.success) message.value = result.message
    else error.value = result.message
  } catch (e) {
    error.value = 'AI 连接测试失败：' + (e as Error).message
  } finally {
    testing.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="ai-settings">
    <div class="settings-heading">
      <h3>AI 能力</h3>
      <p class="muted">Graphify 与自动标签共用这里的 Provider、模型和 API key。</p>
    </div>

    <p v-if="loading" class="muted">正在读取设置…</p>
    <form v-else class="settings-form" @submit.prevent="save">
      <label>
        <span>Provider</span>
        <select :value="settings.provider" @change="providerSelectionChanged">
          <option v-for="provider in providers" :key="provider.value" :value="provider.value">
            {{ provider.label }}
          </option>
        </select>
      </label>

      <label>
        <span>模型</span>
        <input v-model="settings.model" :placeholder="modelPlaceholder" autocomplete="off" />
      </label>

      <label>
        <span>标签抽取文本上限（字符）</span>
        <input
          v-model.number="settings.maxTextChars"
          type="number"
          min="1000"
          max="1000000"
          step="1000"
        />
        <small class="muted">默认 1,000,000；按字符计算，不是 token。</small>
      </label>

      <p v-if="settings.provider === 'opencode-go'" class="muted">
        OpenCode Go 使用 Chat Completions 接口；请填写支持所需文本和图片输入的模型 ID。
      </p>

      <label>
        <span>API key</span>
        <input
          v-model="apiKey"
          type="password"
          autocomplete="new-password"
          placeholder="留空保持已保存的 key"
          :disabled="clearApiKey"
        />
        <small class="muted">
          {{ keyStatusText }}
          key 只保存在本机 AI 插件目录，不会由读取接口返回。
        </small>
      </label>

      <label v-if="settings.keyConfigured && !providerChanged" class="clear-key">
        <input v-model="clearApiKey" type="checkbox" />
        <span>清除当前 Provider 已保存的 key</span>
      </label>

      <div class="form-actions">
        <button type="submit" :disabled="saving || testing">
          {{ saving ? '保存中…' : '保存设置' }}
        </button>
        <button type="button" :disabled="saving || testing" @click="testConnection">
          {{ testing ? '正在测试…' : '保存并测试连接' }}
        </button>
      </div>
    </form>

    <div class="privacy-note">
      <strong>数据发送说明</strong>
      <p>
        Graphify 索引和自动标签会把笔记标题、卡片正文、被引用的图片发送给当前 Provider。
        测试连接只发送合成文本。
      </p>
    </div>

    <p v-if="message" class="feedback success">{{ message }}</p>
    <p v-if="error" class="feedback error">{{ error }}</p>
  </section>
</template>

<style scoped>
.ai-settings {
  display: grid;
  gap: 14px;
  color: var(--c-text);
}

.settings-heading h3 {
  margin: 0 0 4px;
}

.settings-heading p,
.privacy-note p {
  margin: 4px 0 0;
}

.muted {
  color: var(--c-text-muted, #8b9098);
  font-size: 12px;
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

.settings-form input:not([type='checkbox']),
.settings-form select {
  width: 100%;
  box-sizing: border-box;
  min-height: 34px;
  padding: 6px 9px;
  color: var(--c-text);
  background: var(--c-surface, #fff);
  border: 1px solid var(--c-border);
  border-radius: 6px;
}

.settings-form small {
  line-height: 1.5;
}

.clear-key {
  display: flex !important;
  align-items: center;
  gap: 7px !important;
}

.form-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
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

.privacy-note {
  max-width: 620px;
  padding: 11px 13px;
  background: var(--c-surface-2);
  border-radius: 7px;
  font-size: 12px;
  line-height: 1.5;
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
