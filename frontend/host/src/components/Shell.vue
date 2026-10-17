<script setup lang="ts">
import { useUiStore } from '@/stores/ui'
import { sidebarPanels_, topbarSearches_ } from '@thisnode/plugin-api'

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
        <span class="brand-name">thisnote</span>
      </div>
      <!-- 顶栏搜索插槽：插件交付的模糊查询（宿主只渲染容器） -->
      <template v-for="w in topbarSearches_()" :key="w.pluginId">
        <component :is="w.component" />
      </template>
      <div class="spacer"></div>
      <button
        class="btn ghost icon"
        :title="ui.theme === 'dark' ? '切换到浅色' : '切换到深色'"
        @click="ui.toggleTheme()"
      >
        {{ ui.theme === 'dark' ? '☀' : '☾' }}
      </button>
      <button
        class="btn ghost icon"
        :class="{ active: $route.name === 'settings' }"
        :title="$route.name === 'settings' ? '关闭设置，返回笔记' : '设置'"
        @click="$route.name === 'settings' ? $router.push('/notes') : $router.push('/settings')"
      >
        ⚙
      </button>
    </header>

    <div class="body">
      <!-- 侧栏容器：内容全部来自登记的业务面板（notes 列表面板内含插件入口行） -->
      <aside class="sidebar">
        <template v-for="panel in sidebarPanels_()" :key="panel.pluginId">
          <component :is="panel.component" />
        </template>
      </aside>
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
