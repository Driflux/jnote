package io.github.driflux.jnote.model

/** 사용자가 편집하는 노트의 글 부분. 앵커와 상태는 들어 있지 않다. */
data class NoteText(
    val title: String,
    val body: String,
    val aiSource: String?,
    val sourceUrl: String?,
    val prompt: String?,
)
