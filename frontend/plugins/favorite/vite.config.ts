import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { qiankun } from '@qiankunjs/bundler-plugin/vite'
import { qiankunSharedDeps } from '@thisnode/plugin-api/vite'

/**
 * 收藏插件 —— qiankun 微应用的构建与开发服务器。
 *
 * <p>产物仍是一个 ES 模块（`plugin.js`），落在模块的 **frontend-dist/**（前后端分离：不进 jar），
 * Maven 打包后由 antrun 拷到 backend/plugins-dist/frontend/，生产由后端 `/plugin-assets/favorite/` 从磁盘端出。
 * vue / vue-router / `@thisnode/plugin-api` 一律**外置**：qiankun ESM 沙箱里的 URL 模块是私有实例，
 * 这些共享依赖由宿主通过 `sandbox.modules` 注入同一份实例（裸 import 解析）。
 *
 * <p>`qiankun()`：dev server 加 CORS 头（宿主跨源拉取模块图需要）；`qiankunSharedDeps()`：
 * 开发期把共享依赖拦截成哨兵路径（见 @thisnode/plugin-api/vite），宿主的 resolveHook
 * 把它映射回裸名再走注入表 —— 独立运行（直接打开本 dev server）时由它的中间件兜底。
 */
export default defineConfig({
  plugins: [vue(), qiankun(), qiankunSharedDeps()],
  server: {
    port: 7111,
    strictPort: true,
  },
  build: {
    lib: {
      entry: 'index.ts',
      formats: ['es'],
      fileName: () => 'plugin.js',
      cssFileName: 'plugin',
    },
    // 前后端分离：产物落在前端自己的 dist/（backend 树里不留任何前端产物），
    // 打包时由 antrun 从这里拷到 backend/plugins-dist/frontend/<id>/，/plugin-assets 从那里端出。
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      output: { chunkFileNames: 'chunk-[hash].js' },  // 动态 import 出来的分片，别落进 assets/ 子目录
      external: ['vue', 'vue-router', '@thisnode/plugin-api'],
    },
  },
})
