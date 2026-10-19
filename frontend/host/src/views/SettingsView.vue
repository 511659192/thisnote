<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ConfirmDialog,
  pluginApi,
  pluginSettings,
  refreshPluginAvailability,
  request,
  type PluginInfo,
} from '@thisnote/plugin-api'
import {
  loadPluginFrontend,
  unloadPluginFrontend,
  reloadPluginFrontend,
} from '../plugins/loader'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const router = useRouter()
const busy = ref('')
const confirmation = ref<{
  title: string
  message: string
  confirmLabel: string
  action: () => Promise<void>
} | null>(null)

// ---- 插件管理（列表来自后端注册表；每个插件可启停、可配置）
const plugins = ref<PluginInfo[]>([])
const openPlugins = ref<Set<string>>(new Set())
let defaultPluginPanelsOpened = false
const notesRootDir = ref('')
const activeTab = ref<'storage' | 'plugins'>('storage')

interface NotesRootSettings {
  rootDir: string
}

async function refreshPlugins() {
  try {
    plugins.value = await pluginApi.list()
    if (!defaultPluginPanelsOpened) {
      const configured = plugins.value
        .filter((plugin) => Boolean(pluginSettings(plugin.id)))
        .map((plugin) => plugin.id)
      if (configured.length) {
        openPlugins.value = new Set(configured)
        defaultPluginPanelsOpened = true
      }
    }
  } catch {
    plugins.value = []
  }
}

async function refreshNotesRoot() {
  try {
    const settings = await request<NotesRootSettings>('GET', '/api/notes/settings/get')
    notesRootDir.value = settings.rootDir
  } catch (e) {
    ui.toast(`读取笔记根目录失败：${(e as Error).message}`, 'error')
  }
}

async function applyPluginState(plugin: PluginInfo, next: boolean) {
  busy.value = `${next ? '正在启用' : '正在停用'}「${plugin.title}」`
  try {
    const updated = await pluginApi.setActive(plugin.id, next)
    plugins.value = plugins.value.map((p) => (p.id === updated.id ? updated : p))
    // 强制刷新可用性快照（跳过新鲜期）：侧栏入口与卡片工具要跟着消失/恢复，否则停用就不闭环
    await refreshPluginAvailability(true)
    // qiankun 微应用的真实生命周期：停用即卸载（插槽注销 + 路由拆掉），
    // 启用即装载 —— 不再依赖整页刷新。
    if (next) {
      await loadPluginFrontend(plugin.id, router)
    } else {
      await unloadPluginFrontend(plugin.id, router)
    }
    ui.toast(`「${plugin.title}」已${next ? '启用' : '停用'}`, 'success')
  } catch (e) {
    ui.toast(`操作失败：${(e as Error).message}`, 'error')
  } finally {
    busy.value = ''
  }
}

function askConfirmation(title: string, message: string, confirmLabel: string, action: () => Promise<void>) {
  confirmation.value = { title, message, confirmLabel, action }
}

function cancelConfirmation() {
  if (!busy.value) confirmation.value = null
}

async function confirmAction() {
  const action = confirmation.value?.action
  if (!action || busy.value) return
  await action()
  confirmation.value = null
}

function togglePlugin(plugin: PluginInfo) {
  const next = !plugin.active
  if (next) {
    void applyPluginState(plugin, true)
    return
  }
  askConfirmation(
    `停用「${plugin.title}」？`,
    '停用后该插件自己的端点会被拒绝；管理面仍可访问，之后可以重新启用。',
    '停用插件',
    () => applyPluginState(plugin, false),
  )
}

/**
 * 重载插件：换了 plugins/ 里的 jar（或开发模式改了插件代码）之后让它生效。
 * SBP 会**重建插件的类加载器与 Spring 子容器**，换掉磁盘上的 jar 当场生效。
 */
async function applyReloadPlugin(plugin: PluginInfo) {
  busy.value = `正在重载「${plugin.title}」`
  try {
    const updated = await pluginApi.reload(plugin.id)
    plugins.value = plugins.value.map((p) => (p.id === updated.id ? updated : p))
    await refreshPluginAvailability(true)
    // 插件的类加载器与容器已在后端重建；界面侧同步重载微应用（卸载 + 装载），
    // 换掉磁盘上的 jar 当场生效。
    await reloadPluginFrontend(plugin.id, router)
    ui.toast(`「${plugin.title}」已重载（v${updated.version}）`, 'success')
  } catch (e) {
    ui.toast(`重载失败：${(e as Error).message}`, 'error')
  } finally {
    busy.value = ''
  }
}

function reloadPlugin(plugin: PluginInfo) {
  const hint = plugin.reloadAvailable
    ? `\n\n磁盘上的版本是 v${plugin.diskVersion}，运行中的是 v${plugin.version}。`
    : ''
  askConfirmation(
    `重载「${plugin.title}」？`,
    `${hint ? hint.trimStart() + '\n\n' : ''}它会先停掉再按磁盘上的 jar 重启，进行中的请求会失败。`,
    '重载插件',
    () => applyReloadPlugin(plugin),
  )
}

function isPluginOpen(pluginId: string): boolean {
  return openPlugins.value.has(pluginId)
}

function togglePluginPanel(plugin: PluginInfo) {
  const next = new Set(openPlugins.value)
  if (next.has(plugin.id)) {
    next.delete(plugin.id)
  } else {
    next.add(plugin.id)
  }
  openPlugins.value = next
}

function healthClass(plugin: PluginInfo): string {
  return `health-${plugin.health.status}`
}

async function refresh() {
  await Promise.all([refreshPlugins(), refreshNotesRoot()])
}

onMounted(async () => {
  await refresh()
})
</script>

<template>
  <div class="settings">
    <header class="tabs">
      <button class="btn sm ghost" title="返回笔记" @click="$router.push('/notes')">← 返回笔记</button>
      <div class="tab-title">设置</div>
      <div class="spacer"></div>
      <button class="btn sm" :disabled="!!busy" @click="refresh">刷新</button>
    </header>

    <nav class="settings-tabs" role="tablist" aria-label="设置分类">
      <button
        id="settings-tab-storage"
        class="settings-tab"
        :class="{ active: activeTab === 'storage' }"
        type="button"
        role="tab"
        :aria-selected="activeTab === 'storage'"
        aria-controls="settings-panel-storage"
        @click="activeTab = 'storage'"
      >
        笔记存储
      </button>
      <button
        id="settings-tab-plugins"
        class="settings-tab"
        :class="{ active: activeTab === 'plugins' }"
        type="button"
        role="tab"
        :aria-selected="activeTab === 'plugins'"
        aria-controls="settings-panel-plugins"
        @click="activeTab = 'plugins'"
      >
        插件管理
      </button>
    </nav>

    <div class="body">
      <div v-if="busy" class="busy-bar">{{ busy }}…</div>

      <section
        id="settings-panel-storage"
        class="panel"
        role="tabpanel"
        aria-labelledby="settings-tab-storage"
        v-show="activeTab === 'storage'"
      >
        <h3>笔记存储</h3>
        <p class="small muted">笔记根目录由服务配置确定。</p>
        <p class="small muted notes-root-active">当前笔记根目录：<span class="mono">{{ notesRootDir }}</span></p>
      </section>

      <!-- ------------------------------ plugins ------------------------------ -->
      <section
        id="settings-panel-plugins"
        class="panel"
        role="tabpanel"
        aria-labelledby="settings-tab-plugins"
        v-show="activeTab === 'plugins'"
      >
        <h3>已安装的插件</h3>
        <p class="small muted">
          插件分两类：<b>卡片插件</b>给卡片右上角加工具（如记忆周期）；<b>服务插件</b>提供整库范围的能力。
          百度网盘同步的入口在侧栏「复习」之下。停用后该插件自己的端点会被拒绝，侧栏入口一并下线，
          随时能再打开。
        </p>

        <div v-if="!plugins.length" class="small muted">没有已注册的插件。</div>

        <div v-if="plugins.length" class="plugin-list">
          <div v-for="plugin in plugins" :key="plugin.id" class="plugin-card">
            <div class="plugin-head">
              <!-- 只有「不可停用」的插件需要一枚徽标；其余没有分类标签（分类由插槽事实决定，不靠声明） -->
              <span v-if="plugin.undeactivatable" class="kind kind-core">核心</span>
              <b>{{ plugin.title }}</b>
              <span class="small muted mono">v{{ plugin.version }}</span>
              <span class="small" :class="healthClass(plugin)">● {{ plugin.health.message }}</span>
              <div class="spacer"></div>
              <!-- 换了磁盘上的 jar（版本不同）时给一条明确提示；重载按钮总是可用 —— 开发模式下
                   换的是同一版本的 target/classes，没有它就只能重启应用。 -->
              <span v-if="plugin.reloadAvailable" class="small warn">
                磁盘上是 v{{ plugin.diskVersion }}
              </span>
              <!-- 重载与停用对核心插件都不可用（后端直接拒绝）：它是全部笔记端点的宿主，
                   重建容器期间路由与索引会短暂缺失。这里直接不渲染这两个按钮。 -->
              <button
                v-if="!plugin.undeactivatable"
                class="btn sm ghost"
                :disabled="!!busy"
                :title="plugin.reloadAvailable
                  ? `按磁盘上的 v${plugin.diskVersion} 重建该插件的容器`
                  : '按磁盘上的 jar 重建该插件的容器（改了插件代码/换了 jar 之后用）'"
                @click="reloadPlugin(plugin)"
              >
                重载
              </button>
              <button class="btn sm ghost" @click="togglePluginPanel(plugin)">
                {{ isPluginOpen(plugin.id) ? '收起' : '设置' }}
              </button>
              <button
                v-if="!plugin.undeactivatable"
                class="btn sm"
                :class="{ primary: plugin.active }"
                :disabled="!!busy"
                @click="togglePlugin(plugin)"
              >
                {{ plugin.active ? '已启用' : '已停用' }}
              </button>
            </div>

            <p class="small muted plugin-desc">{{ plugin.description }}</p>
            <p v-if="plugin.dependencies?.length" class="small muted">
              依赖：<span class="mono">{{ plugin.dependencies.join(', ') }}</span>
            </p>

            <div v-if="isPluginOpen(plugin.id)" class="plugin-body">
              <!-- 插件自带的设置面板（由插件自己注册，平台不掺和它的内容） -->
              <component :is="pluginSettings(plugin.id)" v-if="pluginSettings(plugin.id)" />
              <p v-else class="small muted">这个插件没有自己的设置面板。</p>

            </div>
          </div>
        </div>
      </section>

    </div>

    <ConfirmDialog
      v-if="confirmation"
      :open="true"
      :title="confirmation.title"
      :message="confirmation.message"
      :confirm-label="confirmation.confirmLabel"
      :busy="!!busy"
      @cancel="cancelConfirmation"
      @confirm="confirmAction"
    />
  </div>
</template>

<style scoped>
.settings {
  display: grid;
  grid-template-columns: 176px minmax(0, 1fr);
  grid-template-rows: 40px minmax(0, 1fr);
  height: 100%;
  min-height: 0;
}
.tabs {
  grid-column: 1 / -1;
  grid-row: 1;
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: 0 var(--space-3);
  height: 40px;
  border-bottom: 1px solid var(--c-border);
  background: var(--c-surface);
}
.tab-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text-2);
}
.settings-tabs {
  grid-column: 1;
  grid-row: 2;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 2px;
  padding: var(--space-3) var(--space-2);
  border-right: 1px solid var(--c-border);
  background: var(--c-surface);
}
.settings-tab {
  width: 100%;
  text-align: left;
  padding: 9px 12px;
  border: 0;
  border-left: 2px solid transparent;
  background: transparent;
  color: var(--c-text-3);
  font-size: 13px;
  cursor: pointer;
}
.settings-tab.active {
  border-left-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
}
.tabs button {
  padding: 6px 10px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  color: var(--c-text-3);
}
.body {
  grid-column: 2;
  grid-row: 2;
  overflow-y: auto;
  min-width: 0;
  min-height: 0;
}
.busy-bar {
  padding: 6px var(--space-4);
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12.5px;
}
.panel {
  padding: var(--space-4) var(--space-5) 15vh;
  max-width: 900px;
}
.panel h3 {
  margin: var(--space-3) 0 var(--space-1);
  font-size: 14px;
}
.panel h3:first-child {
  margin-top: 0;
}
.warn {
  color: var(--warning);
  margin: 0;
  padding-left: var(--space-4);
}
.notes-root-active {
  overflow-wrap: anywhere;
  margin: var(--space-2) 0 0;
}

@media (max-width: 640px) {
  .settings {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: 40px auto minmax(0, 1fr);
  }
  .tabs {
    grid-column: 1;
    grid-row: 1;
  }
  .settings-tabs {
    grid-column: 1;
    grid-row: 2;
    flex-direction: row;
    overflow-x: auto;
    padding: 0 var(--space-2);
    border-right: 0;
    border-bottom: 1px solid var(--c-border);
  }
  .body {
    grid-column: 1;
    grid-row: 3;
  }
  .settings-tab {
    width: auto;
    flex: 0 0 auto;
    white-space: nowrap;
    border-left: 0;
    border-bottom: 2px solid transparent;
  }
  .settings-tab.active {
    border-bottom-color: var(--accent);
  }
}

/* --- 插件管理 --- */
#settings-panel-plugins {
  max-width: none;
}

.plugin-list {
  column-count: 5;
  column-width: auto;
  column-gap: var(--space-3);
  margin-top: var(--space-3);
}
.plugin-card {
  display: inline-block;
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  margin: 0 0 var(--space-3);
  padding: var(--space-3);
  border: 1px solid var(--c-border);
  border-radius: 10px;
  background: var(--c-surface);
  break-inside: avoid;
  -webkit-column-break-inside: avoid;
  page-break-inside: avoid;
}
.plugin-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.plugin-head .spacer {
  flex: 1;
}
.kind {
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 17px;
}
.kind-core {
  background: var(--c-surface-2);
  color: var(--c-text-2, var(--c-text));
}
.health-ok {
  color: var(--success, #3a9);
}
.health-degraded {
  color: var(--warning);
}
.health-error {
  color: var(--danger, #d55);
}
.health-unknown {
  color: var(--c-text-3);
}
.plugin-desc {
  margin: var(--space-1) 0 0;
}
.plugin-body {
  margin-top: var(--space-2);
  padding-top: var(--space-2);
  border-top: 1px dashed var(--c-border);
}

@media (max-width: 1700px) {
  .plugin-list {
    column-count: 4;
  }
}

@media (max-width: 1360px) {
  .plugin-list {
    column-count: 3;
  }
}

@media (max-width: 1050px) {
  .plugin-list {
    column-count: 2;
  }
}

@media (max-width: 700px) {
  .plugin-list {
    column-count: 1;
  }
}
</style>
