import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 插件界面的边界约束：插件只能用 @thisnote/plugin-api、共享运行时和自身包内文件，
 * 不得引用宿主内部路径或另一个插件。
 */
const repoRoot = fileURLToPath(new URL('../../..', import.meta.url))
const pluginRoot = join(repoRoot, 'frontend', 'plugins')

function collect(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === 'dist') continue
    const full = join(dir, entry.name)
    if (entry.isDirectory()) out.push(...collect(full))
    else if (/\.(ts|vue)$/.test(entry.name)) out.push(full)
  }
  return out
}

function importSpecifiers(text: string): string[] {
  const specifiers: string[] = []
  for (const match of text.matchAll(/(?:from\s+|import\s*\(\s*)['"]([^'"]+)['"]/g)) {
    specifiers.push(match[1])
  }
  return specifiers
}

describe('插件界面边界', () => {
  it('全部插件只依赖契约和自身代码，不跨插件或引用宿主内部路径', () => {
    const offenders: string[] = []
    const plugins = readdirSync(pluginRoot, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)

    for (const plugin of plugins) {
      const dir = join(pluginRoot, plugin)
      for (const file of collect(dir)) {
        const text = readFileSync(file, 'utf8')
        for (const specifier of importSpecifiers(text)) {
          if (specifier.startsWith('@/') && plugin !== 'notes') {
            offenders.push(`${file.replace(repoRoot, '')} -> ${specifier}`)
          }
          if (specifier.startsWith('@thisnote/plugin-')
            && !specifier.startsWith('@thisnote/plugin-api')) {
            offenders.push(`${file.replace(repoRoot, '')} -> ${specifier}`)
          }
          if (specifier.startsWith('.')) {
            const resolved = resolve(dirname(file), specifier)
            if (resolved !== dir && !resolved.startsWith(dir + sep)) {
              offenders.push(`${file.replace(repoRoot, '')} -> ${specifier}`)
            }
          }
        }
      }

      const packageJson = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8')) as {
        dependencies?: Record<string, string>
      }
      for (const dependency of Object.keys(packageJson.dependencies ?? {})) {
        if (dependency.startsWith('@thisnote/plugin-')
          && !dependency.startsWith('@thisnote/plugin-api')) {
          offenders.push(`frontend/plugins/${plugin}/package.json -> ${dependency}`)
        }
      }
    }

    expect(offenders).toEqual([])
  })
})
