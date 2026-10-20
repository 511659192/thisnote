<script setup lang="ts">
import { watchEffect } from 'vue'
import { useRouter } from 'vue-router'
import { pluginRoutes } from '@thisnote/plugin-api'

/**
 * 装载占位页：业务路由（/notes、/notes/:id…）由业务域微应用在 mount 时登记，
 * 就绪前用户落在的这一页在路由注册完成后自动接过去 —— 保留原始 fullPath，
 * 深链接（#/notes/abc）装载完成后仍能精确落回。
 */
const router = useRouter()

watchEffect(() => {
  const routes = pluginRoutes()
  if (routes.length === 0) return
  const current = router.currentRoute.value
  const first = routes.find((route) => route.path === '/notes') ?? routes[0]
  void router.replace(current.fullPath === '/' ? first.path : current.fullPath)
})
</script>

<template>
  <div class="shell-home">
    <span class="dot"></span>
    <span>正在装载业务域…</span>
  </div>
</template>

<style scoped>
.shell-home {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  color: var(--c-text-3);
  font-size: 13px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--accent);
  animation: pulse 1s ease-in-out infinite;
}
@keyframes pulse {
  50% {
    opacity: 0.3;
  }
}
</style>
