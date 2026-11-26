package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind

/** 목록에 보일 노트 종류. 번들 키만 들고 있어 Swing 없이 테스트한다. */
enum class NoteFilter(val labelKey: String, val emptyTitleKey: String) {
    ALL("toolWindow.filter.all", "toolWindow.empty.title"),
    CODE("toolWindow.filter.code", "toolWindow.empty.code.title"),
    FREE("toolWindow.filter.free", "toolWindow.empty.free.title"),
    ;

    fun accepts(note: Note): Boolean = when (this) {
        ALL -> true
        CODE -> note.kind == NoteKind.CODE
        FREE -> note.kind == NoteKind.FREE
    }
}
