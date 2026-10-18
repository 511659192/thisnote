import { loadMicroApp, type MicroApp, type ModuleNamespace } from 'qiankun'
import type { Router } from 'vue-router'
import { pluginApi, pluginRoutes } from '@thisnote/plugin-api'
import { SHARED_DEP_PREFIX } from '@thisnote/plugin-api/vite'
import { PLUGIN_DEV_PORTS } from './devPorts'

/**
 * 插件界面的 **qiankun 微应用装载器** —— 主应用侧的装配车间。
 *
 * <p>架构：每个插件界面是一个 qiankun 微应用（独立 dev server / 独立产物 / 生命周期），
 * 主应用按后端 `/api/plugins/get` 的启用列表逐个 `loadMicroApp`，装进 index.html 里的
 * 隐藏容器。微应用的 `mount()` 里完成六个插槽的注册（卡片工具 / 笔记工具 / 设置面板 /
 * 侧栏入口 / 插件路由 / 列表筛选），`unmount()` 里注销 —— 于是「停用 / 重载插件」
 * 第一次成为界面侧的真实生命周期事件，不再依赖整页刷新。
 *
 * <p><b>共享运行时</b>：沙箱里 fetch 的任何 URL 模块都是私有实例，所以共享的
 * vue / vue-router / @thisnote/plugin-api 不走 URL（那是旧 import map 方案，
 * 在沙箱下会裂成两个 Vue），而是宿主把自己的模块命名空间通过 `sandbox.modules`
 * 注入微应用 —— 微应用源码里的裸 import 解析到的就是宿主正在用的同一份实例。
 *
 * <p><b>微应用入口</b>是一段运行时生成的 HTML（blob URL，后端无需任何改动），
 * 里面只有一个 module script，指向：
 * <ul>
 *   <li><b>开发</b>：插件自己 dev server 的源码入口（端口约定见 {@link PLUGIN_DEV_PORTS}；
 *       dev server 没起时自动回退到后端端出的产物，只是没有插件源码级的调试体验）；</li>
 *   <li><b>生产</b>：界面侧静态服务提供的 `/plugin-assets/<id>/...`（路径直通，可配基址）。</li>
 * </ul>
 */

/** 已装载的微应用句柄，key = 插件 id。停用 / 重载时按它卸载。 */
const microApps = new Map<string, MicroApp>()

/** 宿主挂到 router 下的插件路由：name → pluginId（卸载时要按 id 拆，与注册表无关）。 */
const registeredRoutes = new Map<string, string>()

// ---------------------------------------------------------------------------
// 共享模块：宿主的 vue / vue-router / @thisnote/plugin-api 命名空间
// ---------------------------------------------------------------------------

let sharedModulesPromise: Promise<Record<string, { namespace: ModuleNamespace }>> | null = null

function sharedSandboxModules(): Promise<Record<string, { namespace: ModuleNamespace }>> {
  sharedModulesPromise ??= (async () => {
    // 四个 import 都发生在宿主自己的模块图里，拿到的就是宿主正在用的那份实例。
    // pinia 也在列：笔记域（notes 微应用）的 store 用 defineStore 定义，
    // 宿主渲染它的组件时必须解析到宿主正在用的那个 pinia 实例。
    const [vue, vueRouter, pinia, pluginApiModule] = await Promise.all([
      import('vue'),
      import('vue-router'),
      import('pinia'),
      import('@thisnote/plugin-api'),
    ])
    return {
      vue: { namespace: vue },
      'vue-router': { namespace: vueRouter },
      pinia: { namespace: pinia },
      '@thisnote/plugin-api': { namespace: pluginApiModule },
    }
  })()
  return sharedModulesPromise
}

/**
 * 开发期的说明符改写：插件 dev server 的 {@code qiankunSharedDeps}（@thisnote/plugin-api/vite）
 * 把共享依赖拦截成 `{@link SHARED_DEP_PREFIX}` 开头的哨兵路径；这里把它映射回裸名，
 * 让沙箱继续走 modules 表解析到宿主的实例。其余说明符按沙箱默认规则处理
 * （URL 相对解析；未知裸名交给 modules 表，找不到就报错——插件不该有别的裸依赖）。
 */
function devSharedResolveHook(specifier: string, referrer: string): string {
  if (specifier.startsWith(SHARED_DEP_PREFIX)) {
    const name = decodeURIComponent(specifier.slice(SHARED_DEP_PREFIX.length).split('?')[0])
    if (name === 'vue' || name === 'vue-router' || name === 'pinia' || name === '@thisnote/plugin-api') return name
    return specifier
  }
  const isUrlLike =
    specifier.startsWith('/') || specifier.startsWith('./') || specifier.startsWith('../') ||
    /^[a-z][a-z0-9+.-]*:/i.test(specifier)
  if (isUrlLike) {
    try {
      return new URL(specifier, referrer).href
    } catch {
      return specifier
    }
  }
  return specifier
}

// ---------------------------------------------------------------------------
// 微应用入口：运行时生成的 blob HTML
// ---------------------------------------------------------------------------

/**
 * 入口 HTML 里只放一个 module script；它必须是**绝对地址**
 * （blob URL 不能作为相对解析的 base）。
 */
function buildHtmlEntry(moduleSrcUrl: string): string {
  const html = `<!doctype html><html><body><script type="module" src="${moduleSrcUrl}"></script></body></html>`
  return URL.createObjectURL(new Blob([html], { type: 'text/html' }))
}

/**
 * 界面资源基址：默认**同源**（部署时由界面侧的静态服务在 `/plugin-assets/` 下提供）；
 * 也可以用 `window.__THISNOTE_PLUGIN_ASSETS__` 或 localStorage 的
 * `thisnote.pluginAssetsBase` 覆盖到别的源（与 apiBase 同一套优先级）。
 */
function assetsBase(): string {
  const injected = (window as unknown as { __THISNOTE_PLUGIN_ASSETS__?: string }).__THISNOTE_PLUGIN_ASSETS__
  if (typeof injected === 'string' && injected) return injected.replace(/\/+$/, '')
  const stored = localStorage.getItem('thisnote.pluginAssetsBase')
  if (stored) return stored.replace(/\/+$/, '')
  return ''
}

/**
 * 生产形态：由界面侧静态服务提供的插件产物。
 *
 * <p>路径形态是刻意保留的：plugin.js 内的 `./chunk-*.js` 相对导入需要目录语义，
 * query 形态会让相对解析落到页面根。
 */
function prodModuleSrcUrl(id: string): string {
  const base = assetsBase()
  const path = `/plugin-assets/${id}/plugin.js`
  return base ? base + path : new URL(path, window.location.origin).href
}

/** 开发形态：插件自己 dev server 的源码入口；没起就回退 null。 */
async function devModuleSrcUrl(id: string): Promise<string | null> {
  const port = PLUGIN_DEV_PORTS[id]
  if (!port) return null
  const src = `http://localhost:${port}/index.ts`
  try {
    const res = await fetch(src, { method: 'HEAD' })
    if (res.ok) return src
  } catch {
    // dev server 没起，走后端端出的产物兜底
  }
  return null
}

async function moduleSrcUrlFor(id: string): Promise<string> {
  if (import.meta.env.DEV) {
    const dev = await devModuleSrcUrl(id)
    if (dev) return dev
    console.info(`[插件] ${id} 的 dev server（端口 ${PLUGIN_DEV_PORTS[id] ?? '未约定'}）不可达，回退到后端端出的产物`)
  }
  return prodModuleSrcUrl(id)
}

// ---------------------------------------------------------------------------
// 装载 / 卸载
// ---------------------------------------------------------------------------

function pluginNotebook(id: string): HTMLElement {
  // 每个插件微应用一个**独立**容器：qiankun 的容器占用闸门是排他的，
  // 多个微应用共用一个容器会互相等待（"app X is waiting for container held by Y"）。
  const root = document.getElementById('plugin-microapps')
  if (!root) {
    // index.html 里的容器被删了（不该发生）：兜底重建
    const fallbackRoot = document.createElement('div')
    fallbackRoot.id = 'plugin-microapps'
    fallbackRoot.style.display = 'none'
    document.body.appendChild(fallbackRoot)
    return provisionNotebook(fallbackRoot, id)
  }
  return provisionNotebook(root, id)
}

function provisionNotebook(root: HTMLElement, id: string): HTMLElement {
  const own = `plugin-microapp-${id}`
  let own_ = root.querySelector(`#${CSS.escape(own)}`)
  if (!own_) {
    own_ = document.createElement('div')
    own_.id = own
    root.appendChild(own_)
  }
  return own_ as HTMLElement
}

/**
 * 生产：插件的样式由它自己的构建抽成 `plugin.css`（组件里的 `<style>`），
 * 有才插 `<link>`，免得没有样式的插件在控制台留下 404。
 * 开发不走这里 —— 样式由插件 dev server 以 JS 注入，跟宿主的 HMR 体系一致。
 */
async function injectStyles(id: string): Promise<void> {
  const base = assetsBase()
  const href = base ? `${base}/plugin-assets/${id}/plugin.css` : `/plugin-assets/${id}/plugin.css`
  try {
    const res = await fetch(href, { method: 'HEAD' })
    if (!res.ok) return
  } catch {
    return
  }
  if (document.querySelector(`link[data-plugin-css="${id}"]`)) return
  const link = document.createElement('link')
  link.rel = 'stylesheet'
  link.href = href
  link.dataset.pluginCss = id
  document.head.appendChild(link)
}

/**
 * 装载单个插件微应用。mount 链完成（插槽注册完毕、插件路由已可解析）后才 resolve，
 * 深链接（#/review、#/favorites）因此能一次就匹配上。失败不抛出：插件界面的问题
 * 不该拖垮核心，控制台如实告警即可。
 */
export async function loadPluginFrontend(id: string, router: Router): Promise<void> {
  if (microApps.has(id)) return
  try {
    const [modules, moduleSrcUrl] = await Promise.all([sharedSandboxModules(), moduleSrcUrlFor(id)])
    if (!import.meta.env.DEV) await injectStyles(id)
    const microApp = loadMicroApp(
      { name: `plugin-${id}`, entry: buildHtmlEntry(moduleSrcUrl), container: pluginNotebook(id), props: { pluginId: id } },
      {
        sandbox: {
          modules,
          resolveHook: import.meta.env.DEV ? devSharedResolveHook : undefined,
        },
      },
      {
        // 插件 dev server 的样式模块在 import 期就注入样式（早于 mount），而沙箱的
        // document.head 代理要求自己的容器里已有 <qiankun-head>；qiankun 自己不代建（
        // provisionNotebookHead=false），initNotebook 还会把预置节点清掉 —— 所以在
        // 装载链最早的 beforeLoad 里补上（此时容器已初始化、模块尚未求值）。
        beforeLoad: async () => {
          const container = pluginNotebook(id)
          if (!container.querySelector('qiankun-head')) {
            container.prepend(document.createElement('qiankun-head'))
          }
        },
      },
    )
    microApps.set(id, microApp)
    // mount 里的注册（含插件路由登记）完成后，路由表才需要 sync
    await microApp.mountPromise
    syncPluginRoutes(router)
  } catch (e) {
    microApps.delete(id)
    console.warn(`[插件] ${id} 的界面装载失败：`, e)
  }
}

/** 卸载单个插件微应用：qiankun unmount → 插件自己注销插槽 → 拆掉宿主路由。 */
export async function unloadPluginFrontend(id: string, router: Router): Promise<void> {
  const app = microApps.get(id)
  if (!app) return
  microApps.delete(id)
  for (const [name, pluginId] of [...registeredRoutes]) {
    if (pluginId !== id) continue
    if (router.hasRoute(name)) router.removeRoute(name)
    registeredRoutes.delete(name)
  }
  try {
    await app.unmount()
  } catch (e) {
    console.warn(`[插件] ${id} 的界面卸载失败：`, e)
  }
}

/** 重载 = 卸载再装载（换 jar 后点「重载」、开发期改插件界面代码后想立刻生效时用）。 */
export async function reloadPluginFrontend(id: string, router: Router): Promise<void> {
  await unloadPluginFrontend(id, router)
  await loadPluginFrontend(id, router)
}

/** 启动时装载所有启用中的插件（main.ts 调用；任一插件失败不影响其余与核心）。 */
export async function loadActivePluginFrontends(router: Router): Promise<void> {
  let activeIds: string[]
  try {
    const plugins = await pluginApi.list()
    // 产物分离对齐后，CORE 插件（notes）也有界面微应用 —— 全部启用中的插件都装载。
    activeIds = plugins.filter((plugin) => plugin.active).map((plugin) => plugin.id)
  } catch (e) {
    // 后端没起来/列表拿不到：界面照常可用，只是没有插件入口
    console.warn('[插件] 拿不到插件列表，跳过界面装载：', e)
    return
  }
  await Promise.all(activeIds.map((id) => loadPluginFrontend(id, router)))
}

/**
 * 把插件登记的页面路由挂到 Shell 之下（晚到的插件也能挂上）。
 *
 * <p>插件路由会与核心路由一样带侧栏与顶栏；静态路径比 catch-all 更具体，优先匹配。
 */
export function syncPluginRoutes(router: Router): void {
  for (const route of pluginRoutes()) {
    if (!route.component || registeredRoutes.has(route.name)) continue
    router.addRoute('shell', {
      path: route.path,
      name: route.name,
      component: route.component,
      props: route.props,
    })
    registeredRoutes.set(route.name, route.pluginId)
  }
}
