import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { qiankun } from '@qiankunjs/bundler-plugin/vite'
import { qiankunSharedDeps } from '@thisnode/plugin-api/vite'

/**
 * 记忆曲线插件 —— qiankun 微应用的构建与开发服务器（端口约定 7112，宿主按它找过来）。
 *
 * <p>产物仍是一个 ES 模块（`plugin.js`），落在模块的 **frontend-dist/**（前后端分离：不进 jar），
 * Maven 打包后由 antrun 拷到 backend/plugins-dist/frontend/，生产由后端 `/plugin-assets/memory-curve/` 从磁盘端出。
 * vue / vue-router / `@thisnode/plugin-api` 一律**外置**，由宿主通过 qiankun 沙箱的 modules 表注入同一份实例。
 * `qiankun()` 给 dev server 加 CORS（宿主跨源拉模块图）；`qiankunSharedDeps()` 在开发期
 * 把共享依赖拦截成哨兵路径（独立运行时由其兜底中间件解析到本插件自己的依赖）。
 */
export default defineConfig({
  plugins: [vue(), qiankun(), qiankunSharedDeps()],
  server: {
    port: 7112,
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
