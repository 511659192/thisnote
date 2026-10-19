<script setup lang="ts">
import { ref } from 'vue'
import { host } from '@thisnote/plugin-api'
import { stateResetApi, type PluginStateResetReport, type PluginResetStatus } from '../api/stateResetApi'

const busy = ref(false)
const confirming = ref(false)
const report = ref<PluginStateResetReport | null>(null)
const error = ref('')

const statusLabels: Record<PluginResetStatus, string> = {
  RESET: '已重置',
  SKIPPED: '无需重置',
  FAILED: '失败',
}

async function reset() {
  if (busy.value) return
  busy.value = true
  report.value = null
  error.value = ''
  try {
    const resetReport = await stateResetApi.reset()
    report.value = resetReport
    confirming.value = false
    host().notify(resetReport.ok ? '插件状态已重置' : '插件状态重置部分失败',
      resetReport.ok ? 'success' : 'error')
  } catch (e) {
    error.value = (e as Error).message
    host().notify(`重置失败：${(e as Error).message}`, 'error')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="state-reset-settings">
    <h3>插件状态重置</h3>
    <p>重置全部已安装插件；活动插件执行自身清理，停用插件按插件清单清理状态文件：</p>
    <ul>
      <li>收藏：重置收藏文件</li>
      <li>记忆周期：重置排期文件</li>
      <li>标签：清空标签文件</li>
      <li>Graphify：删除图谱、投影语料和索引状态</li>
      <li>百度网盘同步：重置同步基线（授权凭据保留）</li>
      <li>卡片导入 / 附件导入：无状态文件</li>
      <li>笔记：清空笔记/卡片/附件索引（卡片 md 与附件文件保留）</li>
      <li>AI：模型配置与 API key 保留</li>
    </ul>
    <p class="warn">此操作不可撤销。</p>
    <div class="actions">
      <button v-if="!confirming" class="btn" :disabled="busy" @click="confirming = true">重置插件状态…</button>
      <template v-else>
        <button class="btn primary" :disabled="busy" @click="reset">{{ busy ? '重置中…' : '确认重置' }}</button>
        <button class="btn ghost" :disabled="busy" @click="confirming = false">取消</button>
      </template>
    </div>
    <p v-if="error" class="small error">{{ error }}</p>
    <ul v-if="report" class="reset-results">
      <li v-for="item in report.results" :key="item.pluginId">
        <code>{{ item.pluginId }}</code>
        <span :class="['status', item.status.toLowerCase()]">{{ statusLabels[item.status] }}</span>
        <span class="small muted">{{ item.message }}</span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.state-reset-settings {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 760px;
}

h3 {
  margin: 4px 0 0;
}

p,
ul {
  margin: 0;
}

ul {
  padding-left: 20px;
}

li + li {
  margin-top: 4px;
}

.warn {
  color: var(--warning);
}

.error {
  color: var(--danger);
}

.reset-results {
  list-style: none;
  padding: 0;
}

.reset-results li {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
}

.status {
  font-size: 12px;
}

.status.reset {
  color: var(--success);
}

.status.skipped {
  color: var(--c-text-3);
}

.status.failed {
  color: var(--danger);
}

.actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}
</style>
