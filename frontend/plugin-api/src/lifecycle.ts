import { unregisterPlugin } from './registry'

/**
 * 微应用入口的生命周期样板：`mount()` 里执行注册，`unmount()` 里先跑可选的
 * 额外清理再整体注销本插件的全部插槽；独立运行（直接打开插件 dev server、
 * 不在 qiankun 沙箱里）时 import 即注册，并在 `#plugin-root` 里给出占位说明。
 *
 * <p>返回值直接再导出即为 qiankun 要求的 bootstrap / mount / unmount 三个生命周期。
 */
export function createPluginLifecycle(options: {
  pluginId: string
  register: () => void
  /** unmount 时先于插槽注销执行的额外清理（如清空模块级搜索状态）。 */
  beforeUnmount?: () => void
  standaloneHint: string
}): {
  bootstrap: () => Promise<void>
  mount: () => Promise<void>
  unmount: () => Promise<void>
} {
  if (!window.__POWERED_BY_QIANKUN__) {
    options.register()
    document.getElementById('plugin-root')?.replaceChildren(
      Object.assign(document.createElement('p'), {
        textContent: options.standaloneHint,
      }),
    )
  }

  return {
    bootstrap: async () => {},
    mount: async () => {
      options.register()
    },
    unmount: async () => {
      options.beforeUnmount?.()
      unregisterPlugin(options.pluginId)
    },
  }
}
