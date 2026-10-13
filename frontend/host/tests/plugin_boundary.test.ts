import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 插件界面的边界约束。
 *
 * <p>后端靠 Maven 强制"插件只能看见 plugin-api"（在插件里 import 核心类会**编译失败**）；
 * 界面侧没有等价机制，所以这里用测试守住同一条线：插件界面不得出现 `from '@/…'`
 * 这类核心内部引用，只能用 `@thisnode/plugin-api`。
 */
const repoRoot = fileURLToPath(new URL('../../..', import.meta.url))

function collect(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules') continue
    const full = join(dir, entry.name)
    if (entry.isDirectory()) out.push(...collect(full))
    else if (/\.(ts|vue)$/.test(entry.name)) out.push(full)
  }
  return out
}

describe('插件界面边界', () => {
  it('插件界面只依赖 @thisnode/plugin-api，不得引用核心内部路径', () => {
    const offenders: string[] = []
    for (const plugin of ['memory-cycle', 'favorite']) {
      const dir = join(repoRoot, 'frontend', 'plugins', plugin)
      for (const file of collect(dir)) {
        const text = readFileSync(file, 'utf8')
        // 静态与动态 import 都要查（动态那条曾经漏过去，构建时才炸）
        for (const match of text.matchAll(/(?:from\s+|import\(\s*)'(@\/[^']*)'/g)) {
          offenders.push(`${file.replace(repoRoot, '')} → ${match[1]}`)
        }
      }
    }
    expect(offenders).toEqual([])
  })
})