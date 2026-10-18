import { describe, expect, it } from 'vitest'
import { renderMarkdown } from '../src/core/markdown'

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
