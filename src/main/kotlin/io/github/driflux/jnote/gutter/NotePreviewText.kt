package io.github.driflux.jnote.gutter

import io.github.driflux.jnote.model.NoteStatus

/** 미리보기에 보일 글을 만드는 순수 계산. */
internal object NotePreviewText {
    /** 한 미리보기에 한꺼번에 보이는 노트 수. 넘치면 개수만 알린다. */
    const val MAX_CARDS = 3
    const val BODY_MAX_LINES = 8
    const val BODY_MAX_CHARS = 500

    /** 본문 앞부분. 줄 수와 글자 수 상한을 넘으면 줄임표를 붙인다. */
    fun body(body: String): String {
        // 큰 본문도 보일 만큼만 읽고 멈춘다. 앞뒤 공백은 처음과 끝에서 안쪽으로 찾는다
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

    /** [line](1-based)을 범위 안에 두는, 거터에 올라가는 노트. 아이콘이 있는 시작 줄과 막대가 덮는 나머지 줄이 모두 해당한다. */
    fun covering(notes: List<CachedNote>, line: Int): List<CachedNote> =
        notes.filter { (it.status == NoteStatus.OK || it.status == NoteStatus.APPROX) && line in it.startLine..it.endLine }
            .sortedBy { it.id }
}
