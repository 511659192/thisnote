
import re, io
base = 'D:/projects/thisnote/backend/plugins/notes/src/test/java/com/thisnode/ext/notes/domain/'
files = {
  'ContentAlwaysFreshTest.java': 'index',
  'NoteLinkSearchTest.java': 'index',
  'NoteBlockConflictTest.java': 'index',
  'NoteListTagsTest.java': 'noteIndex',
  'NoteRepositoryTest.java': 'noteIndex',
  'TagRepositoryTest.java': 'index',
}
import_line = 'import com.thisnode.ext.notes.store.NoteIndex;'
CAT = 'import com.thisnode.ext.notes.store.NoteCatalog;'
COMP = 'import com.thisnode.ext.notes.store.NoteCompositionService;'

def strip_line(src, needle):
    out = []
    for line in src.split('\n'):
        if line.strip() == needle:
            continue
        out.append(line)
    return '\n'.join(out)

for name, field in files.items():
    p = base + name
    src = open(p, encoding='utf-8').read()
    before = len(src)
    # 去掉旧 import 与 load/reload 调用
    assert src.count(import_line) == 1, name + ' import'
    src = src.replace(import_line, '@@IMPORTS@@')
    src = strip_line(src, field + '.load();')
    src = strip_line(src, field + '.reload();')
    # 实例化与字段
    src = src.replace('private NoteIndex ' + field + ';', '@@FIELD@@')
    src = src.replace(field + ' = new NoteIndex(store);', '@@NEW@@')
    # 前缀改名
    pairs = [
        (field + '.noteMarkdown(', 'composition.noteMarkdown('),
        (field + '.putBlock(', 'composition.putBlock('),
        (field + '.allBlocks()', 'composition.allBlocks()'),
        (field + '.blocks(', 'composition.blocks('),
        (field + '.block(', 'composition.block('),
        (field + '.content(', 'composition.content('),
        (field + '.fileOf(', 'composition.fileOf('),
        (field + '.noteIdOf(', 'composition.noteIdOf('),
        (field + '.putExtension(', 'composition.putExtension('),
        (field + '.removeNote(', 'composition.removeNote('),
        (field + '.applyExternalChanges()', 'composition.claimUnmanaged()'),
        (field + '.notes()', 'catalog.scan()'),
        (field + '.note(', 'catalog.note('),
        (field + '.putNote(', 'catalog.save('),
    ]
    for old, new in pairs:
        src = src.replace(old, new)
    assert (field + '.') not in src.replace('@@', ''), name + ' leftover ' + field + '.'
    # 按需还原占位符
    needs_comp = 'composition.' in src
    field_decl = 'private NoteCatalog catalog;' + ('\n    private NoteCompositionService composition;' if needs_comp else '')
    new_stmt = 'catalog = TestWiring.noteCatalog(store);' + ('\n        composition = TestWiring.composition(store);' if needs_comp else '')
    used = ('catalog.' in src) or needs_comp
    src = src.replace('@@FIELD@@', field_decl if used else '@@DROP@@')
    src = src.replace('@@NEW@@', new_stmt)
    src = strip_line(src, '@@DROP@@')
    imp = CAT + ('\n' + COMP if needs_comp else '')
    src = src.replace('@@IMPORTS@@', imp)
    after = len(src)
    assert abs(after - before) / before < 0.25, name + ' length guard'
    open(p, 'w', encoding='utf-8').write(src)
    print(name, 'ok; composition needed:', needs_comp, before, '->', after)
