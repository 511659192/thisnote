<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ConfirmDialog } from '@thisnote/plugin-api'
import { aiApi, type AiProfile, type AiProviderDefaults } from '../api/aiApi'

interface Draft {
  id: string
  label: string
  provider: string
  model: string
  endpoint: string
  maxTextChars: number
}

const profiles = ref<AiProfile[]>([])
const providerDefaults = ref<AiProviderDefaults[]>([])
const selectedId = ref('')
const draft = ref<Draft>(blankDraft())
const apiKey = ref('')
const clearApiKey = ref(false)
const loading = ref(true)
const saving = ref(false)
const testing = ref(false)
const deleting = ref(false)
const confirmingDelete = ref(false)
const error = ref('')
const message = ref('')

const isNew = ref(true)
const selectedProfile = computed(() => profiles.value.find((profile) => profile.id === selectedId.value))
const keyStatusText = computed(() => {
  if (isNew.value) return '保存后才会写入当前配置。'
  return selectedProfile.value?.keyConfigured ? '当前配置已保存 key。' : '当前配置尚未保存 key。'
})

function blankDraft(defaults = providerDefaults.value[0]): Draft {
  return {
    id: '',
    label: '',
    provider: defaults?.id ?? '',
    model: defaults?.defaultModel ?? '',
    endpoint: defaults?.defaultEndpoint ?? '',
    maxTextChars: 1_000_000,
  }
}

function selectProfile(id: string): void {
  selectedId.value = id
  const profile = profiles.value.find((item) => item.id === id)
  isNew.value = !profile
  draft.value = profile
    ? {
        id: profile.id,
        label: profile.label,
        provider: profile.provider,
        model: profile.model,
        endpoint: profile.endpoint,
        maxTextChars: profile.maxTextChars ?? 1_000_000,
      }
    : blankDraft(providerDefaults.value[0])
  apiKey.value = ''
  clearApiKey.value = false
}

function onSelectionChange(event: Event): void {
  selectProfile((event.target as HTMLSelectElement).value)
}

function newProfile(): void {
  selectedId.value = ''
  isNew.value = true
  draft.value = blankDraft(providerDefaults.value[0])
  apiKey.value = ''
  clearApiKey.value = false
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const settings = await aiApi.settings()
    profiles.value = settings.profiles
    providerDefaults.value = settings.providers
    if (selectedId.value && profiles.value.some((profile) => profile.id === selectedId.value)) {
      selectProfile(selectedId.value)
    } else if (profiles.value.length) {
      selectProfile(profiles.value[0].id)
    } else {
      newProfile()
    }
  } catch (e) {
    error.value = '读取 AI 设置失败：' + (e as Error).message
  } finally {
    loading.value = false
  }
}

async function save(): Promise<boolean> {
  if (!draft.value.id.trim()) {
    error.value = '请填写配置 ID'
    return false
  }
  if (!draft.value.model.trim()) {
    error.value = '请填写模型名称'
    return false
  }
  if (!draft.value.endpoint.trim()) {
    error.value = '请填写 endpoint'
    return false
  }
  const value = Number(draft.value.maxTextChars)
  if (!Number.isInteger(value) || value < 1_000 || value > 1_000_000) {
    error.value = '单次文本上限必须是 1,000 到 1,000,000 之间的整数'
    return false
  }
  draft.value.maxTextChars = value
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const saved = await aiApi.saveProfile({
      id: draft.value.id.trim(),
      label: draft.value.label.trim(),
      provider: draft.value.provider,
      model: draft.value.model.trim(),
      endpoint: draft.value.endpoint.trim(),
      maxTextChars: draft.value.maxTextChars,
      apiKey: apiKey.value.trim() || undefined,
      clearApiKey: clearApiKey.value,
    })
    selectedId.value = saved.id
    message.value = '模型配置已保存'
    await load()
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
    const result = await aiApi.testProfile(selectedId.value)
    if (result.success) message.value = result.message
    else error.value = result.message
  } catch (e) {
    error.value = 'AI 连接测试失败：' + (e as Error).message
  } finally {
    testing.value = false
  }
}

async function deleteProfile(): Promise<void> {
  if (!selectedProfile.value) return
  deleting.value = true
  error.value = ''
  message.value = ''
  try {
    await aiApi.deleteProfile(selectedProfile.value.id)
    selectedId.value = ''
    message.value = '模型配置已删除'
    await load()
  } catch (e) {
    error.value = '删除失败：' + (e as Error).message
  } finally {
    deleting.value = false
    confirmingDelete.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="ai-settings">
    <div class="settings-heading">
      <h3>AI 能力</h3>
      <p class="muted">集中管理可选的模型配置；Graphify、标签和其他插件各自选择使用哪一条配置。</p>
    </div>

    <p v-if="loading" class="muted">正在读取设置…</p>
    <template v-else>
      <div class="profile-picker">
        <label>
          <span>模型配置</span>
          <select :value="selectedId" @change="onSelectionChange">
            <option value="">新配置</option>
            <option v-for="profile in profiles" :key="profile.id" :value="profile.id">
              {{ profile.label || profile.id }} · {{ profile.model }}
            </option>
          </select>
        </label>
        <button type="button" class="secondary" @click="newProfile">新增配置</button>
        <button
          type="button"
          class="secondary danger"
          :disabled="!selectedProfile || saving || testing || deleting"
          @click="confirmingDelete = true"
        >
          删除
        </button>
      </div>

      <form class="settings-form" @submit.prevent="save">
        <div class="two-columns">
          <label>
            <span>配置 ID</span>
            <input v-model="draft.id" :disabled="!isNew" autocomplete="off" placeholder="例如 default-chat" />
          </label>
          <label>
            <span>显示名称</span>
            <input v-model="draft.label" autocomplete="off" placeholder="例如 默认对话" />
          </label>
        </div>

        <label>
          <span>Provider</span>
          <select v-model="draft.provider">
            <option v-for="item in providerDefaults" :key="item.id" :value="item.id">
              {{ item.label }}
            </option>
          </select>
        </label>

        <label>
          <span>模型</span>
          <input v-model="draft.model" autocomplete="off" placeholder="模型 ID" />
        </label>

        <label>
          <span>Endpoint</span>
          <input v-model="draft.endpoint" autocomplete="off" placeholder="https://…" />
        </label>

        <label>
          <span>单次文本上限（字符）</span>
          <input v-model.number="draft.maxTextChars" type="number" min="1000" max="1000000" step="1000" />
          <small class="muted">按字符计算，不是 token。</small>
        </label>

        <p v-if="draft.provider === 'opencode-go'" class="muted">
          OpenCode Go 使用 Chat Completions；endpoint 可覆盖默认地址。
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
          <small class="muted">{{ keyStatusText }} key 只保存在本机 AI 插件目录。</small>
        </label>

        <label v-if="selectedProfile?.keyConfigured && !isNew" class="clear-key">
          <input v-model="clearApiKey" type="checkbox" />
          <span>清除当前配置已保存的 key</span>
        </label>

        <div class="form-actions">
          <button type="submit" :disabled="saving || testing || deleting">
            {{ saving ? '保存中…' : '保存配置' }}
          </button>
          <button type="button" class="secondary" :disabled="saving || testing || deleting" @click="testConnection">
            {{ testing ? '正在测试…' : '保存并测试' }}
          </button>
        </div>
      </form>

      <div class="privacy-note">
        <strong>数据发送说明</strong>
        <p>
          插件只在执行时读取所选配置。API key 不通过配置列表返回；测试连接会发送合成文本或测试搜索词。
        </p>
      </div>
    </template>

    <p v-if="message" class="feedback success">{{ message }}</p>
    <p v-if="error" class="feedback error">{{ error }}</p>

    <ConfirmDialog
      v-if="confirmingDelete"
      :open="true"
      title="删除模型配置？"
      message="使用这个配置的业务插件下次执行时会提示配置不存在，需要重新选择。"
      confirm-label="删除配置"
      :busy="deleting"
      @cancel="confirmingDelete = false"
      @confirm="deleteProfile"
    />
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

.profile-picker {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: end;
  gap: 8px;
  max-width: 620px;
}

.profile-picker label,
.settings-form label {
  display: grid;
  gap: 5px;
  font-size: 13px;
}

.two-columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.settings-form input:not([type='checkbox']),
.settings-form select,
.profile-picker select {
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

.secondary {
  min-height: 34px;
  padding: 6px 10px;
  color: var(--c-text);
  background: var(--c-surface, #fff);
  border: 1px solid var(--c-border);
  border-radius: 6px;
  cursor: pointer;
}

.danger {
  color: var(--c-danger, #c33);
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

.form-actions button:disabled,
.secondary:disabled {
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

@media (max-width: 640px) {
  .profile-picker,
  .two-columns {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
