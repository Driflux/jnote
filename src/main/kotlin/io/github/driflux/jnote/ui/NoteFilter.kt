package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.NoteKind

enum class NoteFilter(val labelKey: String, val emptyTitleKey: String) {
    ALL("toolWindow.filter.all", "toolWindow.empty.title"),
    CODE("toolWindow.filter.code", "toolWindow.empty.code.title"),
    FREE("toolWindow.filter.free", "toolWindow.empty.free.title"),
    ;

    fun accepts(kind: NoteKind): Boolean = when (this) {
        ALL -> true
        CODE -> kind == NoteKind.CODE
        FREE -> kind == NoteKind.FREE
    }
}
