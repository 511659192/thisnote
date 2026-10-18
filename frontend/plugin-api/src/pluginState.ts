/**
 * 插件状态变化事件。
 *
 * <p>插件改动自己的数据（收藏、记忆曲线…）后调用 {@link emitPluginStateChange} 广播；
 * 核心里需要联动的视图（侧栏计数、笔记页卡片徽标…）用 {@link onPluginStateChange} 订阅。
 * 核心不认识任何具体插件，插件也不必知道谁在听。
 */
export interface PluginStateChange {
  pluginId: string
  /** 受影响的笔记 id（收藏这类按笔记变化的状态）。 */
  noteIds?: string[]
  /** 受影响的卡片 id（记忆曲线这类按卡片变化的状态）。 */
  cardIds?: string[]
}

type PluginStateListener = (change: PluginStateChange) => void

const listeners = new Set<PluginStateListener>()

/** 订阅插件状态变化；返回取消订阅的函数。 */
export function onPluginStateChange(listener: PluginStateListener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** 广播一次状态变化；单个订阅方抛错不影响其它订阅。 */
export function emitPluginStateChange(change: PluginStateChange): void {
  for (const listener of [...listeners]) {
    try {
      listener(change)
    } catch {
      // 订阅方异常不应打断插件自己的流程
    }
  }
}
