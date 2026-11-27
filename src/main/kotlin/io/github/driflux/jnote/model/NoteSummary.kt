package io.github.driflux.jnote.model

/** 목록 한 행에 필요한 열만 담는다. 스냅샷, 본문, 프롬프트처럼 큰 텍스트는 읽지 않는다. */
data class NoteSummary(
    val id: Long,
    val uid: String,
    val kind: NoteKind,
    val project: String?,
    val relPath: String?,
    val className: String?,
    val startLine: Int?,
    val endLine: Int?,
    val status: NoteStatus,
    val title: String,
    val aiSource: String?,
    val updatedAt: Long,
)
