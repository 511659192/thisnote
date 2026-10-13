import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { qiankun } from '@qiankunjs/bundler-plugin/vite'
import { qiankunSharedDeps } from '@thisnode/plugin-api/vite'

/**
 * 记忆周期插件 —— qiankun 微应用的构建与开发服务器（端口约定 7112，宿主按它找过来）。
 *
 * <p>产物仍是一个 ES 模块（`plugin.js`），落在模块的 **dist/**（产物分离：不进 jar），
 * 构建后由交付脚本 tools/deliver.mjs 送到 frontend/runtime/plugins/，生产由界面侧静态服务从磁盘端出（URL 仍为 `/plugin-assets/memory-cycle/`）。
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
    // 产物分离：产物落在界面侧自己的 dist/（backend 树里不留任何界面资源），
    // 构建后由交付脚本送到 frontend/runtime/plugins/<id>/，/plugin-assets 从那里端出。
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      output: { chunkFileNames: 'chunk-[hash].js' },  // 动态 import 出来的分片，别落进 assets/ 子目录
      external: ['vue', 'vue-router', '@thisnode/plugin-api'],
    },
  },
})
