package io.github.driflux.jnote.model

data class LocationConfirmation(
    val relPath: String,
    val startLine: Int,
    val endLine: Int,
    val snapshot: String,
)
