package io.github.driflux.jnote.gutter

import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.model.Tag

/**
 * 거터가 메모리에 들고 있는 노트 한 건. 마우스를 올렸을 때 보이는 미리보기(이슈 23)가
 * DB를 다시 읽지 않도록 제목, 본문, 태그, AI 출처, 상태, 줄 범위, 스니펫 앞부분을 모두 담는다.
 */
data class CachedNote(
    val id: Long,
    val title: String,
    val body: String,
    val tags: List<String>,
    val aiSource: String?,
    val status: NoteStatus,
    val startLine: Int,
    val endLine: Int,
    val snippetHead: String,
) {
    companion object {
        const val SNIPPET_LINES = 5

        // 한 줄이 비정상적으로 긴 코드(minified 등)가 캐시를 부풀리지 않게 한다
        const val SNIPPET_MAX_CHARS = 600

        /** 앵커가 없는 노트(FREE)는 거터에 올릴 수 없어 null. */
        fun of(note: Note, tags: List<Tag>): CachedNote? {
            val start = note.startLine ?: return null
            val end = note.endLine ?: return null
            return CachedNote(
                id = note.id,
                title = note.title,
                body = note.body,
                tags = tags.map { it.name },
                aiSource = note.aiSource,
                status = note.status,
                startLine = start,
                endLine = end,
                snippetHead = snippetHead(note.snapshot),
            )
        }

        internal fun snippetHead(snapshot: String?): String =
            snapshot?.lineSequence()?.take(SNIPPET_LINES)?.joinToString("\n")?.take(SNIPPET_MAX_CHARS).orEmpty()
    }
}

/** 한 줄에 마커로 묶이는 노트들. [notes]는 id순이다. */
data class LineGroup(val line: Int, val notes: List<CachedNote>) {
    /** 모두 근사 위치(APPROX)일 때만 근사 아이콘을 쓴다. 정확한 노트가 하나라도 있으면 일반 아이콘. */
    val approxOnly: Boolean get() = notes.all { it.status == NoteStatus.APPROX }
}

object GutterLines {
    /** OK, APPROX 노트만 시작 줄별로 묶어 줄 번호순으로 돌려준다. ORPHAN과 NEEDS_RELOCATE는 아이콘을 그리지 않는다. */
    fun group(notes: List<CachedNote>): List<LineGroup> =
        notes.filter { it.status == NoteStatus.OK || it.status == NoteStatus.APPROX }
            .groupBy { it.startLine }
            .toSortedMap()
            .map { (line, same) -> LineGroup(line, same.sortedBy { it.id }) }
}
