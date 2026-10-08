
base = 'D:/projects/thisnote/backend/plugins/notes/src/test/java/com/thisnode/ext/notes/'
MIG_IMPORT = 'import com.thisnode.ext.notes.store.NoteMigrationService;'
CAT = 'import com.thisnode.ext.notes.store.NoteCatalog;'
COMP = 'import com.thisnode.ext.notes.store.NoteCompositionService;'

def strip_line(src, needle):
    return '\n'.join(l for l in src.split('\n') if l.strip() != needle)

# ---- NoteInboundCountTest（domain）----
p = base + 'domain/NoteInboundCountTest.java'
src = open(p, encoding='utf-8').read()
before = len(src)
old_import = 'import com.thisnode.ext.notes.store.NoteIndex;'
assert src.count(old_import) == 1
src = src.replace(old_import, CAT + '\n' + COMP + '\n' + MIG_IMPORT)
src = strip_line(src, 'index.load();')
src = src.replace('private NoteIndex index;', 'private NoteCatalog catalog;\n    private NoteCompositionService composition;')
src = src.replace('index = new NoteIndex(store);',
                  'catalog = TestWiring.noteCatalog(store);\n        composition = TestWiring.composition(store);')
old_block = '''        NoteIndex fresh = new NoteIndex(store);
        fresh.load();'''
new_block = '''        NoteMigrationService migration = new NoteMigrationService(store, store.paths(),
                TestWiring.noteCatalog(store), TestWiring.cardCatalog(store), TestWiring.composition(store));
        migration.run();'''
assert src.count(old_block) == 1, 'fresh block'
src = src.replace(old_block, new_block)
src = src.replace('fresh.note(', 'catalog.note(')
for old, new in [('index.noteMarkdown(', 'composition.noteMarkdown('), ('index.notes()', 'catalog.scan()'),
                 ('index.note(', 'catalog.note(')]:
    src = src.replace(old, new)
assert 'NoteIndex' not in src and 'fresh.' not in src, 'inbound leftover'
after = len(src)
assert abs(after - before) / before < 0.3, 'inbound length guard'
open(p, 'w', encoding='utf-8').write(src)
print('NoteInboundCountTest ok', before, '->', after)

# ---- NoteLoadPerfProbe（store，@Disabled 探针）----
p = base + 'store/NoteLoadPerfProbe.java'
src = open(p, encoding='utf-8').read()
before = len(src)
old_load = '''            long t0 = System.nanoTime();
            NoteIndex index = new NoteIndex(store);
            index.load();
            long loadMs = (System.nanoTime() - t0) / 1_000_000;

            long t1 = System.nanoTime();
            index.reload();
            long reloadMs = (System.nanoTime() - t1) / 1_000_000;'''
new_load = '''            NoteCatalog catalog = new NoteCatalog(store, store.paths());
            NoteCompositionService composition = new NoteCompositionService(catalog,
                    new CardCatalog(store, store.paths()), store.paths());
            NoteMigrationService migration = new NoteMigrationService(store, store.paths(), catalog,
                    new CardCatalog(store, store.paths()), composition);

            long t0 = System.nanoTime();
            migration.run();
            long loadMs = (System.nanoTime() - t0) / 1_000_000;

            long t1 = System.nanoTime();
            catalog.scan();
            long reloadMs = (System.nanoTime() - t1) / 1_000_000;'''
assert src.count(old_load) == 1, 'probe load block'
src = src.replace(old_load, new_load)
src = src.replace('index.notes()', 'catalog.scan()')
src = src.replace('index.blocks(', 'composition.blocks(')
src = src.replace('index.noteMarkdown(', 'composition.noteMarkdown(')
src = src.replace('index.allBlocks()', 'composition.allBlocks()')
assert 'NoteIndex' not in src, 'probe leftover'
after = len(src)
assert abs(after - before) / before < 0.3, 'probe length guard'
open(p, 'w', encoding='utf-8').write(src)
print('NoteLoadPerfProbe ok', before, '->', after)
