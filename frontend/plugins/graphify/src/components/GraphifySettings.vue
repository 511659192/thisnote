<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { graphifyApi, type GraphifySettings, type GraphifyStatus } from '../api/graphifyApi'

const settings = ref<GraphifySettings>({
  aiProfileId: '',
  provider: '',
  model: '',
  executablePath: '',
  keyConfigured: false,
  profiles: [],
})
const status = ref<GraphifyStatus | null>(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const message = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    settings.value = await graphifyApi.settings()
    status.value = await graphifyApi.status()
  } catch (e) {
    error.value = '读取 Graphify 设置失败：' + (e as Error).message
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    settings.value = await graphifyApi.saveSettings({
      executablePath: settings.value.executablePath.trim(),
      aiProfileId: settings.value.aiProfileId,
    })
    message.value = 'Graphify 设置已保存'
  } catch (e) {
    error.value = '保存失败：' + (e as Error).message
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="graphify-settings">
    <div class="settings-heading">
      <h3>Graphify 知识图谱</h3>
      <p class="muted">模型、视觉能力与 API key 由 AI 能力插件统一提供；这里选择 Graphify 使用的配置。</p>
    </div>

    <p v-if="loading" class="muted">正在读取设置…</p>
    <template v-else>
      <dl class="ai-summary">
        <div>
          <dt>Provider</dt>
          <dd>{{ settings.provider || '未配置' }}</dd>
        </div>
        <div>
          <dt>模型</dt>
          <dd>{{ settings.model || '未配置' }}</dd>
        </div>
        <div>
          <dt>API key</dt>
          <dd>{{ settings.keyConfigured ? '已配置' : '未配置' }}</dd>
        </div>
      </dl>

      <form class="settings-form" @submit.prevent="save">
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
          <span>Graphify 可执行文件路径</span>
          <input
            v-model="settings.executablePath"
            placeholder="留空时从 thisnote 的 PATH 查找 graphify"
            autocomplete="off"
          />
        </label>

        <div class="form-actions">
          <button type="submit" :disabled="saving || !settings.aiProfileId">
            {{ saving ? '保存中…' : '保存设置' }}
          </button>
        </div>
      </form>

      <div class="privacy-note">
        <strong>数据发送说明</strong>
        <p>
          Graphify 索引时，AI 插件会把笔记标题、卡片正文和被引用的图片发送给所选配置的 Provider。
          测试连接与模型配置请到 AI 能力插件中操作。
        </p>
        <p>
          更换模型后如需重新抽取已有内容，请在图谱页手动执行强制重建；这会绕过 Graphify 缓存并可能产生 API 费用。
        </p>
      </div>
    </template>

    <p v-if="message" class="feedback success">{{ message }}</p>
    <p v-if="error" class="feedback error">{{ error }}</p>
    <p v-if="status?.error" class="feedback error">最近索引错误：{{ status.error }}</p>
  </section>
</template>

<style scoped>
.graphify-settings {
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

.ai-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 26px;
  margin: 0;
  padding: 10px 12px;
  background: var(--c-surface-2);
  border-radius: 6px;
}

.ai-summary div {
  display: grid;
  gap: 2px;
}

.ai-summary dt {
  color: var(--c-text-muted, #8b9098);
  font-size: 10px;
}

.ai-summary dd {
  margin: 0;
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

.settings-form input,
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
