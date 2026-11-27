package io.github.driflux.jnote.model

data class NoteText(
    val title: String,
    val body: String,
    val aiSource: String?,
    val sourceUrl: String?,
    val prompt: String?,
)
