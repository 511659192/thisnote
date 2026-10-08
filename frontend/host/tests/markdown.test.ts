import { describe, expect, it } from 'vitest'
import { extractWikiLinks, firstHeading, plainText, renderMarkdown, wordCount } from '../src/core/markdown'

/**
 * The preview renderer is the one piece of frontend logic where a silent regression is
 * invisible (a link that stops being clickable just looks like plain text), so it is
 * covered directly.
 */
describe('markdown rendering', () => {
  it('renders wiki links as clickable spans carrying the target', () => {
    const html = renderMarkdown('see [[韧性设计]] now')
    expect(html).toContain('class="md-wikilink"')
    expect(html).toContain('data-wikilink="韧性设计"')
    expect(html).toContain('>韧性设计</span>')
  })

  it('keeps the label when the link is aliased', () => {
    const html = renderMarkdown('[[韧性设计|设计原则]]')
    expect(html).toContain('data-wikilink="韧性设计"')
    expect(html).toContain('>设计原则</span>')
  })

  it('drops the heading anchor from the target', () => {
    const html = renderMarkdown('[[某笔记#小节]]')
    expect(html).toContain('data-wikilink="某笔记"')
  })

  it('does not treat code samples as links', () => {
    const html = renderMarkdown('`[[not a link]]`\n\n```\n[[also not]]\n```')
    expect(html).not.toContain('data-wikilink')
  })

  it('renders #tags as filterable chips', () => {
    const html = renderMarkdown('body #方法论 end')
    expect(html).toContain('data-tag="方法论"')
  })

  it('escapes raw HTML instead of executing it', () => {
    const html = renderMarkdown('<img src=x onerror=alert(1)>\n\n<script>alert(2)</script>')
    expect(html).not.toContain('<img src=x')
    expect(html).not.toContain('<script>')
    expect(html).toContain('&lt;')
  })

  it('escapes quotes inside link targets', () => {
    const html = renderMarkdown('[[a"b]]')
    expect(html).not.toContain('data-wikilink="a"b"')
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
  it('extracts wiki link targets once each', () => {
    expect(extractWikiLinks('[[a]] [[a|label]] [[b#h]]')).toEqual(['a', 'b'])
  })

  it('finds the first heading for the title fallback', () => {
    expect(firstHeading('text\n\n## 标题\nmore')).toBe('标题')
    expect(firstHeading('no heading')).toBeNull()
  })

  it('strips syntax for card previews', () => {
    expect(plainText('# 标题\n\n- [ ] 一项 **加粗** [[链接]]', 100)).toBe('标题 [ ] 一项 加粗 链接')
  })

  it('counts CJK characters and latin words', () => {
    expect(wordCount('中文三字 abc def')).toBe(6)
  })
})
