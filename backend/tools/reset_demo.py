"""Rewrite the demo vault's note page so it matches the current model.

The demo notes were authored for the old board model, so some of them are near-duplicates or
carry test leftovers. This keeps the vault small and self-explanatory:

  * delete the throwaway/duplicate notes
  * give each remaining note a tidy first card
  * put a small multi-card example on the welcome note so the page model is obvious

Usage: python tools/seed_demo.py --reset  (or re-run this file)
"""

from __future__ import annotations

import json
import sys
import urllib.parse
import urllib.request

BASE = next((a for a in sys.argv[1:] if a.startswith("http")), "http://127.0.0.1:45917")
RESET = "--reset" in sys.argv


def call(method, path, body=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=60) as res:
        raw = res.read()
    return json.loads(raw) if raw else None


notes = call("GET", "/api/notes?limit=2000")
print(f"vault has {len(notes)} notes:")
for n in sorted(notes, key=lambda x: x["title"]):
    blocks = len(n.get("cards") or [])
    print(f"   {n['title'][:34]:36} cards={blocks}")

if not RESET:
    print("\nreport only — re-run with --reset to delete everything and reseed")
    sys.exit(0)

print("\ndeleting all notes…")
for n in notes:
    call("DELETE", f"/api/notes/{n['id']}?hard=true")

print("reseeding…")


def note(title, cards, tags=None):
    created = call("POST", "/api/notes", {"title": title, "contentMd": "", "tags": tags or []})
    page_url = f"/api/notes/{created['id']}/blocks"
    page = call("GET", page_url)
    # The page always starts with one card; write the first fragment into it, then add more.
    first = page["blocks"][0]["block"]["id"]
    call("PATCH", f"{page_url}/{first}/content", {"contentMd": cards[0]})
    x, y = 0, 0
    for i, text in enumerate(cards[1:], start=1):
        call("POST", f"{page_url}/blocks",
             {"x": x + i * 380, "y": y + (i % 2) * 40, "w": 360, "h": 220, "contentMd": text})
    return created


welcome = note(
    "欢迎使用 thisnode",
    [
        "# 欢迎使用 thisnode\n\n"
        "这是一篇**笔记**。下面每一块都是一个可以随手摆放、随手书写的**卡片**：\n\n"
        "- 双击页面空白处 → 新建一张卡片\n"
        "- 在卡片里直接打字 → 内容就是这篇笔记的内容\n"
        "- 拖动卡片 → 摆到你顺手的位置\n"
        "- `Ctrl+V` → 剪贴板内容变成一张卡片",
        "## 卡片属于这篇笔记\n\n"
        "一篇笔记就是一个页面，页面上的卡片装的是这篇笔记的内容片段。\n"
        "不想看的卡片可以折叠（标题栏右侧箭头），折叠后只剩一条标题。",
        "## 先写链接，再建笔记\n\n"
        "正文里写 `[[某个概念]]`，即使那篇笔记还不存在也可以；之后创建它，链接会自动接上。\n\n"
        "比如现在指向 [[韧性设计原则]]。",
    ],
    ["入门", "thisnode"],
)

principles = note(
    "韧性设计原则",
    [
        "# 韧性设计原则\n\n系统失去韧性，通常不是因为它不够强，而是因为它没有冗余、没有反馈、无法回滚。\n\n"
        "## 冗余\n\n本地 SQLite 是权威数据源，同时维护一份 Markdown 镜像；每天自动快照，保留 7 天。",
        "## 反馈\n\n反向链接让你看到「谁在用我的想法」；空间位置让你看到「想法之间的关系」。\n\n"
        "相关：[[欢迎使用 thisnode]]",
    ],
    ["方法论", "设计"],
)

note(
    "Markdown 与双链语法",
    [
        "# Markdown 与双链语法\n\n"
        "## 双向链接\n\n`[[笔记标题]]` 建立链接；`[[标题|显示文字]]` 起别名；`[[标题#小节]]` 指向小节。\n\n"
        "## 标签\n\n正文里写 `#标签名` 会自动成为标签，也可以在标题下方手动添加。",
        "## 内容块\n\n"
        "标题、列表、待办、引用、代码块、表格、分割线、图片、双向链接都可以直接写。\n\n"
        "| 语法 | 效果 |\n| --- | --- |\n| `**粗体**` | **粗体** |\n| `` `代码` `` | `代码` |",
    ],
    ["入门", "语法"],
)

note(
    "本地文件与 Markdown 镜像",
    [
        "# 本地文件与 Markdown 镜像\n\n"
        "每篇笔记都会同步写入 `~/.thisnode/notes/` 下的一个 `.md` 文件，"
        "可以直接用 Obsidian / VS Code 打开。\n\n"
        "在外部编辑并保存后，回到应用 4 秒内会自动同步进来，并重建链接与全文索引。",
    ],
    ["存储"],
)

daily = call("POST", "/api/notes/daily")
page = call("GET", f"/api/notes/{daily['id']}/blocks")
first = page["blocks"][0]["block"]["id"]
call("PATCH", f"/api/notes/{daily['id']}/blocks/{first}/content",
     {"contentMd": f"# {daily['title']}\n\n## 今天\n\n"
                   f"- [ ] 试用卡片：把 [[韧性设计原则]] 拖到顺手的位置\n"
                   f"- [ ] 按 Ctrl+K 搜索「韧性」"})

counts = call("GET", "/api/notes/counts")
stats = call("GET", "/api/maintenance/stats")

# 清空 + 重建数据之后必须重建搜索索引：MySQL 的 InnoDB FULLTEXT 在大量删除后会「变哑」
# （索引进 delete-marked 状态，MATCH 查不到任何东西，LIKE 却正常），只有重建才修得回来。
reindexed = call("POST", "/api/maintenance/reindex")
print(f"搜索索引已重建: {reindexed.get('search', '')}")

print(f"\nvault now: notes {counts['all']} · cards {stats['noteBlocks']} · links {counts['links']} "
      f"· tags {stats['tags']}")
