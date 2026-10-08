package com.thisnode.ext.notes.domain;

import com.thisnode.ext.notes.store.NoteCompositionService;
import com.thisnode.ext.notes.store.NoteStore;

/** 测试装配：按生产环境的接法把组合层拼出来。 */
final class TestWiring {

    private TestWiring() {
    }

    static NoteCompositionService composition(NoteStore store) {
        return new NoteCompositionService(store);
    }
}
