<script setup lang="ts">
import { useUiStore } from '@/stores/ui'
import { shellState, toggleRightPanel, toggleTheme } from '@thisnode/plugin-api'
import PluginNavRail from '@/components/PluginNavRail.vue'

const ui = useUiStore()
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <button
        class="btn ghost icon"
        :title="ui.sidebarCollapsed ? '展开侧栏 (Ctrl+\\)' : '收起侧栏 (Ctrl+\\)'"
        @click="ui.toggleSidebar()"
      >
        ☰
      </button>
      <div class="brand" @click="$router.push('/notes')">
        <span class="brand-mark">韧</span>
        <span class="brand-name">thisnode</span>
      </div>
      <div class="spacer"></div>
      <button
        class="btn ghost icon"
        :class="{ active: shellState.rightPanelOpen }"
        :title="shellState.rightPanelOpen ? '隐藏右侧面板（反向链接）' : '显示右侧面板（反向链接）'"
        @click="toggleRightPanel()"
      >
        ⊟
      </button>
      <button
        class="btn ghost icon"
        :title="shellState.theme === 'dark' ? '切换到浅色' : '切换到深色'"
        @click="toggleTheme()"
      >
        {{ shellState.theme === 'dark' ? '☀' : '☾' }}
      </button>
      <button
        class="btn ghost icon"
        :class="{ active: $route.name === 'settings' }"
        title="设置"
        @click="$router.push('/settings')"
      >
        ⚙
      </button>
    </header>

    <div class="body">
      <!-- 侧栏 = 业务域登记的面板（notes 列表）+ 宿主自有 nav 轨 -->
      <PluginNavRail v-show="!ui.sidebarCollapsed" />
      <main class="main">
        <RouterView />
      </main>
    </div>

  </div>
</template>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.topbar {
  height: var(--topbar-h);
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0 var(--space-3);
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-border);
  z-index: 30;
}

.brand {
  display: flex;
  align-items: center;
  gap: 7px;
  cursor: pointer;
  user-select: none;
  padding-right: var(--space-2);
}
.brand-mark {
  width: 24px;
  height: 24px;
  border-radius: 7px;
  background: var(--accent);
  color: #fff;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.brand-name {
  font-weight: 600;
  letter-spacing: 0.2px;
}

.body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--c-bg);
  overflow: hidden;
}
</style>
