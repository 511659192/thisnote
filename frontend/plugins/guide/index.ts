import { createPluginLifecycle, registerNavEntry, registerPluginRoute } from '@thisnote/plugin-api'

const PLUGIN_ID = 'guide'

function register(): void {
  registerNavEntry({
    pluginId: PLUGIN_ID,
    label: '使用指南',
    glyph: '?',
    to: '/guide',
    order: -10,
  })
  registerPluginRoute({
    pluginId: PLUGIN_ID,
    path: '/guide',
    name: 'guide',
    onboarding: true,
    component: () => import('./src/views/GuideView.vue'),
  })
}

export const { bootstrap, mount, unmount } = createPluginLifecycle({
  pluginId: PLUGIN_ID,
  register,
  standaloneHint: 'thisnote 使用指南：在宿主侧栏打开“使用指南”查看完整流程。',
})
