<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  baiduSyncApi,
  type BaiduSyncPreview,
  type BaiduSyncPreviewItem,
  type BaiduSyncProgressItem,
  type BaiduSyncSettingsView,
  type BaiduSyncStatus,
} from '../api/baiduSyncApi'

const settings = ref<BaiduSyncSettingsView | null>(null)
const status = ref<BaiduSyncStatus | null>(null)
const previewResult = ref<BaiduSyncPreview | null>(null)
const appName = ref('')
const appKey = ref('')
const secretKey = ref('')
const signKey = ref('')
const code = ref('')
const busy = ref('')
const error = ref('')
let pollTimer: number | undefined
const router = useRouter()
const progressByKey = computed(() => new Map(
  (status.value?.items ?? []).map((item) => [`${item.action}:${item.path}`, item] as const),
))
const displayItems = computed<Array<BaiduSyncPreviewItem | BaiduSyncProgressItem>>(() =>
  (status.value?.items?.length ?? 0) > 0 ? status.value!.items : previewResult.value?.items ?? [],
)
const processedCount = computed(() =>
  (status.value?.items ?? []).filter((item) => item.state === 'done' || item.state === 'failed').length,
)
const failedCount = computed(() =>
  (status.value?.items ?? []).filter((item) => item.state === 'failed').length,
)
const progressTotal = computed(() => status.value?.items?.length ?? 0)

function backToNotes() {
  void router.push('/notes')
}

async function refresh() {
  try {
    settings.value = await baiduSyncApi.settings()
    status.value = await baiduSyncApi.status()
    appName.value = settings.value.appName
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function saveSettings() {
  busy.value = '正在保存配置'
  error.value = ''
  try {
    settings.value = await baiduSyncApi.saveSettings({
      appName: appName.value,
      appKey: appKey.value,
      secretKey: secretKey.value,
      signKey: signKey.value,
    })
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = ''
  }
}

async function authorize() {
  error.value = ''
  const authorizationTab = window.open('', '_blank')
  try {
    const url = await baiduSyncApi.authorizationUrl()
    if (authorizationTab) {
      authorizationTab.opener = null
      authorizationTab.location.href = url
    } else {
      error.value = '浏览器阻止了授权窗口，请允许弹出窗口后重试'
    }
  } catch (e) {
    authorizationTab?.close()
    error.value = (e as Error).message
  }
}

async function exchangeCode() {
  if (!code.value.trim()) return
  busy.value = '正在完成百度授权'
  error.value = ''
  try {
    settings.value = await baiduSyncApi.exchangeCode(code.value.trim())
    code.value = ''
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = ''
  }
}

async function startSync() {
  busy.value = '正在启动同步'
  error.value = ''
  const pendingItems = (previewResult.value?.items ?? []).map((item) => ({
    ...item,
    state: 'pending' as const,
    error: null,
  }))
  previewResult.value = null
  status.value = {
    running: true,
    uploaded: 0,
    downloaded: 0,
    deleted: 0,
    conflicts: 0,
    error: '',
    items: pendingItems,
  }
  try {
    const pending = baiduSyncApi.sync()
    busy.value = '同步进行中'
    status.value = await pending
  } catch (e) {
    error.value = (e as Error).message
    if (status.value) status.value = { ...status.value, running: false }
  } finally {
    busy.value = ''
  }
}

async function previewSync() {
  busy.value = '正在扫描文件差异'
  error.value = ''
  previewResult.value = null
  if (status.value) status.value = { ...status.value, items: [], error: '' }
  try {
    previewResult.value = await baiduSyncApi.preview()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = ''
  }
}

function previewActionLabel(action: BaiduSyncPreviewItem['action']) {
  switch (action) {
    case 'upload': return '上传到网盘'
    case 'download': return '下载到本地'
    case 'delete-local': return '删除本地文件'
    case 'delete-remote': return '删除网盘文件'
    case 'overwrite-local': return '覆盖本地文件'
    case 'overwrite-remote': return '覆盖网盘文件'
  }
}

function progressState(item: BaiduSyncPreviewItem | BaiduSyncProgressItem): BaiduSyncProgressItem['state'] {
  return progressByKey.value.get(`${item.action}:${item.path}`)?.state ?? 'pending'
}

function progressMark(item: BaiduSyncPreviewItem | BaiduSyncProgressItem) {
  switch (progressState(item)) {
    case 'done': return '✓'
    case 'running': return '…'
    case 'failed': return '!'
    default: return '○'
  }
}

function progressLabel(item: BaiduSyncPreviewItem | BaiduSyncProgressItem) {
  switch (progressState(item)) {
    case 'done': return '已完成'
    case 'running': return '进行中'
    case 'failed': return '失败'
    default: return '待处理'
  }
}

function progressError(item: BaiduSyncPreviewItem | BaiduSyncProgressItem) {
  return progressByKey.value.get(`${item.action}:${item.path}`)?.error ?? ''
}

async function setAutoSync(event: Event) {
  const value = (event.target as HTMLInputElement).checked
  busy.value = '正在更新自动同步设置'
  error.value = ''
  try {
    settings.value = await baiduSyncApi.setAutoSync(value)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = ''
  }
}

onMounted(async () => {
  await refresh()
  pollTimer = window.setInterval(async () => {
    if (status.value?.running) {
      status.value = await baiduSyncApi.status().catch(() => status.value)
    }
  }, 1500)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
})
</script>

<template>
  <section class="baidu-settings">
    <header class="page-head">
      <h3>百度网盘双向同步</h3>
      <button class="btn sm ghost" @click="backToNotes">返回笔记</button>
    </header>
    <p class="muted small">
      同步笔记根目录中的文件和文件夹；应用配置、认证信息与同步清单目录会排除在同步外。
    </p>
    <p v-if="settings" class="small muted path">本地：{{ settings.notesRoot }}</p>
    <p v-if="settings?.remoteDirectory" class="small muted path">网盘：{{ settings.remoteDirectory }}</p>

    <label class="field">
      <span>百度应用名称</span>
      <input v-model="appName" class="input" autocomplete="off" :disabled="!!busy">
    </label>
    <label class="field">
      <span>AppKey <i v-if="settings?.hasAppKey">（已保存；留空保留）</i></span>
      <input v-model="appKey" class="input" autocomplete="off" :disabled="!!busy">
    </label>
    <label class="field">
      <span>SecretKey <i v-if="settings?.hasSecretKey">（已保存；留空保留）</i></span>
      <input v-model="secretKey" class="input" type="text" autocomplete="off" :disabled="!!busy">
    </label>
    <label class="field">
      <span>SignKey <i v-if="settings?.hasSignKey">（已保存；留空保留）</i></span>
      <input v-model="signKey" class="input" type="text" autocomplete="off" :disabled="!!busy">
    </label>
    <p class="small muted security-note">
      认证信息以明文保存在当前笔记根目录的 plugins/baidu-sync 中；同步范围由插件清单限定，
      百度插件自身目录与 AI 凭据不会上传。
    </p>
    <button class="btn sm primary" :disabled="!!busy" @click="saveSettings">保存配置</button>

    <div class="auth-row">
      <button class="btn sm" :disabled="!!busy || !settings?.hasAppKey || !settings?.hasSecretKey" @click="authorize">
        打开百度授权
      </button>
      <input v-model="code" class="input auth-code" placeholder="粘贴百度授权码" :disabled="!!busy">
      <button class="btn sm" :disabled="!!busy || !code.trim()" @click="exchangeCode">确认授权</button>
    </div>
    <p class="small" :class="settings?.authorized ? 'ok' : 'muted'">
      {{ settings?.authorized ? '百度网盘已授权' : '尚未完成百度网盘授权' }}
    </p>

    <div class="sync-row">
      <button class="btn sm" :disabled="!!busy || status?.running || !settings?.authorized" @click="previewSync">
        预览文件差异
      </button>
    </div>
    <p class="small muted security-note">差异预览只列出文件操作，不会上传、下载或删除文件。</p>
    <section v-if="previewResult || progressTotal" class="preview-result">
      <p v-if="previewResult && !previewResult.remoteDirectoryExists" class="small muted status">
        网盘目标目录尚未创建，差异按空网盘目录计算。
      </p>
      <p v-if="previewResult" class="small status">
        待上传 {{ previewResult.uploadCount }}，待下载 {{ previewResult.downloadCount }}，
        待删除 {{ previewResult.deleteCount }}，覆盖 {{ previewResult.overwriteCount }}，
        双端一致 {{ previewResult.consistentCount }}，双端冲突 {{ previewResult.conflictCount }}。
      </p>
      <p v-if="previewResult" class="small muted status">
        只有存在历史共同基线且两端摘要都变化时，才能确认双端修改；缺少基线时无法判断变更来源，覆盖方向按文件时间戳选择。
      </p>
      <p v-if="!previewResult && status?.running" class="small muted status">正在读取并逐项处理文件操作…</p>
      <p v-if="progressTotal" class="small muted status">
        已处理 {{ processedCount }} / {{ progressTotal }}
        <span v-if="failedCount">，失败 {{ failedCount }}</span>
      </p>
      <div v-if="displayItems.length" class="preview-list">
        <div
          v-for="item in displayItems"
          :key="`${item.action}:${item.path}`"
          class="preview-item"
          :class="`is-${progressState(item)}`"
        >
          <span class="progress-mark">{{ progressMark(item) }}</span>
          <span>{{ previewActionLabel(item.action) }}</span>
          <code>{{ item.path }}</code>
          <span v-if="item.conflict" class="conflict">双端修改，按较新版本覆盖</span>
          <span class="progress-state">{{ progressLabel(item) }}</span>
          <span v-if="progressError(item)" class="item-error">{{ progressError(item) }}</span>
        </div>
      </div>
      <p v-else class="small muted status">当前没有待处理的文件差异。</p>
    </section>

    <div class="sync-row">
      <button class="btn sm primary" :disabled="!!busy || status?.running || !settings?.authorized" @click="startSync">
        {{ status?.running ? '同步中…' : '立即同步' }}
      </button>
      <label class="auto-sync">
        <input type="checkbox" :checked="settings?.autoSync" :disabled="!!busy || !settings?.authorized" @change="setAutoSync">
        每 5 分钟自动同步
      </label>
    </div>

    <p v-if="status" class="small muted status">
      上传 {{ status.uploaded }}，下载 {{ status.downloaded }}，删除 {{ status.deleted }}，冲突覆盖 {{ status.conflicts }}
    </p>
    <p v-if="busy" class="small muted">{{ busy }}…</p>
    <p v-if="error || status?.error" class="small error">{{ error || status?.error }}</p>
  </section>
</template>

<style scoped>
.baidu-settings { display: flex; flex-direction: column; gap: 9px; width: 100%; max-width: 760px; min-height: 100%; box-sizing: border-box; padding: var(--space-4); }
.baidu-settings h3 { margin: 4px 0 0; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
.muted { color: var(--c-text-3); }
.small { font-size: 12px; }
.path { margin: 0; overflow-wrap: anywhere; }
.field { display: grid; grid-template-columns: 150px minmax(0, 1fr); align-items: center; gap: 10px; }
.field i { color: var(--c-text-3); font-style: normal; }
.security-note { margin: 0; }
.auth-row, .sync-row { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.auth-code { flex: 1; min-width: 180px; }
.auto-sync { display: flex; align-items: center; gap: 6px; font-size: 12px; }
.status { margin: 0; }
.preview-result { display: flex; flex-direction: column; gap: 7px; }
.preview-list { display: flex; flex-direction: column; gap: 5px; max-height: 320px; overflow: auto; padding: 8px; border: 1px solid var(--c-border); border-radius: 6px; }
.preview-item { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; font-size: 12px; }
.preview-item code { overflow-wrap: anywhere; }
.preview-item.is-done { color: var(--c-text-3); }
.preview-item.is-failed { color: var(--danger, #d55); }
.progress-mark { flex: 0 0 16px; width: 16px; text-align: center; font-weight: 700; }
.is-pending .progress-mark { color: var(--c-text-3); }
.is-running .progress-mark { color: var(--accent); }
.is-done .progress-mark { color: var(--success, #3a9); }
.is-failed .progress-mark { color: var(--danger, #d55); }
.progress-state { margin-left: auto; color: var(--c-text-3); }
.is-running .progress-state { color: var(--accent); }
.is-done .progress-state { color: var(--success, #3a9); }
.is-failed .progress-state { color: var(--danger, #d55); }
.item-error { flex-basis: 100%; padding-left: 24px; color: var(--danger, #d55); }
.conflict { color: var(--danger, #d55); }
.error { color: var(--danger, #d55); }
.ok { color: var(--success, #3a9); }
@media (max-width: 560px) {
  .field { grid-template-columns: 1fr; gap: 4px; }
}
</style>
