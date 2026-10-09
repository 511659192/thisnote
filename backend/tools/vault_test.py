"""Verify that external changes to the vault are picked up: rewrite a card's .md outside the app and
confirm the backend re-scans it, then delete that file and confirm the card goes with it.

The vault is the source of truth, so the direction is one-way: disk always wins.

Run: python tools/vault_test.py [base_url]
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.parse
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:45917"
RUN = time.strftime("%H%M%S")
PASSED, FAILED = [], []


def call(method, path, body=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=60) as res:
        raw = res.read()
    return json.loads(raw) if raw else None


def check(name, ok, detail=""):
    (PASSED if ok else FAILED).append(name if ok else f"{name} :: {detail}")
    print(f"  {'PASS' if ok else 'FAIL'}  {name}  {'' if ok else detail}")


mirror = call("GET", "/api/io/mirror/info")
root = mirror["root"]
print(f"mirror root: {root}")

note = call("POST", "/api/notes", {
    "title": f"外部编辑-{RUN}", "contentMd": f"# 外部编辑-{RUN}\n\n原始内容 ORIGINAL-{RUN}\n"})

# 卡片文件就在保险库根目录：<slug>-<卡片短 id>.md（slug 取自卡片标题或正文首个标题）。
matches = [f for f in os.listdir(root) if f.endswith(".md") and f.startswith(f"外部编辑-{RUN}")]
check("mirror file created for the note", len(matches) == 1, str(matches))
if not matches:
    print("\nRESULT: cannot continue without a mirrored file")
    sys.exit(1)
path = os.path.join(root, matches[0])
print(f"mirror file: {path}")

# 1. simulate an edit in Obsidian: rewrite the file with newer content
time.sleep(1.2)
with open(path, "w", encoding="utf-8") as fh:
    fh.write(f"# 外部编辑-{RUN}\n\n被 Obsidian 改过的内容 EXTERNAL-{RUN} [[外部链接目标-{RUN}]]\n")
future = time.time() + 5
os.utime(path, (future, future))

call("POST", "/api/notes/sync/reconcile")
time.sleep(1.0)
detail = call("GET", f"/api/notes/{note['id']}")
check("external edit loaded into the index", f"EXTERNAL-{RUN}" in detail["contentMd"],
      detail["contentMd"][:120])
check("links from the external edit were parsed",
      any(l["toTitle"] == f"外部链接目标-{RUN}" for l in detail["outbound"]),
      str(detail["outbound"]))
check("unresolved link reported for the new target",
      f"外部链接目标-{RUN}" in detail["unresolved"], str(detail["unresolved"]))

search = call("GET", f"/api/search?q={urllib.parse.quote(f'EXTERNAL-{RUN}')}")
check("externally edited content is searchable", search["total"] >= 1, str(search["total"]))

# 2. app-side edit must be written back to the file
call("PATCH", f"/api/notes/{note['id']}", {"contentMd": f"# 外部编辑-{RUN}\n\n来自应用 APP-{RUN}\n"})
call("POST", "/api/notes/sync/reconcile")
time.sleep(0.6)
with open(path, encoding="utf-8") as fh:
    on_disk = fh.read()
check("app edit written back to the markdown file", f"APP-{RUN}" in on_disk, on_disk[:120])

# 3. 在应用外删掉卡片文件 = 删掉那张卡（文件就是卡片，不会被重建）
os.remove(path)
call("POST", "/api/notes/sync/reconcile")
time.sleep(0.8)
page = call("GET", f"/api/notes/{note['id']}/blocks")
check("externally deleted card file disappears from the page",
      all(f"APP-{RUN}" not in (b.get("contentMd") or "") for b in page["blocks"]),
      str([(b.get("contentMd") or "")[:40] for b in page["blocks"]]))

call("DELETE", f"/api/notes/{note['id']}?hard=true")
print(f"\nPASSED {len(PASSED)}   FAILED {len(FAILED)}")
for failure in FAILED:
    print("  - " + failure)
sys.exit(1 if FAILED else 0)
