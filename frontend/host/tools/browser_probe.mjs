// Reproduce the "clicking a note does nothing" report in a real browser and capture
// every console message / failed request so the cause is visible.
//
// Usage: node tools/browser_probe.mjs [url]

import { chromium } from 'playwright'
import { existsSync } from 'node:fs'

const URL = process.argv[2] || 'http://localhost:5173/'

// Prefer an already-downloaded browser over Playwright's pinned headless shell, which may
// not be present on this machine (the pinned revision needs its own download).
const CANDIDATES = [
  (process.env.LOCALAPPDATA ?? '') + '\\ms-playwright\\chromium-1208\\chrome-win64\\chrome.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
]
const executablePath = CANDIDATES.find((p) => p && existsSync(p))

const browser = await chromium.launch(
  executablePath ? { executablePath, args: ['--headless=new'] } : {},
)
console.log(`browser: ${executablePath ?? '(playwright default)'}`)
const page = await browser.newPage()

const logs = []
page.on('console', (m) => logs.push(`[console.${m.type()}] ${m.text()}`))
page.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}\n${e.stack ?? ''}`))
page.on('requestfailed', (r) =>
  logs.push(`[requestfailed] ${r.method()} ${r.url()} :: ${r.failure()?.errorText}`),
)
page.on('response', (r) => {
  if (r.status() >= 400) logs.push(`[http ${r.status()}] ${r.request().method()} ${r.url()}`)
})
console.log(`== opening ${URL} ==`)
await page.goto(URL, { waitUntil: 'networkidle', timeout: 30000 })
await page.waitForTimeout(2500)

const describe = async (label) => {
  const state = await page.evaluate(() => {
    const editor = document.querySelector('.editor-shell')
    const cm = document.querySelector('.cm-editor')
    return {
      hash: location.hash,
      appHtmlLength: document.querySelector('#app')?.innerHTML.length ?? -1,
      hasNoteList: !!document.querySelector('.note-list'),
      noteRows: document.querySelectorAll('.note-row').length,
      hasEditor: !!editor,
      editorClasses: editor?.className ?? null,
      editorHeight: editor ? Math.round(editor.getBoundingClientRect().height) : null,
      editorTitle: document.querySelector('.title-input')?.value ?? null,
      cmPresent: !!cm,
      cmText: cm ? cm.innerText.slice(0, 120) : null,
      toolbarButtons: [...document.querySelectorAll('.editor-toolbar button')].map((b) => b.textContent.trim()),
      previewHtmlLength: document.querySelector('.md-preview')?.innerHTML.length ?? null,
      emptyState: document.querySelector('.empty h3')?.textContent ?? null,
      bootScreen: !!document.querySelector('.boot-screen'),
      bodyText: document.body.innerText.slice(0, 200).replace(/\n+/g, ' | '),
    }
  })
  console.log(`\n-- ${label} --`)
  console.log(JSON.stringify(state, null, 2))
  return state
}

await describe('after load')

const rows = page.locator('.note-row')
const count = await rows.count()
console.log(`\n== found ${count} note rows ==`)
if (count === 0) {
  console.log('no note rows rendered; dumping logs')
} else {
  const target = rows.first()
  const title = await target.locator('.title').innerText().catch(() => '(no title)')
  console.log(`clicking first row: ${JSON.stringify(title)}`)
  await target.click()
  await page.waitForTimeout(2500)
  await describe('after click')
  console.log(`url now: ${page.url()}`)
}

console.log('\n== console / network ==')
if (logs.length === 0) console.log('(clean: no errors, no failed requests)')
for (const line of logs) console.log(line)

await browser.close()
