"""Remove empty cards left on pages by test runs.

A card with neither text nor a title is noise; the app already deletes them on blur, but
automated runs can leave some behind (a card created and abandoned without a blur).

Usage: python tools/cleanup_empty_blocks.py [--yes] [base_url]
"""

from __future__ import annotations

import json
import sys
import urllib.request

BASE = next((a for a in sys.argv[1:] if a.startswith("http")), "http://127.0.0.1:45917")
APPLY = "--yes" in sys.argv


def call(method, path, body=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=60) as res:
        raw = res.read()
    return json.loads(raw) if raw else None


notes = call("GET", "/api/notes?limit=2000")
victims: list[tuple[str, str, int]] = []
for note in notes:
    page = call("GET", f"/api/notes/{note['id']}/blocks")
    total = len(page["blocks"])
    for view in page["blocks"]:
        block = view["block"]
        empty = not (block.get("contentMd") or "").strip() and not (block.get("title") or "").strip()
        if empty and total > 1:
            victims.append((note["id"], block["id"], total))

print(f"{len(notes)} notes scanned; {len(victims)} empty card(s) found")
for note_id, block_id, total in victims[:10]:
    print(f"   note {note_id[:8]} block {block_id[:8]} (page had {total} cards)")
if len(victims) > 10:
    print(f"   …and {len(victims) - 10} more")

if not victims:
    sys.exit(0)
if not APPLY:
    print("\ndry run only — re-run with --yes to delete")
    sys.exit(0)

by_note: dict[str, list[str]] = {}
for note_id, block_id, _ in victims:
    by_note.setdefault(note_id, []).append(block_id)

for note_id, block_ids in by_note.items():
    for block_id in block_ids:
        call("DELETE", f"/api/notes/{note_id}/blocks/{block_id}?deleteContent=false")
    print(f"   cleaned {len(block_ids)} card(s) from note {note_id[:8]}")

stats = call("GET", "/api/maintenance/stats")
print(f"\nremaining: notes {stats['nodes']} · cards {stats['noteBlocks']}")
