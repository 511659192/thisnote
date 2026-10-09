import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { qiankun } from '@qiankunjs/bundler-plugin/vite'
import { qiankunSharedDeps } from '@thisnode/plugin-api/vite'

/**
 * 标题搜索插件 —— 服务插件的最小参考实现：能力全在前端（列表头部搜索框），
 * 没有端点也没有数据。共享依赖（vue / @thisnode/plugin-api）外置，由宿主沙箱注入。
 */
export default defineConfig({
  plugins: [vue(), qiankun(), qiankunSharedDeps()],
  server: {
    port: 7114,
    strictPort: true,
  },
  build: {
    lib: {
      entry: 'index.ts',
      formats: ['es'],
      fileName: () => 'plugin.js',
      cssFileName: 'plugin',
    },
    // 前后端分离：产物落前端自己的 dist/，打包时 antrun 拷到 plugins-dist/frontend/search/。
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      external: ['vue', 'vue-router', '@thisnode/plugin-api'],
    },
  },
})
