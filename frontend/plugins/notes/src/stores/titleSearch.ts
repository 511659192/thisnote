import { ref } from 'vue'

/**
 * notes 核心内标题搜索框与列表筛选共享的状态：查询词、命中计数与筛选标识。
 *
 * <p>独立成模块是为了避免循环依赖：TitleSearchBox.vue（UI）写 query、读 matchCount，
 * notes/index.ts（筛选的 load()）写 matchCount、读 query —— 两者都只 import 本模块。
 * query/matchCount 用 ref：搜索框要在输入时响应式地显示「命中 N 篇」。
 */

export const PLUGIN_ID = 'notes'
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
