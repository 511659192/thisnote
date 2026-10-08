import MarkdownIt from 'markdown-it'

/**
 * Markdown rendering for the preview pane.
 *
 * Two project-specific extensions on top of CommonMark:
 *  - `[[目标]]` / `[[目标|别名]]` become clickable spans that route inside the app;
 *  - `#标签` becomes a tag chip.
 *
 * `html: false` keeps raw HTML from note content out of the webview (the content is
 * local and user-authored, but the canvas renders untrusted-by-default text).
 */

const escapeHtml = (s: string) =>
  s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: false,
  typographer: false,
})

/** Inline rule for [[wiki links]]. */
md.inline.ruler.before('link', 'wikilink', (state, silent) => {
  const src = state.src
  const start = state.pos
  if (src.charCodeAt(start) !== 0x5b || src.charCodeAt(start + 1) !== 0x5b) return false
  const end = src.indexOf(']]', start + 2)
  if (end === -1) return false
  const inner = src.slice(start + 2, end)
  if (inner.includes('[') || inner.includes(']') || inner.includes('\n')) return false

  const [targetPart, labelPart] = inner.split('|')
  const target = targetPart.split('#')[0].trim()
  if (!target) return false
  const label = (labelPart ?? targetPart).trim()

  if (!silent) {
    const token = state.push('wikilink', '', 0)
    token.content = label
    token.meta = { target }
  }
  state.pos = end + 2
  return true
})

md.renderer.rules.wikilink = (tokens, idx) => {
  const token = tokens[idx]
  const target = String(token.meta?.target ?? '')
  return `<span class="md-wikilink" data-wikilink="${escapeHtml(target)}" title="打开：${escapeHtml(
    target,
  )}">${escapeHtml(token.content)}</span>`
}

/** Inline rule for #标签 (must start a word and contain no spaces). */
md.inline.ruler.before('emphasis', 'hashtag', (state, silent) => {
  const src = state.src
  const start = state.pos
  if (src.charCodeAt(start) !== 0x23) return false
  const prev = start > 0 ? src[start - 1] : ' '
  if (/[\p{L}\p{N}_#]/u.test(prev)) return false
  const match = /^#([\p{L}\p{N}_/-]{1,64})/u.exec(src.slice(start))
  if (!match) return false
  if (!silent) {
    const token = state.push('hashtag', '', 0)
    token.content = match[1]
  }
  state.pos = start + match[0].length
  return true
})

md.renderer.rules.hashtag = (tokens, idx) =>
  `<span class="md-wikilink" data-tag="${escapeHtml(tokens[idx].content)}" title="筛选标签">#${escapeHtml(
    tokens[idx].content,
  )}</span>`

/**
 * 外链一律新窗口打开。
 *
 * <p>应用是单页，桌面壳里更是同一个 webview —— 原地跳转会把整个应用「导航走」，
 * 用户只能重启。rel 顺手挡掉 window.opener 与 referrer。
 *
 * <p>注意：交给系统浏览器打开外链，需要宿主允许新窗口（嵌入式 webview 通常会拦）。
 * 这一条只保证浏览器里的行为正确。
 */
const defaultLinkOpen =
  md.renderer.rules.link_open ?? ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const href = tokens[idx].attrGet('href') ?? ''
  if (/^https?:\/\//i.test(href)) {
    tokens[idx].attrSet('target', '_blank')
    tokens[idx].attrSet('rel', 'noopener noreferrer')
  }
  return defaultLinkOpen(tokens, idx, options, env, self)
}

/** Task lists: `- [ ]` / `- [x]` render as disabled checkboxes. */
const defaultListItemOpen =
  md.renderer.rules.list_item_open ?? ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))

md.renderer.rules.list_item_open = (tokens, idx, options, env, self) => {
  const inline = tokens[idx + 2]
  if (inline && inline.type === 'inline' && inline.content) {
    const m = /^\[([ xX])\]\s+/.exec(inline.content)
    if (m) {
      inline.content = inline.content.replace(/^\[([ xX])\]\s+/, '')
      tokens[idx].attrJoin('class', 'task-item')
      const checkbox = `<input type="checkbox" disabled${m[1].toLowerCase() === 'x' ? ' checked' : ''}>`
      return `${defaultListItemOpen(tokens, idx, options, env, self).replace(/>$/, '>')}${checkbox}`
    }
  }
  return defaultListItemOpen(tokens, idx, options, env, self)
}

export function renderMarkdown(source: string): string {
  if (!source) return ''
  return md.render(source)
}

export function renderInline(source: string): string {
  if (!source) return ''
  return md.renderInline(source)
}

/** Plain text projection used by card previews and list rows. */
export function plainText(source: string, maxChars = 200): string {
  if (!source) return ''
  const text = source
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/g, (_m, t, l) => l || t)
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/^\s{0,3}#{1,6}\s*/gm, '')
    .replace(/^\s{0,3}>\s?/gm, '')
    .replace(/^\s{0,3}[-*+]\s+/gm, '')
    .replace(/\*\*|__|~~|\*|_|`/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  return text.length > maxChars ? `${text.slice(0, maxChars)}…` : text
}

/** Extract wiki-link targets from source (used for the editor's link hints). */
export function extractWikiLinks(source: string): string[] {
  const out: string[] = []
  const re = /\[\[\s*([^[\]|#]+?)\s*(?:#[^[\]|]*)?(?:\|[^[\]]*)?\]\]/g
  let m: RegExpExecArray | null
  while ((m = re.exec(source))) {
    const t = m[1].trim()
    if (t && !out.includes(t)) out.push(t)
  }
  return out
}

/** First markdown heading, mirroring the backend's title fallback. */
export function firstHeading(source: string): string | null {
  const m = /^#{1,6}\s+(.+?)\s*$/m.exec(source ?? '')
  return m ? m[1].trim() : null
}

export function wordCount(source: string): number {
  const cjk = (source.match(/[\u3400-\u9fff]/g) || []).length
  const latin = (source.replace(/[\u3400-\u9fff]/g, ' ').match(/[A-Za-z0-9_'-]+/g) || []).length
  return cjk + latin
}

export default md
