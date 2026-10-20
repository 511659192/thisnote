<script setup lang="ts">
import { computed, ref } from 'vue'
import { cardImporterApi, type FolderImportResult } from '../api/cardImporterApi'

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
    result.value = await cardImporterApi.importFolder(folderPath.value.trim())
  } catch (e) {
    error.value = `导入失败：${(e as Error).message}`
  } finally {
    importing.value = false
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
          :disabled="importing"
        />
        <button type="button" class="btn sm primary" :disabled="!canImport" @click="importFolder">
          {{ importing ? '正在导入…' : '导入卡片' }}
        </button>
      </div>
      <p v-if="error" class="feedback error">{{ error }}</p>
      <p v-if="result" class="feedback success">
        复制完成：新增 {{ result.imported }} 个，重复 {{ result.duplicates }} 个，跳过 {{ result.skipped }} 个。
        卡片将在下一轮自动扫描时生成笔记。
      </p>
      <ul v-if="result?.errors.length" class="error-list">
        <li v-for="item in result.errors" :key="item">{{ item }}</li>
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
