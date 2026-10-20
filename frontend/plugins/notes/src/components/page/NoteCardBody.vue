<script setup lang="ts">
import { ref, type Component } from 'vue'

const props = defineProps<{
  collapsed: boolean
  editing: boolean
  content: string
  effectiveFormat: string
  draft: string
  pastingImage: boolean
  pastingMarkdownFile: boolean
  markdownView: Component
}>()

const emit = defineEmits<{
  (e: 'update:draft', value: string): void
  (e: 'input'): void
  (e: 'keydown', event: KeyboardEvent): void
  (e: 'paste', event: ClipboardEvent): void
  (e: 'blur'): void
}>()

const textarea = ref<HTMLTextAreaElement | null>(null)
const readBody = ref<HTMLElement | null>(null)

function onInput(event: Event) {
  emit('update:draft', (event.target as HTMLTextAreaElement).value)
  emit('input')
}

defineExpose({
  focus: () => textarea.value?.focus(),
  textareaElement: () => textarea.value,
  readBodyElement: () => readBody.value,
})
</script>

<template>
  <textarea
    v-if="!collapsed && editing"
    ref="textarea"
    class="body edit"
    :value="draft"
    :readonly="pastingImage || pastingMarkdownFile"
    spellcheck="false"
    placeholder="在这里写…（Ctrl+Enter 保存，Esc 退出）"
    @pointerdown.stop
    @dblclick.stop
    @keydown="$emit('keydown', $event)"
    @paste="$emit('paste', $event)"
    @blur="$emit('blur')"
    @input="onInput"
  ></textarea>

  <div v-else-if="!collapsed" ref="readBody" class="body read">
    <component
      :is="markdownView"
      v-if="content && effectiveFormat === 'markdown'"
      :content="content"
    />
    <div v-else-if="content" class="preview plain-text">{{ content }}</div>
    <div v-else class="preview empty-hint">点击写点什么</div>
  </div>
</template>

<style scoped>
.body {
  flex: 1;
  min-height: 0;
  min-width: 0;
  margin: 0;
  padding: 10px 14px 12px;
  font-size: 15px;
  line-height: 1.85;
  border-radius: 0;
}
.body.read {
  overflow-y: auto;
  overflow-x: hidden;
  user-select: text;
  cursor: move;
  padding-top: 30px;
}
.body.read :deep(.md-preview img) {
  max-height: none;
}
.body.edit {
  background: transparent;
  font-family: var(--font-ui);
  color: var(--c-text);
  border: none;
  outline: none;
  resize: none;
  overflow-y: auto;
  padding-top: 30px;
}
.body.edit::placeholder {
  color: var(--c-text-3);
}
.preview {
  white-space: pre-wrap;
  word-break: break-word;
  overflow: hidden;
}
.preview.empty-hint {
  color: var(--c-text-3);
  font-style: italic;
}
</style>
