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
  const match = /^\.\.\/attachments\/([^/\n]+\.(?:png|jpg|webp))$/i.exec(src)
  if (match) {
    tokens[idx].attrSet('data-thisnote-attachment', match[1])
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

export default md
