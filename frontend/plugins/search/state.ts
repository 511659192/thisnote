import { ref } from 'vue'

/**
 * 搜索插件的共享状态：查询词、命中计数与标识常量。
 *
 * <p>独立成模块是为了避免循环依赖：SearchBox.vue（UI）写 query、读 matchCount，
 * index.ts（筛选的 load()）写 matchCount、读 query —— 两者都只 import 本模块。
 * query/matchCount 用 ref：搜索框要在输入时响应式地显示「命中 N 篇」。
 */

export const PLUGIN_ID = 'search'
export const FILTER_ID = 'title'

export const query = ref('')
/** 最近一次筛选的命中数；null = 未在筛选中。 */
export const matchCount = ref<number | null>(null)

export function setQuery(q: string): void {
  query.value = q
}

export function setMatchCount(n: number | null): void {
  matchCount.value = n
}
