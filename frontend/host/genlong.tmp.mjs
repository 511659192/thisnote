
import fs from 'node:fs'
import path from 'node:path'
const ROOT = 'D:/projects/thisnote/.tmp-longvault'
const NOTES = path.join(ROOT, 'notes')
const META = path.join(NOTES, '.notes')
fs.rmSync(ROOT, { recursive: true, force: true })
fs.mkdirSync(META, { recursive: true })
const now = Date.now()
const para = '这是一段用于测量正文缓存占用的长文本，重复若干遍以逼近真实笔记的长度。'
const body = (i, c) => Array.from({ length: 12 }, (_, k) => '第 ' + (i + 1) + ' 篇第 ' + (c + 1) + ' 张卡第 ' + (k + 1) + ' 段：' + para).join('\n')
for (let i = 0; i < 2000; i++) {
  const noteId = 'note-' + String(i).padStart(6, '0')
  const cardRefs = []
  for (let c = 0; c < 5; c++) {
    const blockId = noteId + '-card-' + c
    const file = 'note' + i + '-card' + c + '-' + blockId.slice(-8) + '.md'
    const meta = { v: 2, id: blockId, title: '', x: 0, y: c * 300, w: 720, h: 260, z: c, collapsed: false, ext: {}, createdAt: now, updatedAt: now }
    fs.writeFileSync(path.join(NOTES, file), '<!-- thisnode:meta ' + JSON.stringify(meta) + ' -->\n' + body(i, c))
    cardRefs.push({ id: blockId, file })
  }
  fs.writeFileSync(path.join(META, noteId + '.json'), JSON.stringify({ v: 2, id: noteId, title: '笔记 ' + i, type: 'note', archived: false, deleted: false, tags: [], aliases: [], notebookId: 'grp-default', cards: cardRefs, summary: '第 ' + i + ' 篇', createdAt: now, updatedAt: now }))
}
fs.writeFileSync(path.join(META, 'notebooks.json'), JSON.stringify({ v: 1, notebooks: [{ v: 1, id: 'grp-default', type: 'notebook', title: '收集箱', parentId: null, icon: null, sortOrder: 0, createdAt: now, updatedAt: now }] }))
let bytes = 0
for (const f of fs.readdirSync(NOTES)) { const s = fs.statSync(path.join(NOTES, f)); if (f.endsWith('.md')) bytes += s.size }
console.log('cards bytes = ' + (bytes / 1048576).toFixed(1) + ' MB')
