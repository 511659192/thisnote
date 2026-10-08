import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import { waitForBackend } from './core/http'
import { installHostServices } from '@thisnode/plugin-api'
import { loadActivePluginFrontends } from './plugins/loader'
import { useUiStore } from './stores/ui'
import { formatBytes, formatRelative } from './core/utils'
import MarkdownView from './components/MarkdownPreview.vue'



import './styles/theme.css'

async function bootstrap() {
  // 路由在模块 import 时就开始了初始导航，而插件路由要等到装载之后才存在：
  // 此时 #/favorites 这类深链接会被 catch-all 重定向到 /notes。记下它，装载完再解析一次。
  const initialHash = window.location.hash
  const app = createApp(App)
  app.use(createPinia())
  app.use(router)

  // 把核心的实现装给插件：契约在 @thisnode/plugin-api，实现在核心。
  // notify 用闭包延迟取 store（这里还没挂载，实际调用发生在用户交互时）。
  installHostServices({
    notify: (message, kind) => useUiStore().toast(message, kind),
    formatBytes,
    formatRelative,
    MarkdownView,
  })

  // 后端与界面同源（同一个端口），启动时等一下它可用，
  // 免得各视图的首个请求打在还没起来的端口上。
  await waitForBackend(20000)

  // 按后端启用列表装载插件前端（生产 = 插件 jar 里的 plugin.js）。
  // 放在 isReady 之前：深链接（#/review、#/favorites）才能一次就匹配上插件路由。
  await loadActivePluginFrontends(router)

  // Resolve the initial route BEFORE mounting. Mounting first and re-pushing the current path
  // raced the router's own initial navigation: currentRoute was still the start location '/',
  // so the re-push redirected EVERY deep link (#/review, #/notes/:id) back to the list.
  await router.isReady()
  if (initialHash && window.location.hash !== initialHash) {
    await router.replace(initialHash.slice(1))
  }
  app.mount('#app')
}

void bootstrap()
