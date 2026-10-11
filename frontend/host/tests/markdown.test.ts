import { describe, expect, it } from 'vitest'
import { firstHeading, plainText, renderMarkdown, wordCount } from '../src/core/markdown'

describe('markdown rendering', () => {
  it('escapes raw HTML instead of executing it', () => {
    const html = renderMarkdown('<img src=x onerror=alert(1)>\n\n<script>alert(2)</script>')
    expect(html).not.toContain('<img src=x')
    expect(html).not.toContain('<script>')
    expect(html).toContain('&lt;')
  })

  it('renders headings, lists, tables and task items', () => {
    const html = renderMarkdown('# T\n\n- [ ] todo\n- [x] done\n\n| a | b |\n| --- | --- |\n| 1 | 2 |')
    expect(html).toContain('<h1>T</h1>')
    expect(html).toContain('type="checkbox"')
    expect(html).toContain('checked')
    expect(html).toContain('<table>')
  })
})

describe('markdown helpers', () => {
  it('finds the first heading for the title fallback', () => {
    expect(firstHeading('text\n\n## 标题\nmore')).toBe('标题')
    expect(firstHeading('no heading')).toBeNull()
  })

  it('strips regular Markdown syntax for card previews', () => {
    expect(plainText('# 标题\n\n- [ ] 一项 **加粗** [链接](https://example.com)', 100))
      .toBe('标题 [ ] 一项 加粗 链接')
  })

  it('counts CJK characters and latin words', () => {
    expect(wordCount('中文三字 abc def')).toBe(6)
  })
})
