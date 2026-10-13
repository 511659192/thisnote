<script setup lang="ts">
import { ref, watch } from 'vue'
import { favoriteApi } from './favoriteApi'

/**
 * 收藏插件的「笔记工具」：笔记标题旁的 ★ 开关。
 *
 * <p>它是**实体插件**的 UI 形态：底座把 noteId 递进来，插件自己读状态、自己切换。
 * 状态存在收藏插件自己的文件里，核心的表里没有 favorite 这一列。
 */
const props = defineProps<{ noteId: string }>()

const on = ref(false)
const busy = ref(false)

async function load(id: string) {
  if (!id) {
    on.value = false
    return
  }
  try {
    on.value = await favoriteApi.isFavorite(id)
  } catch {
    on.value = false
  }
}

watch(() => props.noteId, (id) => void load(id), { immediate: true })

async function toggle() {
  if (!props.noteId || busy.value) return
  busy.value = true
  try {
    await favoriteApi.setFavorite(props.noteId, !on.value)
    on.value = !on.value
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <button
    class="btn sm ghost fav-tool"
    :class="{ active: on }"
    :title="on ? '取消收藏' : '收藏'"
    :disabled="busy"
    @click.stop="toggle"
  >
    {{ on ? '★' : '☆' }}
  </button>
</template>

<style scoped>
.fav-tool {
  font-size: 14px;
  line-height: 1;
}
</style>
