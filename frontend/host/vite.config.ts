import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 主应用（qiankun 宿主）的构建与开发服务器。
 *
 * <p><b>插件界面不在这份 bundle 里</b>：每个插件界面是一个 qiankun 微应用，有自己的
 * dev server（开发）与自己的产物（生产，`/plugin-assets/<id>/`，后端从磁盘目录提供）。
 * 主应用在运行时按启用列表 loadMicroApp 装载它们（见 src/plugins/loader.ts）。
 *
 * <p>共享运行时不再是 import map + /plugin-runtime.js：qiankun 的 ESM 沙箱里
 * URL 模块是私有实例，宿主改为把自己的 vue / vue-router / @thisnode/plugin-api
 * 命名空间注入微应用沙箱（sandbox.modules），微应用里的裸 import 解析到的就是
 * 同一份实例。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: false,
    proxy: {
      // 后端地址可用 THISNODE_BACKEND 覆盖（默认 45917）：起第二个实例做验证时不用停正在跑的那个。
      '/api': {
        target: process.env.THISNODE_BACKEND ?? 'http://127.0.0.1:45917',
        changeOrigin: true,
      },
      // 插件界面资源由后端从磁盘目录端出来（开发期 dev server 没起时的兜底）
      '/plugin-assets': {
        target: process.env.THISNODE_BACKEND ?? 'http://127.0.0.1:45917',
        changeOrigin: true,
      },
    },
  },
  build: {
    // 产物分离：产物落在本包 dist/（vite 默认），由界面侧自行部署；后端 jar 不再内嵌任何 UI。
    // 开发期 dev server（5173）经代理访问后端（/api、/plugin-assets）。
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1200,
    rollupOptions: {
      output: {
        manualChunks: {
          qiankun: ['qiankun'],
          vendor: ['vue', 'vue-router', 'pinia', 'markdown-it'],
        },
      },
    },
  },
  clearScreen: false,
})
