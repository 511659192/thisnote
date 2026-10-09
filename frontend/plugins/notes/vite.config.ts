import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { qiankun } from '@qiankunjs/bundler-plugin/vite'
import { qiankunSharedDeps } from '@thisnode/plugin-api/vite'

/**
 * 笔记域微应用 —— 前后端拆分对齐后的「核心业务域」：后端它是 plugin-notes（CORE jar），
 * 前端它是 notes 微应用（端口约定 7113）。路由（/notes、/notes/:id）与侧栏主面板
 * 在 mount() 里经 @thisnode/plugin-api 登记，宿主只渲染容器。
 *
 * <p>共享依赖（vue / vue-router / pinia / @thisnode/plugin-api）一律**外置**：qiankun ESM
 * 沙箱里 URL 模块是私有实例，由宿主通过 sandbox.modules 注入同一份 —— 两个 Vue 或两个
 * Pinia 都会让插槽注册与 store 失联。qiankunSharedDeps() 在开发期把共享依赖拦截成哨兵路径，
 * 宿主 resolveHook 映射回裸名；独立运行（直接打开本 dev server）时由它的中间件兜底。
 */
export default defineConfig({
  plugins: [vue(), qiankun(), qiankunSharedDeps()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 7113,
    strictPort: true,
  },
  build: {
    lib: {
      entry: 'index.ts',
      formats: ['es'],
      fileName: () => 'plugin.js',
      cssFileName: 'plugin',
    },
    // 前后端分离：产物落在插件模块的 frontend-dist/（resources 之外，不进 jar），
    // 由 antrun 拷到 backend/plugins-dist/frontend/notes/，/plugin-assets 从那里端出。
    outDir: '../../../backend/plugins/notes/frontend-dist',
    emptyOutDir: true,
    rollupOptions: {
      output: { chunkFileNames: 'chunk-[hash].js' },
      external: ['vue', 'vue-router', 'pinia', '@thisnode/plugin-api'],
    },
  },
})
