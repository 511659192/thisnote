<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  attachmentImporterApi,
  type FolderImportResult,
} from '../api/attachmentImporterApi'

const folderPath = ref('')
const importing = ref(false)
const error = ref('')
const result = ref<FolderImportResult | null>(null)
const canImport = computed(() => folderPath.value.trim().length > 0 && !importing.value)

async function importFolder() {
  if (!canImport.value) return
  importing.value = true
  error.value = ''
  result.value = null
  try {
    result.value = await attachmentImporterApi.importFolder(folderPath.value.trim())
  } catch (e) {
    error.value = `导入失败：${(e as Error).message}`
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <section class="attachment-importer-settings">
    <h3>附件自动导入</h3>
    <p>
      插件每 10 秒扫描一次 <code>notes/attachments</code>：新出现的图片只要还没有被任何卡片引用，
      就自动生成一篇笔记和一张图片卡片，归入「收集箱」。
    </p>
    <ul>
      <li>已被卡片引用的图片会跳过，编辑器里粘贴的图片不会重复生成笔记。</li>
      <li>未管理的附件会改名为 <code>&lt;uuid&gt;.&lt;扩展名&gt;</code>，原始文件名记入附件元数据，并作为笔记标题。</li>
      <li>自动扫描每轮都按「没有被任何卡片引用」判断，引用关系由 notes 提供；已被引用的图片不会重复生成笔记。</li>
      <li>手动文件夹导入按原文件名与 notes 的待管理附件/元数据判重，相同文件名再次导入会跳过。</li>
      <li>卡片框体按图片原始尺寸生成（宽度上限 680），打开时图片铺满卡片，不留大片空白。</li>
      <li>只处理 png / jpg / webp，其它文件与非普通文件一律跳过。</li>
    </ul>

    <div class="manual-import">
      <h4>从文件夹导入</h4>
      <p class="small muted">
        输入本地文件夹的绝对路径，后端会把图片复制到待管理附件目录，不经过浏览器上传。
        子文件夹中的图片也会复制，之后由定时任务接管并生成笔记。
      </p>
      <div class="folder-row">
        <input
          v-model="folderPath"
          class="input"
          placeholder="例如 D:\Pictures\待导入"
          :disabled="importing"
        />
        <button type="button" class="btn sm primary" :disabled="!canImport" @click="importFolder">
          {{ importing ? '正在导入…' : '导入图片' }}
        </button>
      </div>
      <p v-if="error" class="feedback error">{{ error }}</p>
      <p v-if="result" class="feedback success">
        复制完成：成功 {{ result.imported }} 张，重复 {{ result.duplicates }} 张，跳过 {{ result.skipped }} 张。
        图片将在下一轮自动扫描时生成笔记。
      </p>
      <ul v-if="result?.errors.length" class="error-list">
        <li v-for="item in result.errors" :key="item">{{ item }}</li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.attachment-importer-settings {
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
