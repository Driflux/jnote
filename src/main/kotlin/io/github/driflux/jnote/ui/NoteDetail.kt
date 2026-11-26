package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind

/** AI 출처 구역에 보여줄 값. */
data class AiSourceDetail(val source: String, val sourceUrl: String?, val prompt: String?)

/** 상세 영역이 보여줄 값과 버튼 유무. 화면 부품에 기대지 않으므로 단위 테스트로 검증한다. */
data class NoteDetail(
    val title: String,
    val className: String?,
    /** "src/Foo.kt:L3-5" 꼴. FREE 노트는 null. */
    val location: String?,
    val snippet: String?,
    val body: String,
    /** FREE 노트의 본문만 고정폭으로 보여준다(쿼리 등을 붙여 넣는 용도). */
    val monospaceBody: Boolean,
    val ai: AiSourceDetail?,
    /** FREE 노트는 이동할 곳이 없으므로 버튼을 숨긴다. */
    val canGoToCode: Boolean,
)

object NoteDetails {
    fun of(note: Note): NoteDetail {
        val isCode = note.kind == NoteKind.CODE
        return NoteDetail(
            title = note.title,
            className = note.className?.takeIf { it.isNotBlank() },
            location = if (isCode) location(note) else null,
            snippet = note.snapshot?.takeIf { it.isNotEmpty() },
            body = note.body,
            monospaceBody = !isCode,
            ai = note.aiSource?.takeIf { it.isNotBlank() }?.let {
                AiSourceDetail(it, note.sourceUrl?.takeIf(String::isNotBlank), note.prompt?.takeIf(String::isNotBlank))
            },
            canGoToCode = isCode,
        )
    }

    private fun location(note: Note): String {
        val range = NoteRows.lineRange(note.startLine, note.endLine)
        return listOfNotNull(note.relPath, range.takeIf { it.isNotEmpty() }).joinToString(":")
    }
}
