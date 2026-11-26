package io.github.driflux.jnote.ui

import io.github.driflux.jnote.anchor.CodeAnchor
import io.github.driflux.jnote.model.NewNote

data class NoteDraft(
    val title: String,
    val body: String = "",
    val aiSource: String = "",
    val sourceUrl: String = "",
    val prompt: String = "",
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

    private fun String.trimToNull(): String? = trim().takeIf { it.isNotEmpty() }
}

/** 다이얼로그에 보일 줄 범위. 한 줄이면 `12`, 여러 줄이면 `12-15`. */
internal fun CodeAnchor.linesLabel(): String = if (startLine == endLine) "$startLine" else "$startLine-$endLine"
