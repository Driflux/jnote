package io.github.driflux.jnote.gutter

import io.github.driflux.jnote.model.NoteStatus

internal object NotePreviewText {
    const val MAX_CARDS = 3
    const val BODY_MAX_LINES = 8
    const val BODY_MAX_CHARS = 500

    fun body(body: String): String {
        val first = body.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return ""
        var end = body.indexOfLast { !it.isWhitespace() } + 1
        var truncated = false
        var lines = 1
        var i = first
        while (i < end) {
            if (i - first >= BODY_MAX_CHARS) {
                end = i
                truncated = true
                break
            }
            if (body[i] == '\n' && ++lines > BODY_MAX_LINES) {
                end = i
                truncated = true
                break
            }
            i++
        }
        val cut = body.substring(first, end)
        return if (truncated) cut.trimEnd() + "…" else cut
    }

    fun tags(tags: List<String>): String = tags.joinToString(" ") { "#$it" }

    fun covering(notes: List<CachedNote>, line: Int): List<CachedNote> =
        notes.filter { (it.status == NoteStatus.OK || it.status == NoteStatus.APPROX) && line in it.startLine..it.endLine }
            .sortedBy { it.id }
}
