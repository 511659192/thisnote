<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { aiSummaryApi, type AiSummarySettings } from '../api/aiSummaryApi'

const settings = ref<AiSummarySettings>({
  aiProfileId: '',
  autoEnabled: true,
  fontSize: 15,
  profiles: [],
  profilesError: '',
})
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const message = ref('')

const selectedProfile = computed(() =>
  settings.value.profiles.find((profile) => profile.id === settings.value.aiProfileId),
)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    settings.value = await aiSummaryApi.settings()
  } catch (e) {
    error.value = '读取 AI 总结设置失败：' + (e as Error).message
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    settings.value = await aiSummaryApi.saveSettings(
      settings.value.aiProfileId,
      settings.value.autoEnabled,
      settings.value.fontSize,
    )
    message.value = 'AI 总结设置已保存'
  } catch (e) {
    error.value = '保存失败：' + (e as Error).message
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="summary-settings">
    <div class="settings-heading">
      <h3>AI 总结</h3>
      <p class="muted">总结会结合 Graphify 知识图谱和笔记正文生成；模型与 API key 由 AI 能力插件统一管理。</p>
    </div>

    <p v-if="loading" class="muted">正在读取设置…</p>
    <form v-else class="settings-form" @submit.prevent="save">
      <label>
        <span>AI 模型配置</span>
        <select v-model="settings.aiProfileId">
          <option value="">请选择 completion 配置</option>
          <option v-for="profile in settings.profiles" :key="profile.id" :value="profile.id">
            {{ profile.label || profile.id }} · {{ profile.model }}
          </option>
        </select>
        <small v-if="!settings.profiles.length" class="muted">
          AI 能力插件中还没有 completion 配置。
        </small>
      </label>

      <label>
        <span>总结字号</span>
        <div class="font-size-row">
          <input v-model.number="settings.fontSize" type="number" min="10" max="32" step="1" />
          <span>px</span>
        </div>
        <small class="muted">控制 AI 总结正文、提示与错误文字的字号。</small>
      </label>

      <label class="switch-row">
        <input v-model="settings.autoEnabled" type="checkbox" />
        <span>允许自动生成 AI 总结</span>
        <small class="muted">关闭后仅在点击笔记页的 ↻ 图标时生成。</small>
      </label>

      <dl v-if="selectedProfile" class="profile-summary">
        <div>
          <dt>Provider</dt>
          <dd>{{ selectedProfile.provider }}</dd>
        </div>
        <div>
          <dt>API key</dt>
          <dd>{{ selectedProfile.keyConfigured ? '已配置' : '未配置' }}</dd>
        </div>
      </dl>

      <div class="form-actions">
        <button type="submit" :disabled="saving || !settings.aiProfileId">
          {{ saving ? '保存中…' : '保存设置' }}
        </button>
      </div>
    </form>

    <p v-if="message" class="feedback success">{{ message }}</p>
    <p v-if="settings.profilesError" class="feedback error">{{ settings.profilesError }}</p>
    <p v-if="error" class="feedback error">{{ error }}</p>
  </section>
</template>

<style scoped>
.summary-settings {
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

.settings-form .switch-row {
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 5px 8px;
}

.switch-row input {
  width: 16px;
  height: 16px;
  margin: 0;
}

.switch-row small {
  grid-column: 2;
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

.profile-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 24px;
  margin: 0;
  padding: 9px 11px;
  background: var(--c-surface-2);
  border-radius: 6px;
}

.profile-summary div {
  display: grid;
  gap: 2px;
}

.profile-summary dt {
  color: var(--c-text-muted, #8b9098);
  font-size: 10px;
}

.profile-summary dd {
  margin: 0;
  font-size: 12px;
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
