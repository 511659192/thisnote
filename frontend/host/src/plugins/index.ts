/**
 * 核心访问插件平台的入口（``@/plugins``）。
 *
 * <p>插件界面是 **qiankun 微应用**：核心（主应用）在运行时按后端启用列表逐个装载，
 * 生产吃插件 jar 里的 ``plugin.js``，开发吃各插件 dev server 的源码（见 ``loader.ts``）。
 * 这里只把平台重新导出，核心各处照旧 ``from '@/plugins'``。
 */
export * from '@thisnote/plugin-api'
export {
  loadActivePluginFrontends,
  loadPluginFrontend,
  unloadPluginFrontend,
  reloadPluginFrontend,
  syncPluginRoutes,
} from './loader'
