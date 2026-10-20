<script setup lang="ts">
import { ref } from 'vue'
import { host } from '@thisnote/plugin-api'
import { stateResetApi } from '../api/stateResetApi'

const busy = ref(false)
const confirming = ref(false)
const error = ref('')

async function reset() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await stateResetApi.reset()
    confirming.value = false
    host().notify('插件状态重置已提交', 'success')
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
    <p>重置活动插件状态；停用插件不启动、不清理：</p>
    <ul>
      <li>收藏：重置收藏文件</li>
      <li>记忆周期：重置排期文件</li>
      <li>标签：清空标签文件</li>
      <li>Graphify：删除图谱、投影语料和索引状态</li>
      <li>百度网盘同步：重置同步基线（授权凭据保留）</li>
      <li>卡片导入 / 附件导入：无状态文件</li>
      <li>笔记：删除卡片 md、附件文件与自定义分组，清空索引并重建默认分组</li>
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

.actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}
</style>
