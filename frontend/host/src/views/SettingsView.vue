<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { pluginApi, type PluginInfo } from '@/api/plugins'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import {
  pluginSettings,
  refreshPluginAvailability,
  loadPluginFrontend,
  unloadPluginFrontend,
  reloadPluginFrontend,
} from '@/plugins'
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
const openPlugin = ref('')

async function refreshPlugins() {
  try {
    plugins.value = await pluginApi.list()
  } catch {
    plugins.value = []
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

function togglePluginPanel(plugin: PluginInfo) {
  openPlugin.value = openPlugin.value === plugin.id ? '' : plugin.id
}

function healthClass(plugin: PluginInfo): string {
  return `health-${plugin.health.status}`
}

/** 只有「不可停用」的插件需要一枚徽标；其余没有分类标签（分类由插槽事实决定，不靠声明）。 */
function coreLabel(plugin: PluginInfo): string {
  return plugin.undeactivatable ? '核心' : ''
}

async function refresh() {
  await refreshPlugins()
}

onMounted(async () => {
  await refresh()
})
</script>

<template>
  <div class="settings">
    <header class="tabs">
      <button class="btn sm ghost" title="返回笔记" @click="$router.push('/notes')">← 返回笔记</button>
      <div class="tab-title">设置 · 插件</div>
      <div class="spacer"></div>
      <button class="btn sm" :disabled="!!busy" @click="refresh">刷新</button>
    </header>

    <div class="body">
      <div v-if="busy" class="busy-bar">{{ busy }}…</div>

      <!-- ------------------------------ plugins ------------------------------ -->
      <section class="panel">
        <h3>已安装的插件</h3>
        <p class="small muted">
          插件分两类：<b>卡片插件</b>给卡片右上角加工具（如记忆曲线）；<b>服务插件</b>提供整库范围的能力
          并带来自己的设置面板（如百度网盘备份）。停用后该插件自己的端点会被拒绝，但管理面仍可访问，
          随时能再打开。
        </p>

        <div v-if="!plugins.length" class="small muted">没有已注册的插件。</div>

        <div v-for="plugin in plugins" :key="plugin.id" class="plugin-card">
          <div class="plugin-head">
            <span v-if="plugin.undeactivatable" class="kind kind-core">{{ coreLabel(plugin) }}</span>
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
              {{ openPlugin === plugin.id ? '收起' : '设置' }}
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

          <div v-if="openPlugin === plugin.id" class="plugin-body">
            <!-- 插件自带的设置面板（由插件自己注册，平台不掺和它的内容） -->
            <component :is="pluginSettings(plugin.id)" v-if="pluginSettings(plugin.id)" />
            <p v-else class="small muted">这个插件没有自己的设置面板。</p>

            <p v-if="plugin.migrationLocation" class="small muted mono plugin-migration">
              自带迁移：{{ plugin.migrationLocation }}
            </p>
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
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.tabs {
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
.tabs button {
  padding: 6px 10px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  color: var(--c-text-3);
}
.tabs button.active {
  background: var(--accent-soft);
  color: var(--accent);
}
.body {
  flex: 1;
  overflow-y: auto;
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
.grow {
  flex: 1;
}
.file-btn {
  position: relative;
}
.report {
  margin-top: var(--space-3);
  padding: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  background: var(--c-surface);
}
.warn {
  color: var(--warning);
  margin: 0;
  padding-left: var(--space-4);
}
.kv {
  display: grid;
  grid-template-columns: 130px 1fr;
  gap: 5px var(--space-3);
  margin: var(--space-2) 0;
  font-size: 13px;
}
.kv dt {
  color: var(--c-text-3);
}
.kv dd {
  margin: 0;
  word-break: break-all;
}
.event {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 5px 0;
  border-bottom: 1px solid var(--c-border);
}
.wrap {
  flex-wrap: wrap;
}
/* 同步报告明细：等宽、限高可滚，避免一次备份几百条把页面撑爆 */
.lines {
  margin: var(--space-2) 0 0;
  padding-left: 1.1em;
  max-height: 260px;
  overflow-y: auto;
}
.lines li {
  line-height: 1.6;
}

/* --- 插件管理 --- */
.plugin-card {
  margin: var(--space-3) 0;
  padding: var(--space-3);
  border: 1px solid var(--c-border);
  border-radius: 10px;
  background: var(--c-surface);
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
.plugin-migration {
  margin-top: var(--space-2);
}
</style>
