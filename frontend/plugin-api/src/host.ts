import type { Component } from 'vue'

/**
 * 宿主（核心界面侧）提供给插件的服务。
 *
 * <p>为什么用"注入"而不是让插件直接 import 核心：插件界面没有 Maven 那种编译期强制，
 * 如果允许它 `import { useUiStore } from '@/stores/ui'`，边界立刻退化成约定。
 * 这里反过来：**契约定义在包内，实现由核心在启动时装上**（见 installHostServices），
 * 插件只能用 `host()` 拿到它被允许使用的那几样东西。
 */
export interface PluginHostServices {
  /** 轻提示（核心负责渲染，插件不关心它是 toast 还是别的） */
  notify(message: string, kind?: 'success' | 'error' | 'info'): void
  /** 体积/时间格式化 —— 插件列表里要显示"上次同步于…"这类文案 */
  formatBytes(bytes: number): string
  formatRelative(timestamp: number): string
  /** 渲染 Markdown 的共享组件（复习页要用它显示卡片正文） */
  MarkdownView: Component
  /**
   * 把某篇笔记装进核心 store（加载详情与卡片），**不做任何路由跳转**。
   * 插件随后渲染 NotePage 即可显示它。
   */
}

let installed: PluginHostServices | null = null

export function installHostServices(services: PluginHostServices): void {
  installed = services
}

export function host(): PluginHostServices {
  if (!installed) {
    throw new Error('插件宿主服务尚未安装：核心应在 main.ts 里调用 installHostServices()')
  }
  return installed
}
