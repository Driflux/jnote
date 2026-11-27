package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.NoteKind

/** 번들 키만 들고 있어 Swing 없이 테스트한다. */
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
