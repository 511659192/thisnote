import { createReadStream, existsSync, statSync } from 'node:fs'
import { extname, join } from 'node:path'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 插件界面资源的 **dev 兜底**：某个插件的 dev server 没起时，宿主会回退到
 * `/plugin-assets/<id>/...`。这个路径不再经后端 —— 直接由本 dev server 从界面侧的
 * 交付目录 `frontend/runtime/plugins/` 端出（与部署时静态服务的布局一致）。
 */
function pluginAssetsDevServer() {
  const root = fileURLToPath(new URL('../runtime/plugins', import.meta.url))
  const TYPES: Record<string, string> = {
    '.js': 'text/javascript; charset=utf-8',
    '.mjs': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json',
    '.map': 'application/json',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.woff2': 'font/woff2',
  }
  return {
    name: 'thisnode-plugin-assets-dev',
    configureServer(server: { middlewares: { use: (fn: (req: any, res: any, next: () => void) => void) => void } }) {
      server.middlewares.use((req, res, next) => {
        const url = String(req.url ?? '').split('?')[0]
        if (!url.startsWith('/plugin-assets/')) return next()
        const rel = decodeURIComponent(url.slice('/plugin-assets/'.length))
        const file = join(root, rel)
        if (rel.includes('..') || !file.startsWith(root) || !existsSync(file) || !statSync(file).isFile()) {
          res.statusCode = 404
          return res.end()
        }
        res.setHeader('Content-Type', TYPES[extname(file).toLowerCase()] ?? 'application/octet-stream')
        res.setHeader('Cache-Control', 'no-cache')
        createReadStream(file).pipe(res)
      })
    },
  }
}

/**
 * 主应用（qiankun 宿主）的构建与开发服务器。
 *
 * <p><b>插件界面不在这份 bundle 里</b>：每个插件界面是一个 qiankun 微应用，有自己的
 * dev server（开发）与自己的产物（生产，`/plugin-assets/<id>/`，由界面侧的静态服务提供）。
 * 主应用在运行时按启用列表 loadMicroApp 装载它们（见 src/plugins/loader.ts）。
 *
 * <p>共享运行时不再是 import map + /plugin-runtime.js：qiankun 的 ESM 沙箱里
 * URL 模块是私有实例，宿主改为把自己的 vue / vue-router / @thisnode/plugin-api
 * 命名空间注入微应用沙箱（sandbox.modules），微应用里的裸 import 解析到的就是
 * 同一份实例。
 */
export default defineConfig({
  plugins: [vue(), pluginAssetsDevServer()],
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
    },
  },
  build: {
    // 产物分离：产物落在本包 dist/（vite 默认），由界面侧自行部署；后端 jar 不再内嵌任何 UI。
    // 开发期 dev server（5173）经代理访问后端（/api）；插件界面资源由本 dev server 直接端出。
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
