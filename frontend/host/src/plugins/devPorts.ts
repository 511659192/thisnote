/**
 * 插件微应用在**开发模式**下的 dev server 端口约定。
 *
 * <p>主应用按这张表把微应用入口指到对应插件的 Vite dev server（见 loader.ts）；
 * 端口没起或插件不在这张表里时，自动回退到后端 jar 里的产物（/plugin-assets）。
 * 新增插件时：给它的 vite.config.ts 固定一个端口，来这里登记一行。
 */
export const PLUGIN_DEV_PORTS: Record<string, number> = {
  favorite: 7111,
  'memory-curve': 7112,
}
