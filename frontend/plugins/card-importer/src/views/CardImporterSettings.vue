<script setup lang="ts">
import { computed, ref } from 'vue'
import { cardImporterApi, type FolderImportResult } from '../api/cardImporterApi'

const folderPath = ref('')
const importingFolder = ref(false)
const folderError = ref('')
const folderResult = ref<FolderImportResult | null>(null)
const canImportFolder = computed(() => folderPath.value.trim().length > 0 && !importingFolder.value)

const fileInput = ref<HTMLInputElement | null>(null)
const selectedFiles = ref<File[]>([])
const uploading = ref(false)
const uploadError = ref('')
const uploadResult = ref<FolderImportResult | null>(null)
const canUpload = computed(() => selectedFiles.value.length > 0 && !uploading.value)

async function importFolder() {
  if (!canImportFolder.value) return
  importingFolder.value = true
  folderError.value = ''
  folderResult.value = null
  try {
    folderResult.value = await cardImporterApi.importFolder(folderPath.value.trim())
  } catch (e) {
    folderError.value = `导入失败：${(e as Error).message}`
  } finally {
    importingFolder.value = false
  }
}

function onUploadFilesSelected(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFiles.value = Array.from(input.files ?? [])
  uploadError.value = ''
  uploadResult.value = null
}

async function importUploads() {
  if (!canUpload.value) return
  uploading.value = true
  uploadError.value = ''
  uploadResult.value = null
  try {
    uploadResult.value = await cardImporterApi.importUploads(selectedFiles.value)
    selectedFiles.value = []
    if (fileInput.value) fileInput.value.value = ''
  } catch (e) {
    uploadError.value = `上传失败：${(e as Error).message}`
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <section class="card-importer-settings">
    <h3>卡片自动导入</h3>
    <p>插件每 10 秒扫描一次笔记目录中的 <code>notes/cards/*.md</code>，只处理卡片元数据里没有登记的 Markdown 文件（无主卡片）。</p>
    <ul>
      <li>已在笔记元数据中关联的卡片会跳过。</li>
      <li>每个未关联的 Markdown 文件会创建为一条笔记，标题取文件名并放入收集箱。</li>
      <li>有效且未冲突的卡片 ID 和布局信息会保留；缺失或冲突的卡片 ID 会重新生成。</li>
    </ul>

    <div class="manual-import">
      <h4>从文件夹导入</h4>
      <p class="small muted">
        输入本地文件夹的绝对路径，后端会把其中的 Markdown 卡片复制到待导入目录，不经过浏览器上传。
        子文件夹中的卡片也会复制，之后由定时任务生成笔记。
      </p>
      <div class="folder-row">
        <input
          v-model="folderPath"
          class="input"
          placeholder="例如 D:\Notes\待导入卡片"
          :disabled="importingFolder"
        />
        <button type="button" class="btn sm primary" :disabled="!canImportFolder" @click="importFolder">
          {{ importingFolder ? '正在导入…' : '导入卡片' }}
        </button>
      </div>
      <p v-if="folderError" class="feedback error">{{ folderError }}</p>
      <p v-if="folderResult" class="feedback success">
        复制完成：新增 {{ folderResult.imported }} 个，重复 {{ folderResult.duplicates }} 个，跳过 {{ folderResult.skipped }} 个。
        卡片将在下一轮自动扫描时生成笔记。
      </p>
      <ul v-if="folderResult?.errors.length" class="error-list">
        <li v-for="item in folderResult.errors" :key="item">{{ item }}</li>
      </ul>
    </div>

    <div class="manual-import">
      <h4>HTTP 上传</h4>
      <p class="small muted">
        从当前浏览器选择 Markdown 文件，适合 Docker 等后端无法读取浏览器本地路径的场景。
        多个文件会按顺序逐个上传，单个文件不超过 25MB。
      </p>
      <div class="folder-row">
        <input
          ref="fileInput"
          class="input"
          type="file"
          multiple
          accept=".md,text/markdown"
          :disabled="uploading"
          @change="onUploadFilesSelected"
        />
        <button type="button" class="btn sm primary" :disabled="!canUpload" @click="importUploads">
          {{ uploading ? '正在上传…' : '上传并导入' }}
        </button>
      </div>
      <p v-if="selectedFiles.length" class="small muted">已选择 {{ selectedFiles.length }} 个文件。</p>
      <p v-if="uploadError" class="feedback error">{{ uploadError }}</p>
      <p v-if="uploadResult" class="feedback" :class="uploadResult.errors.length ? 'error' : 'success'">
        处理完成：新增 {{ uploadResult.imported }} 个，重复 {{ uploadResult.duplicates }} 个，跳过 {{ uploadResult.skipped }} 个。
        卡片将在下一轮自动扫描时生成笔记。
      </p>
      <ul v-if="uploadResult?.errors.length" class="error-list">
        <li v-for="item in uploadResult.errors" :key="item">{{ item }}</li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.card-importer-settings {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 760px;
}

h3 {
  margin: 4px 0 0;
}

p,
ul {
  margin: 0;
}

ul {
  padding-left: 20px;
}

li + li {
  margin-top: 4px;
}

.manual-import {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
  padding-top: 12px;
  border-top: 1px solid var(--c-border);
}

h4 {
  margin: 0;
  font-size: 13px;
}

.folder-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
}

.input {
  flex: 1;
  min-width: 240px;
  box-sizing: border-box;
  min-height: 32px;
  padding: 5px 8px;
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
}

.small {
  font-size: 12px;
}

.muted {
  color: var(--c-text-3);
}

.feedback {
  margin: 0;
  font-size: 12px;
}

.success {
  color: var(--success);
}

.error {
  color: var(--danger);
}

.error-list {
  max-height: 180px;
  margin: 0;
  overflow-y: auto;
  color: var(--danger);
  font-size: 11px;
}
</style>
