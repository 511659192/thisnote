import MarkdownIt from 'markdown-it'

/** Shared CommonMark rendering for note and card previews. */
const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: false,
  typographer: false,
})

/** 外链一律新窗口打开，避免单页应用在当前 webview 中被导航走。 */
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

const defaultImage =
  md.renderer.rules.image ?? ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))

md.renderer.rules.image = (tokens, idx, options, env, self) => {
  const src = tokens[idx].attrGet('src') ?? ''
  const match = /^\.\.\/attachments\/([^/\n]+\.(?:png|jpg))$/i.exec(src)
  if (match) {
    tokens[idx].attrSet('data-thisnode-attachment', match[1])
    tokens[idx].attrSet('src', 'about:blank')
  }
  return defaultImage(tokens, idx, options, env, self)
}

/** Task lists: `- [ ]` / `- [x]` render as disabled checkboxes. */
const defaultListItemOpen =
  md.renderer.rules.list_item_open ?? ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))

md.renderer.rules.list_item_open = (tokens, idx, options, env, self) => {
  const inline = tokens[idx + 2]
  if (inline && inline.type === 'inline' && inline.content) {
    const match = /^\[([ xX])\]\s+/.exec(inline.content)
    if (match) {
      inline.content = inline.content.replace(/^\[([ xX])\]\s+/, '')
      tokens[idx].attrJoin('class', 'task-item')
      const checkbox = `<input type="checkbox" disabled${match[1].toLowerCase() === 'x' ? ' checked' : ''}>`
      return `${defaultListItemOpen(tokens, idx, options, env, self)}${checkbox}`
    }
  }
  return defaultListItemOpen(tokens, idx, options, env, self)
}

export function renderMarkdown(source: string): string {
  return source ? md.render(source) : ''
}

export function renderInline(source: string): string {
  return source ? md.renderInline(source) : ''
}

/** Plain-text projection used by card previews and list rows. */
export function plainText(source: string, maxChars = 200): string {
  if (!source) return ''
  const text = source
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/^\s{0,3}#{1,6}\s*/gm, '')
    .replace(/^\s{0,3}>\s?/gm, '')
    .replace(/^\s{0,3}[-*+]\s+/gm, '')
    .replace(/\*\*|__|~~|\*|_|`/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  return text.length > maxChars ? `${text.slice(0, maxChars)}…` : text
}

/** First Markdown heading, mirroring the backend's title fallback. */
export function firstHeading(source: string): string | null {
  const match = /^#{1,6}\s+(.+?)\s*$/m.exec(source ?? '')
  return match ? match[1].trim() : null
}

export function wordCount(source: string): number {
  const cjk = (source.match(/[\u3400-\u9fff]/g) || []).length
  const latin = (source.replace(/[\u3400-\u9fff]/g, ' ').match(/[A-Za-z0-9_'-]+/g) || []).length
  return cjk + latin
}

export default md
