export type CardFormat = 'auto' | 'markdown' | 'text'

const markdownPatterns = [
  /^ {0,3}#{1,6}\s+\S/m,
  /^ {0,3}(?:[-*+]\s+\S|\d+[.)]\s+\S)/m,
  /^ {0,3}>\s?\S/m,
  /^ {0,3}(?:`{3,}|~{3,})/m,
  /^ {0,3}(?:-{3,}|\*{3,}|_{3,})\s*$/m,
  /!?\[[^\]\n]*\]\([^)]+\)/,
  /^\s*\|?.+\|.+\n\s*\|?\s*:?-{3,}:?\s*\|(?:\s*:?-{3,}:?\s*\|)+/m,
  /(?:\*\*|__)[^\s](?:.*?[^\s])?(?:\*\*|__)/s,
  /~~[^\s](?:.*?[^\s])?~~/s,
  /`[^`\n]+`/,
  /(?:^|[^\w])([*_])[^\s*_](?:[^*_]*?[^\s*_])?\1(?=$|[^\w])/m,
]

function looksLikeCode(content: string): boolean {
  if (/^\s*(?:```|~~~)/m.test(content)) return false
  const lines = content.replace(/\r\n?/g, '\n').split('\n')
  const codeStatements = lines.filter((line) =>
    /^\s*(?:(?:\/\/|\/\*|\*\/)|(?:import|export|const|let|var|function|class|interface|type|def|return|if|else|for|while|switch|try|catch|throw|new|public|private|protected|static|void|int|String|async|await)\b|[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\s*\(|(?:[*&]\s*)?[A-Za-z_$][\w$]*\s*=)/.test(line),
  ).length
  const codeSyntaxLines = lines.filter((line) => /[{};]|=>|::|->|\b\w+\s*=\s*[^=]/.test(line)).length
  const nonEmptyLines = lines.filter((line) => line.trim()).length

  return (codeStatements >= 2 && codeStatements / nonEmptyLines >= 0.4)
    || (codeSyntaxLines >= 3 && codeSyntaxLines / nonEmptyLines >= 0.5)
    || (nonEmptyLines <= 2 && codeStatements > 0 && codeSyntaxLines > 0)
}

export function isMarkdown(content: string): boolean {
  if (looksLikeCode(content)) return false
  return markdownPatterns.some((pattern) => pattern.test(content))
}

function renderList(element: Element, depth = 0): string {
  const ordered = element.tagName === 'OL'
  const items = Array.from(element.children).filter((child) => child.tagName === 'LI')
  return items
    .map((item, index) => {
      const nestedLists = Array.from(item.children).filter(
        (child) => child.tagName === 'UL' || child.tagName === 'OL',
      )
      const text = Array.from(item.childNodes)
        .filter((child) => !(child instanceof Element && (child.tagName === 'UL' || child.tagName === 'OL')))
        .map(renderNode)
        .join('')
        .trim()
        .replace(/\s*\n+\s*/g, ' ')
      const marker = ordered ? `${index + 1}.` : '-'
      const nested = nestedLists.map((list) => renderList(list, depth + 1)).join('')
      return `${'  '.repeat(depth)}${marker} ${text}\n${nested}`
    })
    .join('')
}

function renderTable(element: Element): string {
  const rows = Array.from(element.querySelectorAll('tr')).map((row) =>
    Array.from(row.children)
      .filter((cell) => cell.tagName === 'TH' || cell.tagName === 'TD')
      .map((cell) => renderChildren(cell).trim().replace(/\s*\n+\s*/g, ' ').replace(/\|/g, '\\|')),
  )
  if (!rows.length) return ''
  const line = (cells: string[]) => `| ${cells.join(' | ')} |`
  const separator = `| ${rows[0].map(() => '---').join(' | ')} |`
  return [line(rows[0]), separator, ...rows.slice(1).map(line)].join('\n') + '\n\n'
}

function renderChildren(element: Element): string {
  return Array.from(element.childNodes).map(renderNode).join('')
}

function renderNode(node: Node): string {
  if (node.nodeType === 3) return node.nodeValue ?? ''
  if (node.nodeType !== 1) return ''

  const element = node as Element
  const tag = element.tagName
  const children = renderChildren(element)

  if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') return ''
  if (/^H[1-6]$/.test(tag)) {
    return `${'#'.repeat(Number(tag[1]))} ${children.trim()}\n\n`
  }
  if (tag === 'P' || tag === 'DIV' || tag === 'SECTION' || tag === 'ARTICLE') {
    return `${children.trim()}\n\n`
  }
  if (tag === 'BR') return '\n'
  if (tag === 'HR') return '\n---\n\n'
  if (tag === 'UL' || tag === 'OL') return `\n${renderList(element)}\n`
  if (tag === 'BLOCKQUOTE') {
    return `${children
      .trim()
      .split('\n')
      .map((line) => (line ? `> ${line}` : '>'))
      .join('\n')}\n\n`
  }
  if (tag === 'PRE') return `\n\`\`\`\n${element.textContent?.replace(/\n+$/, '') ?? ''}\n\`\`\`\n\n`
  if (tag === 'TABLE') return renderTable(element)
  if (tag === 'STRONG' || tag === 'B') return `**${children}**`
  if (tag === 'EM' || tag === 'I') return `*${children}*`
  if (tag === 'DEL' || tag === 'S' || tag === 'STRIKE') return `~~${children}~~`
  if (tag === 'SPAN') {
    const style = element.getAttribute('style') ?? ''
    if (/font-weight\s*:\s*(?:bold|[6-9]00)/i.test(style)) return `**${children}**`
    if (/font-style\s*:\s*italic/i.test(style)) return `*${children}*`
    if (/text-decoration(?:-line)?\s*:[^;]*line-through/i.test(style)) return `~~${children}~~`
  }
  if (tag === 'CODE' && element.parentElement?.tagName !== 'PRE') return `\`${children}\``
  if (tag === 'A') {
    const href = element.getAttribute('href')
    const text = children.trim()
    return href ? `[${text || href}](${href})` : children
  }
  if (tag === 'IMG') {
    const src = element.getAttribute('src') ?? ''
    const alt = element.getAttribute('alt') ?? ''
    return src && !src.startsWith('data:') ? `![${alt}](${src})` : alt
  }
  return children
}

export function clipboardHtmlToMarkdown(html: string): string {
  const document = new DOMParser().parseFromString(html, 'text/html')
  return renderChildren(document.body)
    .replace(/\u00a0/g, ' ')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function isMarkdownFile(file: File): boolean {
  return file.type === 'text/markdown' || /\.(?:md|markdown)$/i.test(file.name)
}

function hasRichHtmlFormatting(html: string): boolean {
  const document = new DOMParser().parseFromString(html, 'text/html')
  return Boolean(document.body.querySelector(
    'h1,h2,h3,h4,h5,h6,p,div,br,hr,ul,ol,li,blockquote,pre,table,strong,b,em,i,del,s,strike,code,a,img,span',
  ))
}

export function getClipboardMarkdownFile(data: DataTransfer | null): File | null {
  if (!data) return null
  for (const file of Array.from(data.files)) {
    if (isMarkdownFile(file)) return file
  }
  for (const item of Array.from(data.items)) {
    const file = item.kind === 'file' ? item.getAsFile() : null
    if (file && isMarkdownFile(file)) return file
  }
  return null
}

export function isClipboardCode(data: DataTransfer | null): boolean {
  if (!data) return false
  const html = data.getData('text/html')
  if (html) {
    const document = new DOMParser().parseFromString(html, 'text/html')
    if (document.body.querySelector('pre')) {
      const withoutCode = document.body.cloneNode(true) as HTMLElement
      withoutCode.querySelectorAll('pre,code').forEach((element) => element.remove())
      return !withoutCode.textContent?.trim()
    }
  }
  return looksLikeCode(data.getData('text/plain') ?? '')
}

function normalizeLineBreaks(text: string): string {
  return text.replace(/\r\n?/g, '\n')
}

export async function getClipboardMarkdown(data: DataTransfer | null): Promise<string> {
  if (!data) return ''
  const file = getClipboardMarkdownFile(data)
  if (file) return normalizeLineBreaks(await file.text())
  const text = normalizeLineBreaks(data.getData('text/plain') ?? '')
  const html = data.getData('text/html')

  if (html.trim() && hasRichHtmlFormatting(html)) {
    const document = new DOMParser().parseFromString(html, 'text/html')
    if (isClipboardCode(data)) {
      return text || normalizeLineBreaks(document.body.textContent ?? '')
    }
    const markdown = clipboardHtmlToMarkdown(html)
    if (markdown) return markdown
  }
  if (looksLikeCode(text)) return text
  return text
}
