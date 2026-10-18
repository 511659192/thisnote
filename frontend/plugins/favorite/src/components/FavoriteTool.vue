<script setup lang="ts">
import { ref, watch, watchEffect } from 'vue'
import { favoriteApi } from '../api/favoriteApi'
import { favoriteIds, setFavorite } from '../actions'

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
    const value = await favoriteApi.isFavorite(id)
    on.value = value
    if (value) favoriteIds.add(id)
    else favoriteIds.delete(id)
  } catch {
    on.value = false
  }
}

// 列表里的收藏开关改了同一份状态，这里跟着同步
watchEffect(() => {
  if (props.noteId) on.value = favoriteIds.has(props.noteId)
})

watch(() => props.noteId, (id) => void load(id), { immediate: true })

async function toggle() {
  if (!props.noteId || busy.value) return
  busy.value = true
  try {
    await setFavorite(props.noteId, !on.value)
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
