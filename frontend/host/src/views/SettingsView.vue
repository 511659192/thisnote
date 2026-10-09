<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { systemApi } from '@/api'
import { pluginApi, type PluginConfigField, type PluginInfo } from '@/api/plugins'
import {
  pluginSettings,
  refreshPluginAvailability,
  loadPluginFrontend,
  unloadPluginFrontend,
  reloadPluginFrontend,
} from '@/plugins'
import { setApiBase, apiBase } from '@/core/http'
import { useUiStore } from '@/stores/ui'
import { useNoteStore } from '@/stores/notes'
import { formatRelative } from '@/core/utils'

const ui = useUiStore()
const route = useRoute()
const router = useRouter()
const nodes = useNoteStore()

const tab = ref<'data' | 'backup' | 'plugins' | 'about'>('data')
const busy = ref('')
const apiBaseDraft = ref(apiBase.value)

// ---- 插件管理（列表来自后端注册表；每个插件可启停、可配置）
const plugins = ref<PluginInfo[]>([])
const openPlugin = ref('')
const configDrafts = ref<Record<string, Record<string, string>>>({})

async function refreshPlugins() {
  try {
    plugins.value = await pluginApi.list()
  } catch {
    plugins.value = []
  }
}

async function togglePlugin(plugin: PluginInfo) {
  const next = !plugin.active
  if (!next && !window.confirm(`停用「${plugin.title}」？\n\n停用后它自己的端点会被拒绝（管理面仍可访问，随时可以再启用）。`)) {
    return
  }
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
  }
}

/**
 * 重载插件：换了 plugins/ 里的 jar（或开发模式改了插件代码）之后让它生效。
 * SBP 会**重建插件的类加载器与 Spring 子容器**，换掉磁盘上的 jar 当场生效。
 */
async function reloadPlugin(plugin: PluginInfo) {
  const hint = plugin.reloadAvailable
    ? `\n\n磁盘上的版本是 v${plugin.diskVersion}，运行中的是 v${plugin.version}。`
    : ''
  if (!window.confirm(`重载「${plugin.title}」？${hint}\n\n它会先停掉再按磁盘上的 jar 重启，进行中的请求会失败。`)) {
    return
  }
  try {
    const updated = await pluginApi.reload(plugin.id)
    plugins.value = plugins.value.map((p) => (p.id === updated.id ? updated : p))
    await refreshPluginAvailability(true)
    // 插件的类加载器与容器已在后端重建；前端侧同步重载微应用（卸载 + 装载），
    // 换掉磁盘上的 jar 当场生效。
    await reloadPluginFrontend(plugin.id, router)
    ui.toast(`「${plugin.title}」已重载（v${updated.version}）`, 'success')
  } catch (e) {
    ui.toast(`重载失败：${(e as Error).message}`, 'error')
  }
}

function togglePluginPanel(plugin: PluginInfo) {
  openPlugin.value = openPlugin.value === plugin.id ? '' : plugin.id
  if (openPlugin.value === plugin.id && !configDrafts.value[plugin.id]) {
    configDrafts.value[plugin.id] = {}
  }
}

/** 只提交用户改动过的键；敏感项留空表示不改动（后端也是这个语义）。 */
async function savePluginConfig(plugin: PluginInfo) {
  const draft = configDrafts.value[plugin.id] ?? {}
  const payload: Record<string, string> = {}
  for (const field of plugin.configSchema) {
    if (!(field.key in draft)) continue
    const value = draft[field.key] ?? ''
    if (field.secret && value === '') continue
    payload[field.key] = value
  }
  if (Object.keys(payload).length === 0) {
    ui.toast('没有需要保存的改动')
    return
  }
  try {
    const updated = await pluginApi.updateConfig(plugin.id, payload)
    plugins.value = plugins.value.map((p) => (p.id === updated.id ? updated : p))
    configDrafts.value[plugin.id] = {}
    ui.toast('配置已保存', 'success')
  } catch (e) {
    ui.toast(`保存失败：${(e as Error).message}`, 'error')
  }
}

function draftValue(plugin: PluginInfo, field: PluginConfigField): string {
  const draft = configDrafts.value[plugin.id]
  if (draft && field.key in draft) return draft[field.key]
  return field.secret ? '' : (field.value ?? '')
}

function setDraft(plugin: PluginInfo, field: PluginConfigField, value: string) {
  const draft = configDrafts.value[plugin.id] ?? {}
  draft[field.key] = value
  configDrafts.value[plugin.id] = draft
}

function healthClass(plugin: PluginInfo): string {
  return `health-${plugin.health.status}`
}

/** 插件类型的中文标签（与后端 PluginKind 的 id 对应）。 */
function kindLabel(kind: PluginInfo['kind']): string {
  switch (kind) {
    case 'core': return '核心'
    case 'card': return '卡片'
    case 'entity': return '实体'
    case 'service': return '服务'
    default: return kind
  }
}

async function refresh() {
  apiBaseDraft.value = apiBase.value
  await refreshPlugins()
}

onMounted(async () => {
  await refresh()
  // 支持 /settings?tab=plugins 直达（例如从「插件已停用」的提示点进来）
  if (route.query.tab === 'plugins') tab.value = 'plugins'
})

async function withBusy(label: string, fn: () => Promise<unknown>) {
  busy.value = label
  try {
    await fn()
    ui.toast(`${label}完成`, 'success')
  } catch (e) {
    ui.toast(`${label}失败：${(e as Error).message}`, 'error')
  } finally {
    busy.value = ''
    await refresh()
  }
}
function applyApiBase() {
  setApiBase(apiBaseDraft.value.trim())
  ui.toast('后端地址已更新，正在重载…')
  window.location.reload()
}
</script>

<template>
  <div class="settings">
    <header class="tabs">
      <button :class="{ active: tab === 'data' }" @click="tab = 'data'">本地文件</button>
      <button :class="{ active: tab === 'backup' }" @click="tab = 'backup'">维护</button>
      <button :class="{ active: tab === 'plugins' }" @click="tab = 'plugins'">插件</button>
      <button :class="{ active: tab === 'about' }" @click="tab = 'about'">关于</button>
      <div class="spacer"></div>
      <button class="btn sm" :disabled="!!busy" @click="refresh">刷新</button>
    </header>

    <div class="body">
      <div v-if="busy" class="busy-bar">{{ busy }}…</div>

      <!-- ------------------------------ data ------------------------------ -->
      <section v-if="tab === 'data'" class="panel">
        <h3>笔记存储（本地文件）</h3>
        <p class="small muted">
          笔记以<strong>本地文件为权威</strong>：一张卡片 = 一个 .md，头部一行 meta 注释写着它归属
          哪篇笔记与摆放位置，其余是正文。可直接在 Obsidian / VS Code 中查看与编辑这些文件；
          外部改动在应用重启时对齐（cards/ 里新增的 md 会被认领成笔记）。
        </p>

      </section>

      <!-- ------------------------------ backup ------------------------------ -->
      <section v-if="tab === 'backup'" class="panel">
        <h3>维护</h3>
        <div class="row wrap">
          <button class="btn" @click="withBusy('重建索引', () => systemApi.reindex())">重扫笔记目录并重算链接</button>
        </div>
        <dl class="kv" style="margin-top: 12px">
          <dt>存储</dt><dd class="mono small">本地文件（卡片 md + meta）</dd>
        </dl>
      </section>

      <!-- ------------------------------ plugins ------------------------------ -->
      <section v-if="tab === 'plugins'" class="panel">
        <h3>已安装的插件</h3>
        <p class="small muted">
          插件分两类：<b>卡片插件</b>给卡片右上角加工具（如记忆曲线）；<b>服务插件</b>提供整库范围的能力
          并带来自己的设置面板（如百度网盘备份）。停用后该插件自己的端点会被拒绝，但管理面仍可访问，
          随时能再打开。
        </p>

        <div v-if="!plugins.length" class="small muted">没有已注册的插件。</div>

        <div v-for="plugin in plugins" :key="plugin.id" class="plugin-card">
          <div class="plugin-head">
            <span class="kind" :class="`kind-${plugin.kind}`">
              {{ kindLabel(plugin.kind) }}
            </span>
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
              v-if="plugin.kind !== 'core'"
              class="btn sm ghost"
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
              v-if="plugin.kind !== 'core'"
              class="btn sm"
              :class="{ primary: plugin.active }"
              @click="togglePlugin(plugin)"
            >
              {{ plugin.active ? '已启用' : '已停用' }}
            </button>
          </div>

          <p class="small muted plugin-desc">{{ plugin.description }}</p>

          <div v-if="openPlugin === plugin.id" class="plugin-body">
            <!-- 插件自带的功能面板（如网盘的授权/备份） -->
            <component :is="pluginSettings(plugin.id)" v-if="pluginSettings(plugin.id)" />

            <!-- 通用配置表单：配置是平台级能力，与插件自带面板并存而不是二选一 -->
            <template v-if="plugin.configSchema.length">
              <h4 v-if="pluginSettings(plugin.id)" class="config-title">插件配置</h4>
              <div v-for="field in plugin.configSchema" :key="field.key" class="field">
                <label class="small">
                  {{ field.label }}
                  <span v-if="field.required" class="muted">（必填）</span>
                </label>
                <input
                  v-if="field.type !== 'boolean'"
                  class="input"
                  :type="field.type === 'password' ? 'password' : (field.type === 'number' ? 'number' : 'text')"
                  :placeholder="field.secret && field.hasValue
                    ? '已保存（留空不改动）'
                    : (field.defaultValue ?? '')"
                  :value="draftValue(plugin, field)"
                  @input="setDraft(plugin, field, ($event.target as HTMLInputElement).value)"
                />
                <label v-else class="checkline small">
                  <input
                    type="checkbox"
                    :checked="draftValue(plugin, field) === 'true'"
                    @change="setDraft(plugin, field,
                      ($event.target as HTMLInputElement).checked ? 'true' : 'false')"
                  />
                  开启
                </label>
                <p v-if="field.help" class="small muted field-help">{{ field.help }}</p>
              </div>
              <div class="row">
                <button class="btn primary" @click="savePluginConfig(plugin)">保存配置</button>
              </div>
            </template>

            <p v-else-if="!pluginSettings(plugin.id)" class="small muted">这个插件没有可配置项。</p>

            <p v-if="plugin.migrationLocation" class="small muted mono plugin-migration">
              自带迁移：{{ plugin.migrationLocation }}
            </p>
          </div>
        </div>
      </section>


      <!-- ------------------------------ about ------------------------------ -->
      <section v-if="tab === 'about'" class="panel">
        <h3>thisnode 韧知笔记 · MVP</h3>
        <p class="small muted">
          本地优先的节点化知识工具。Note 是统一原子，一篇笔记 = 一个页面 = 一列卡片。
        </p>
        <div class="divider"></div>
        <h3>后端地址</h3>
        <div class="row">
          <input v-model="apiBaseDraft" class="input grow mono" placeholder="http://127.0.0.1:45917" />
          <button class="btn" @click="applyApiBase">应用并重载</button>
        </div>
        <p class="small muted">
          桌面壳会自动注入 <span class="mono">window.__THISNODE_API__</span>；此处仅用于自定义端口或远程调试。
        </p>
        <div class="divider"></div>
        <h3>快捷键</h3>
        <dl class="kv">
          <dt>Ctrl/Cmd + S</dt><dd>立即保存</dd>
          <dt>Ctrl/Cmd + D</dt><dd>复制选中卡片</dd>
          <dt>Delete</dt><dd>删除选中卡片</dd>
          <dt>Ctrl/Cmd + 1 / 0</dt><dd>适应内容 / 重置视图</dd>
        </dl>
      </section>
    </div>
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
.checkline {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: var(--space-2) 0 0;
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
.kind-card {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}
.kind-core {
  background: var(--c-surface-2);
  color: var(--c-text-2, var(--c-text));
}
.kind-entity {
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}
.kind-service {
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
.field {
  margin-bottom: var(--space-2);
}
.field .input {
  width: 100%;
  margin-top: 3px;
}
.field-help {
  margin: 3px 0 0;
}
.config-title {
  margin: var(--space-3) 0 var(--space-2);
  font-size: 13px;
}
</style>
