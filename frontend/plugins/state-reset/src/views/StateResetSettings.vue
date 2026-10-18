<script setup lang="ts">
import { ref } from 'vue'
import { host } from '@thisnote/plugin-api'
import { stateResetApi } from '../api/stateResetApi'

const busy = ref(false)
const confirming = ref(false)
const result = ref('')

async function reset() {
  if (busy.value) return
  busy.value = true
  result.value = ''
  try {
    await stateResetApi.reset()
    confirming.value = false
    result.value = '已发出重置事件：收藏、记忆周期排期、网盘同步基线已重置，笔记/卡片/附件索引已清空。'
    host().notify('插件状态已重置', 'success')
  } catch (e) {
    result.value = `重置失败：${(e as Error).message}`
    host().notify(`重置失败：${(e as Error).message}`, 'error')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="state-reset-settings">
    <h3>插件状态重置</h3>
    <p>发出一次初始化事件，各插件清理并重建自己的状态：</p>
    <ul>
      <li>收藏：重置收藏文件</li>
      <li>记忆周期：重置排期文件</li>
      <li>百度网盘同步：重置同步基线（授权凭据保留）</li>
      <li>附件导入：无状态文件（只记日志）</li>
      <li>笔记：清空笔记/卡片/附件索引（卡片 md 与附件文件保留）</li>
    </ul>
    <p class="warn">此操作不可撤销。</p>
    <div class="actions">
      <button v-if="!confirming" class="btn" :disabled="busy" @click="confirming = true">重置插件状态…</button>
      <template v-else>
        <button class="btn primary" :disabled="busy" @click="reset">{{ busy ? '重置中…' : '确认重置' }}</button>
        <button class="btn ghost" :disabled="busy" @click="confirming = false">取消</button>
      </template>
    </div>
    <p v-if="result" class="small muted">{{ result }}</p>
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

.actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}
</style>
