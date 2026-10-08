import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { qiankun } from '@qiankunjs/bundler-plugin/vite'
import { qiankunSharedDeps } from '@thisnode/plugin-api/vite'

/**
 * 记忆曲线插件 —— qiankun 微应用的构建与开发服务器（端口约定 7112，宿主按它找过来）。
 *
 * <p>产物仍是一个 ES 模块（`plugin.js`），打进**插件自己的 jar**；vue / vue-router /
 * `@thisnode/plugin-api` 一律**外置**，由宿主通过 qiankun 沙箱的 modules 表注入同一份实例。
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
    // 产物直落插件模块（backend/plugins/memory-curve）的 resources，随 jar 发布 —— 之前少写了
    // backend/，构建产物落到仓库根的 frontend/plugin-memory-curve/，jar 里一直是旧产物。
    outDir: '../../../backend/plugins/memory-curve/src/main/resources/plugin/memory-curve/frontend',  // 必须是子目录：emptyOutDir 只清它自己，不致误删同级的 migration 等资源
    emptyOutDir: true,
    rollupOptions: {
      output: { chunkFileNames: 'chunk-[hash].js' },  // 动态 import 出来的分片，别落进 assets/ 子目录
      external: ['vue', 'vue-router', '@thisnode/plugin-api'],
    },
  },
})
