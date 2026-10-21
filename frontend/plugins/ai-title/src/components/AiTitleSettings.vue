<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { aiTitleApi, type AiTitleSettings } from '../api/aiTitleApi'

const settings = ref<AiTitleSettings>({
  aiProfileId: '',
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
    settings.value = await aiTitleApi.settings()
  } catch (e) {
    error.value = '读取 AI 标题设置失败：' + (e as Error).message
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    settings.value = await aiTitleApi.saveSettings(settings.value.aiProfileId)
    message.value = 'AI 标题设置已保存'
  } catch (e) {
    error.value = '保存失败：' + (e as Error).message
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="title-settings">
    <div class="settings-heading">
      <h3>AI 标题</h3>
      <p class="muted">
        在笔记标题右侧点击「AI 标题」，结合笔记内容与 Graphify 图谱生成 1–3 个候选；选择候选后才会替换标题。
      </p>
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
.title-settings {
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
