import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
// 触发插件自注册（副作用 import）：页面路由由插件登记，核心路由表里不出现任何业务路径。

/**
 * Hash history：路径模式刷新会 404（后端纯 API，不再端出页面）。
 *
 * 前后端分离后，宿主是**纯壳**：这里没有任何业务路由 —— /notes、/notes/:id 由
 * notes 微应用经 registerPluginRoute 登记（挂到 Shell 之下）。装载完成前，
 * 根路径与未知深链接落到 ShellHome 占位页，它在插件路由就绪后自动接过去。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'shell',
    component: () => import('@/components/Shell.vue'),
    children: [
      { path: '', name: 'home', component: () => import('@/components/ShellHome.vue') },
      { path: 'settings', name: 'settings', component: () => import('@/views/SettingsView.vue') },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

/**
 * 插件自有页面（笔记页、记忆曲线的复习页、收藏插件的收藏页…）挂在 Shell 之下，
 * 这样它们和核心页面一样带侧栏与顶栏。静态路径比 catch-all 更具体，会优先匹配。
 */

export default router
