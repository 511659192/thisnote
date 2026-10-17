/**
 * 兼容转发：HTTP 客户端已搬进 `@thisnode/plugin-api`（插件与核心共用同一份）。
 * 保留原路径，核心内部大量 `@/core/http` 引用不必逐处改。
 */
export { apiBase, ApiError, backendMessage, backendOnline, http, request, waitForBackend } from '@thisnode/plugin-api'