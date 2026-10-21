/**
 * 界面侧插件契约（`@thisnote/plugin-api`）。
 *
 * 插件界面只允许依赖这个包：插槽注册、可用性桥、插件管理客户端、HTTP 客户端，
 * 以及由核心注入的宿主服务（notify / 格式化 / Markdown 渲染）。
 * 任何 `@/...` 形式的核心内部引用都不该出现在插件里 —— 有测试守着这条边界。
 */
export * from './types'
export * from './registry'
export * from './availability'
export * from './pluginState'
export * from './noteTitle'
export * from './pluginApi'
export * from './http'
export * from './host'
export * from './shell-state'
export * from './utils'
export { default as ConfirmDialog } from './ui/ConfirmDialog.vue'
export * from './ui/previewTransform'
export * from './lifecycle'
