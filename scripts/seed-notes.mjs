const DEFAULT_NOTE_COUNT = 1000
const BASE_URL = (process.env.THISNOTE_BASE_URL ?? 'http://127.0.0.1:45917').replace(/\/+$/, '')
const NOTE_COUNT = Number(process.argv[2] ?? DEFAULT_NOTE_COUNT)

if (!Number.isInteger(NOTE_COUNT) || NOTE_COUNT < 1) {
  console.error('Usage: node scripts/seed-notes.mjs [positive-note-count]')
  process.exit(1)
}

const randomInt = (min, max) => min + Math.floor(Math.random() * (max - min + 1))
const pick = (items) => items[randomInt(0, items.length - 1)]

async function request(method, path, body) {
  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    headers: body === undefined ? undefined : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let payload = null

  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      payload = text
    }
  }

  if (!response.ok) {
    const detail = typeof payload === 'string' ? payload : JSON.stringify(payload)
    throw new Error(`${method} ${path} returned ${response.status}: ${detail}`)
  }

  return payload
}

function repeatToLength(parts, length) {
  let text = ''
  while (text.length < length) text += pick(parts)
  return text.slice(0, length)
}

function plainText(length) {
  return repeatToLength([
    'A short observation can make a routine task easier to understand. ',
    'The useful details are recorded here so they are easy to find later. ',
    'Small changes are worth reviewing before choosing the next step. ',
    'The result should remain clear, practical, and simple to maintain. ',
    'A quick follow-up will show whether this approach works as expected. ',
  ], length)
}

function markdownText(length) {
  const content = '## Field note\n\n' + repeatToLength([
    '- **Observation:** the latest change is easy to review.\n',
    '- Next step: compare the result with the original goal.\n',
    '\nA clear summary keeps useful context close to the work. ',
    '\nThe detail `status: ready` can be checked again later.\n',
    '\n> Keep the note focused on what can be verified.\n',
  ], length)
  return content.slice(0, length)
}

function codeText(length) {
  const samples = [
    {
      language: 'js',
      comment: '// ',
      body: 'const total = 3 + 5 + 8;\nconsole.log(total);\n',
    },
    {
      language: 'py',
      comment: '# ',
      body: 'values = [3, 5, 8]\ntotal = sum(values)\nprint(total)\n',
    },
    {
      language: 'sql',
      comment: '-- ',
      body: 'SELECT COUNT(*) AS total\nFROM notes\nWHERE status = \'ready\';\n',
    },
  ]
  const sample = pick(samples)
  const fence = '```'
  const opening = `${fence}${sample.language}\n`
  const closing = '\n```'
  const bodyLength = length - opening.length - closing.length
  let body = sample.body

  if (body.length > bodyLength) throw new Error('Code sample exceeds the generated content length')
  while (body.length < bodyLength) {
    const remaining = bodyLength - body.length
    if (remaining >= sample.comment.length + 1) {
      body += `${sample.comment}${'x'.repeat(remaining - sample.comment.length - 1)}\n`
    } else {
      body += ' '.repeat(remaining)
    }
  }

  return opening + body + closing
}

function makeContent() {
  const length = randomInt(100, 500)
  const format = pick(['plain', 'markdown', 'code'])
  const content = format === 'plain'
    ? plainText(length)
    : format === 'markdown'
      ? markdownText(length)
      : codeText(length)

  if (content.length < 100 || content.length > 500) {
    throw new Error(`Generated card content has invalid length: ${content.length}`)
  }

  return { content, format }
}

const runId = `${new Date().toISOString().replace(/[-:.TZ]/g, '')}-${Math.random().toString(16).slice(2, 8)}`
const formatCounts = { plain: 0, markdown: 0, code: 0 }
let completedNotes = 0
let createdCards = 0
let activeNoteId = null

try {
  await request('GET', '/api/notes/get?limit=1')

  for (let index = 1; index <= NOTE_COUNT; index += 1) {
    const note = await request('POST', '/api/notes/create', {
      bookId: 'grp-default',
    })

    activeNoteId = note?.noteId
    if (!activeNoteId) throw new Error('Create-note response did not include noteId')

    const title = `Seed-${runId}-${String(index).padStart(4, '0')}`
    await request('POST', '/api/notes/update', { noteId: activeNoteId, title })

    const cardCount = randomInt(1, 5)
    for (let cardIndex = 0; cardIndex < cardCount; cardIndex += 1) {
      const { content, format } = makeContent()
      await request('POST', '/api/notes/cards/create', {
        noteId: activeNoteId,
        x: 80 + (cardIndex % 2) * 620,
        y: 80 + Math.floor(cardIndex / 2) * 340,
        w: 560,
        h: 300,
        content,
      })
      formatCounts[format] += 1
      createdCards += 1
    }

    completedNotes += 1
    activeNoteId = null
    if (completedNotes % 50 === 0 || completedNotes === NOTE_COUNT) {
      console.log(`Created ${completedNotes}/${NOTE_COUNT} notes (${createdCards} cards)`)
    }
  }

  console.log(`Done: ${completedNotes} notes, ${createdCards} cards`)
  console.log(`Card formats: plain=${formatCounts.plain}, markdown=${formatCounts.markdown}, code=${formatCounts.code}`)
} catch (error) {
  console.error(`Stopped after ${completedNotes}/${NOTE_COUNT} complete notes and ${createdCards} cards.`)
  if (activeNoteId) console.error(`The partially created note has id ${activeNoteId}.`)
  console.error(error instanceof Error ? error.message : error)
  process.exitCode = 1
}
