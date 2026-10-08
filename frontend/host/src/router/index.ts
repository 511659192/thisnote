import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
// 触发插件自注册（副作用 import）：页面路由由插件登记，核心路由表里不出现任何插件路径。

/**
 * Hash history：打包后由后端直接提供页面，路径模式刷新会 404。
 * 404 on reload. Views are lazily imported to keep the cold start small (NFR 冷启动 < 3s).
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'shell',
    component: () => import('@/components/Shell.vue'),
    children: [
      { path: '', redirect: '/notes' },
      { path: 'notes', name: 'notes', component: () => import('@/views/NoteView.vue') },
      { path: 'notes/:id', name: 'note', component: () => import('@/views/NoteView.vue'), props: true },
      { path: 'settings', name: 'settings', component: () => import('@/views/SettingsView.vue') },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/notes' },
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

/**
 * 插件自有页面（记忆曲线的复习页、收藏插件的收藏页…）挂在 Shell 之下，
 * 这样它们和核心页面一样带侧栏与顶栏。静态路径比 catch-all 更具体，会优先匹配。
 */

export default router
