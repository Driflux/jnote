package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteSummary
import io.github.driflux.jnote.model.Tag

data class NoteRow(
    val summary: NoteSummary,
    val subtitle: String,
    val projectName: String?,
    val aiBadge: Boolean,
    val tags: List<Tag> = emptyList(),
) {
    val title: String get() = summary.title
}

object NoteRows {
    fun of(summary: NoteSummary, currentProjectKey: String?, freeLabel: String, tags: List<Tag> = emptyList()): NoteRow = NoteRow(
        summary = summary,
        subtitle = subtitle(summary, freeLabel),
        projectName = projectName(summary, currentProjectKey),
        aiBadge = !summary.aiSource.isNullOrBlank(),
        tags = tags,
    )

    fun subtitle(summary: NoteSummary, freeLabel: String): String {
        if (summary.kind == NoteKind.FREE) return freeLabel
        val place = summary.className?.takeIf { it.isNotBlank() } ?: summary.relPath?.substringAfterLast('/').orEmpty()
        return listOf(place, lineRange(summary.startLine, summary.endLine)).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    fun lineRange(start: Int?, end: Int?): String = when {
        start == null -> ""
        end == null || end == start -> "L$start"
        else -> "L$start-$end"
    }

    private fun projectName(summary: NoteSummary, currentProjectKey: String?): String? {
        val key = summary.project ?: return null
        if (key == currentProjectKey) return null
        return key.trimEnd('/').substringAfterLast('/')
    }
}
