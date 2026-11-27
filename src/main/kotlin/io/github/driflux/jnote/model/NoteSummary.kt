package io.github.driflux.jnote.model

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
