<script setup lang="ts">
withDefaults(defineProps<{
  open: boolean
  x: number
  y: number
  canPreview?: boolean
}>(), {
  canPreview: false,
})

const emit = defineEmits<{
  (event: 'preview'): void
  (event: 'locate'): void
  (event: 'delete'): void
  (event: 'close'): void
}>()
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="rv-menu-backdrop"
      @click="emit('close')"
      @contextmenu.prevent="emit('close')"
    ></div>
    <div
      v-if="open"
      class="rv-menu"
      role="menu"
      :style="{ left: `${x}px`, top: `${y}px` }"
    >
      <button
        v-if="canPreview"
        type="button"
        class="rv-menu-item"
        role="menuitem"
        @click="emit('preview')"
      >
        打开预览
      </button>
      <button
        type="button"
        class="rv-menu-item"
        role="menuitem"
        @click="emit('locate')"
      >
        定位卡片
      </button>
      <button
        type="button"
        class="rv-menu-item danger"
        role="menuitem"
        @click="emit('delete')"
      >
        删除卡片
      </button>
    </div>
  </Teleport>
</template>

<style scoped>
.rv-menu-backdrop {
  position: fixed;
  inset: 0;
  z-index: 900;
}

.rv-menu {
  position: fixed;
  z-index: 901;
  min-width: 140px;
  padding: 4px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  background: var(--c-surface);
  box-shadow: var(--shadow-lg);
}

.rv-menu-item {
  display: block;
  width: 100%;
  padding: 6px 10px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--c-text);
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.rv-menu-item:hover {
  background: var(--c-surface-3);
}

.rv-menu-item.danger {
  color: var(--danger);
}
</style>
