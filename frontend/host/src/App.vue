<script setup lang="ts">
import { onMounted } from 'vue'
import { useUiStore } from '@/stores/ui'
import { useNoteStore } from '@/stores/notes'
import { waitForBackend, backendOnline, backendMessage } from '@/core/http'
import ToastHost from '@/components/ToastHost.vue'

const ui = useUiStore()
const nodes = useNoteStore()

onMounted(async () => {
  // main.ts 在挂载应用之前已经等过一次后端；已经连上就别再等 —— 否则会白打一次 /api/health
  const ok = backendOnline.value || (await waitForBackend(20000))
  if (ok) {
    await nodes.refreshAll()
  }
})
</script>

<template>
  <div class="app-root">
    <RouterView v-if="backendOnline" />
    <div v-else class="boot-screen">
      <div class="boot-mark">韧</div>
      <h2>thisnode 韧知笔记</h2>
      <p class="muted">{{ backendMessage }}</p>
      <p class="small muted">
        本地后端默认监听 <span class="mono">127.0.0.1:45917</span>。请确认 Java 21+ 可用，或稍候重试。
      </p>
      <button class="btn primary" @click="waitForBackend(5000)">重试连接</button>
    </div>

    <ToastHost />
  </div>
</template>

<style scoped>
.app-root {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.boot-screen {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  text-align: center;
  padding: var(--space-6);
}
.boot-screen h2 {
  margin: 0;
  font-weight: 600;
}
.boot-mark {
  width: 64px;
  height: 64px;
  border-radius: 18px;
  background: var(--accent);
  color: #fff;
  font-size: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--shadow);
}
</style>
