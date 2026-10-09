import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { qiankun } from '@qiankunjs/bundler-plugin/vite'
import { qiankunSharedDeps } from '@thisnode/plugin-api/vite'

/**
 * 模糊搜索插件 —— 纯界面侧服务插件：能力只有一个顶栏查询框（跨标题/摘要模糊匹配 + 下拉跳转）。
 * 共享依赖（vue / vue-router / @thisnode/plugin-api）外置，由宿主沙箱注入同一份实例。
 */
export default defineConfig({
  plugins: [vue(), qiankun(), qiankunSharedDeps()],
  server: {
    port: 7115,
    strictPort: true,
  },
  build: {
    lib: {
      entry: 'index.ts',
      formats: ['es'],
      fileName: () => 'plugin.js',
      cssFileName: 'plugin',
    },
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      external: ['vue', 'vue-router', '@thisnode/plugin-api'],
    },
  },
})
