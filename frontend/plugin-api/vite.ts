/**
 * 插件微应用开发服务器的共享依赖插件（`@thisnote/plugin-api/vite`）。
 *
 * <p><b>为什么需要它：</b>qiankun 3 的 ESM 沙箱自己加载微应用的模块图 —— 沙箱里 fetch 到的
 * 任何 URL 模块都是<b>私有实例</b>。插件与宿主必须共用同一份 Vue / vue-router / 插件契约
 * （否则插槽注册进的是另一个注册表、组件渲染用的是另一套响应式系统），唯一的共享通道是
 * 宿主在沙箱配置里注入的 <code>modules</code> 命名空间表 —— 它只认<b>裸说明符</b>。
 *
 * <p>而 Vite 开发服务器会把裸 import 改写成自己 node_modules 里的依赖 URL（私有实例！）。
 * 本插件在 resolveId 阶段把三个共享依赖拦截成哨兵路径（{@code /@qk-shared/<name>}）——
 * 改写后的源码里保留的就是这个可识别的路径，宿主的 resolveHook 把它映射回裸名，
 * 再由 modules 表解析到宿主的命名空间。
 *
 * <p>独立运行（直接打开插件的 dev server）时沙箱不存在，哨兵路径由本插件的中间件兜底：
 * 用 Vite 解析出插件<b>自己的</b>依赖模块并返回其转换产物 —— 独立页面上它就是唯一的 Vue，成立。
 */
const SHARED_DEPS = ['vue', 'vue-router', 'pinia', '@thisnote/plugin-api']

/** 哨兵路径前缀：宿主侧 resolveHook 按它识别「这是共享依赖」。 */
export const SHARED_DEP_PREFIX = '/@qk-shared/'

export function qiankunSharedDeps() {
  return {
    name: 'thisnote-qiankun-shared-deps',
    enforce: 'pre' as const,
    resolveId(id: string) {
      if (SHARED_DEPS.includes(id)) return SHARED_DEP_PREFIX + id
      return null
    },
    load(id: string) {
      // 哨兵路径没有磁盘文件，模块体由 HTTP 中间件按需产出；这里返回空模块，
      // 让 import 预转换（pre-transform）不再把「文件不存在」刷成错误日志。
      if (id.startsWith(SHARED_DEP_PREFIX)) return ''
      return null
    },
    configureServer(server: {
      middlewares: { use: (fn: (req: unknown, res: unknown, next: () => void) => void) => void }
      pluginContainer: { resolveId: (id: string) => Promise<{ id: string } | null> }
      transformRequest: (url: string) => Promise<{ code: string } | null>
    }) {
      server.middlewares.use(async (req, res, next) => {
        const r = req as { url?: string }
        const w = res as { statusCode?: number; setHeader: (k: string, v: string) => void; end: (s?: string) => void }
        const url = (r.url ?? '').split('?')[0]
        if (!url.startsWith(SHARED_DEP_PREFIX)) return next()
        const name = decodeURIComponent(url.slice(SHARED_DEP_PREFIX.length))
        if (!SHARED_DEPS.includes(name)) {
          w.statusCode = 404
          return w.end()
        }
        try {
          const resolved = await server.pluginContainer.resolveId(name)
          const mod = resolved ? await server.transformRequest(resolved.id) : null
          if (!mod) {
            w.statusCode = 500
            return w.end(`failed to transform shared dep ${name}`)
          }
          w.setHeader('Content-Type', 'application/javascript')
          w.end(mod.code)
        } catch (e) {
          w.statusCode = 500
          w.end(String(e))
        }
      })
    },
  }
}
