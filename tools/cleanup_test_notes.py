"""Remove test debris left in the vault by interactive and automated testing.

Matches only the throwaway notes the test runs created (titles carrying their markers); real
notes are never touched. Prints what it deletes and refuses to run without --yes.

Usage: python tools/cleanup_test_notes.py [--yes] [base_url]
"""

from __future__ import annotations

import json
import re
import sys
import urllib.parse
import urllib.request

BASE = next((a for a in sys.argv[1:] if a.startswith("http")), "http://127.0.0.1:45917")
CONFIRM = "--yes" in sys.argv

# Titles produced by the automated runs and manual probing. Kept narrow on purpose.
PATTERNS = (
    "无标题笔记",
    "新卡片",
    "粘贴内容测试",
    "粘贴到页面",
    "页面上写的",
    "卡片输入测试",
    "备份链接源-",
    "硬删除-",
    "性能卡片",
    "Image",
    "任务清单标题",
    "待删除",
)

# smoke_test.py stamps every title it creates with "-HHMMSS", so a run that dies part way
# through (an aborted run stops before its own cleanup) leaves a matching set behind.
RUN_SUFFIX = re.compile(r"-\d{6}$")
RUN_TITLES = (
    "韧性设计",
    "节点化知识",
    "节点化知识体系",
    "FSRS 记忆",
    "别名引用",
    "第二课",
    "第一课",
    "第三课",
)


def call(method, path, body=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=60) as res:
        raw = res.read()
    return json.loads(raw) if raw else None


notes = call("GET", "/api/notes?limit=2000")


def is_debris(note: dict) -> bool:
    title = note["title"]
    if any(title.startswith(p) for p in PATTERNS):
        return True
    # "韧性设计-002513" style: a known smoke-test title plus the run stamp.
    stem = RUN_SUFFIX.sub("", title)
    return bool(RUN_SUFFIX.search(title)) and stem in RUN_TITLES


victims = [n for n in notes if is_debris(n)]

print(f"vault has {len(notes)} notes; {len(victims)} look like test debris:")
for n in victims:
    print(f"   - {n['title'][:44]:46} blocks={n.get('blockCount')}")

if not victims:
    sys.exit(0)
if not CONFIRM:
    print("\ndry run only — re-run with --yes to delete")
    sys.exit(0)

for i, n in enumerate(victims, 1):
    call("DELETE", f"/api/notes/{n['id']}?hard=true")
    if i % 25 == 0:
        print(f"   …{i}/{len(victims)}")

counts = call("GET", "/api/notes/counts")
stats = call("GET", "/api/maintenance/stats")
print(f"\nremaining: notes {counts['all']} · blocks {stats['noteBlocks']} · links {counts['links']}")
