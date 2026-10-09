<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { activeNavEntries, navBadges, refreshPluginAvailability, sidebarPanels_ } from '@thisnode/plugin-api'

const route = useRoute()
const router = useRouter()

/**
 * 侧栏的插件入口（原来是硬编码的「复习」按钮 —— 那等于让核心认识记忆曲线插件）。
 * 现在只面向注册器：入口由插件登记，徽标数量由插件自己算，宿主一个具体插件都不认识。
 */
const pluginNav = computed(() => activeNavEntries())
const badges = ref<Record<string, number>>({})

async function refreshPluginBadges() {
  /** 不给「你正在看的那个插件页」取徽标：那份数据页面自己刚取过一遍。 */
  const elsewhere = pluginNav.value.filter((entry) => route.path !== entry.to)
  badges.value = await navBadges(elsewhere)
}
async function refreshPlugins() {
  await refreshPluginAvailability()
  await refreshPluginBadges()
}

watch(() => route.fullPath, () => void refreshPluginBadges())
onMounted(() => {
  void refreshPlugins()
  window.addEventListener('focus', onFocus)
  document.addEventListener('visibilitychange', onVisibility)
})
onBeforeUnmount(() => {
  window.removeEventListener('focus', onFocus)
  document.removeEventListener('visibilitychange', onVisibility)
})
function onFocus() {
  void refreshPlugins()
}
function onVisibility() {
  if (document.visibilityState === 'visible') void refreshPlugins()
}
</script>

<template>
  <aside class="sidebar">
    <!-- 业务域登记的侧栏面板（笔记列表面板等），堆叠渲染 -->
    <template v-for="panel in sidebarPanels_()" :key="panel.pluginId">
      <component :is="panel.component" />
    </template>

    <!-- 插件 nav 轨：宿主唯一自有的侧栏内容 -->
    <nav class="section">
      <div class="group-title">插件</div>
      <button
        v-for="entry in pluginNav"
        :key="entry.pluginId"
        class="nav-item plugin-entry"
        :class="{ active: route.path === entry.to }"
        @click="$router.push(entry.to)"
      >
        <span class="ico">{{ entry.glyph }}</span>
        <span class="label">{{ entry.label }}</span>
        <span v-if="(badges[entry.pluginId] ?? 0) > 0" class="count">
          {{ (badges[entry.pluginId] ?? 0) > 50 ? '50+' : badges[entry.pluginId] }}
        </span>
      </button>
      <div v-if="pluginNav.length === 0" class="empty">暂无插件入口</div>
    </nav>
  </aside>
</template>

<style scoped>
.sidebar {
  width: var(--sidebar-w);
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  background: var(--c-surface);
  border-right: 1px solid var(--c-border);
  min-height: 0;
}
.section {
  padding: var(--space-2) var(--space-2) var(--space-3);
  border-top: 1px solid var(--c-border);
}
.group-title {
  padding: var(--space-2) var(--space-2) 4px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.5px;
  text-transform: uppercase;
  color: var(--c-text-3);
}
.nav-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 5px var(--space-2);
  border-radius: var(--radius-sm);
  color: var(--c-text-2);
  font-size: 13px;
  text-align: left;
}
.nav-item:hover {
  background: var(--c-surface-3);
}
.nav-item.active {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 500;
}
.nav-item .ico {
  width: 16px;
  text-align: center;
  opacity: 0.8;
}
.nav-item .label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nav-item .count {
  font-size: 11px;
  color: var(--c-text-3);
}
.empty {
  padding: 2px var(--space-2);
  font-size: 12px;
  color: var(--c-text-3);
}
</style>
