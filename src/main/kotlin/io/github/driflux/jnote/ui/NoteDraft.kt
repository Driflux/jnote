package io.github.driflux.jnote.ui

import io.github.driflux.jnote.anchor.CodeAnchor
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteText

data class NoteDraft(
    val title: String,
    val body: String = "",
    val aiSource: String = "",
    val sourceUrl: String = "",
    val prompt: String = "",
    val tags: List<String> = emptyList(),
) {
    fun toNewNote(project: String, anchor: CodeAnchor): NewNote {
        require(title.isNotBlank()) { "title is blank" }
        return NewNote.code(
            project = project,
            relPath = anchor.relPath,
            startLine = anchor.startLine,
            endLine = anchor.endLine,
            title = title.trim(),
            body = body.trim(),
            className = anchor.className,
            snapshot = anchor.snapshot,
            aiSource = aiSource.trimToNull(),
            sourceUrl = sourceUrl.trimToNull(),
            prompt = prompt.trimToNull(),
        )
    }

    fun toFreeNote(): NewNote {
        require(title.isNotBlank()) { "title is blank" }
        return NewNote.free(
            title = title.trim(),
            body = body.trim(),
            aiSource = aiSource.trimToNull(),
            sourceUrl = sourceUrl.trimToNull(),
            prompt = prompt.trimToNull(),
        )
    }

    fun toText(): NoteText {
        require(title.isNotBlank()) { "title is blank" }
        return NoteText(title.trim(), body.trim(), aiSource.trimToNull(), sourceUrl.trimToNull(), prompt.trimToNull())
    }

    fun tagNames(): List<String> = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun hasAiSource(): Boolean = listOf(aiSource, sourceUrl, prompt).any { it.isNotBlank() }

    private fun String.trimToNull(): String? = trim().takeIf { it.isNotEmpty() }

    companion object {
        fun of(note: Note, tags: List<String> = emptyList()) = NoteDraft(
            title = note.title,
            body = note.body,
            aiSource = note.aiSource.orEmpty(),
            sourceUrl = note.sourceUrl.orEmpty(),
            prompt = note.prompt.orEmpty(),
            tags = tags,
        )
    }
}

internal fun Note.anchorOrNull(): CodeAnchor? {
    if (kind != NoteKind.CODE) return null
    return CodeAnchor(relPath!!, startLine!!, endLine!!, snapshot.orEmpty(), className)
}

internal fun CodeAnchor.linesLabel(): String = if (startLine == endLine) "$startLine" else "$startLine-$endLine"
