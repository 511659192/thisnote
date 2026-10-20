package com.thisnote.plugin.api.event;

import java.util.ArrayList;
import java.util.List;

/**
 * 单篇笔记在 Graphify 知识图谱中的只读上下文。
 */
public record GraphifyNoteContext(boolean available,
                                  List<String> concepts,
                                  List<String> communities,
                                  List<RelatedNote> relatedNotes) {

    public GraphifyNoteContext {
        concepts = concepts == null ? List.of() : new ArrayList<>(concepts);
        communities = communities == null ? List.of() : new ArrayList<>(communities);
        relatedNotes = relatedNotes == null ? List.of() : new ArrayList<>(relatedNotes);
    }

    public record RelatedNote(String noteId,
                              String title,
                              List<String> reasons) {

        public RelatedNote {
            reasons = reasons == null ? List.of() : new ArrayList<>(reasons);
        }
    }
}
