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
    /** 노트에 붙일 태그 이름 전부. 저장하면 노트의 태그가 정확히 이 목록이 된다. */
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

    /** 편집 결과의 글 부분. 태그는 노트 행에 없으므로 들어 있지 않다. */
    fun toText(): NoteText {
        require(title.isNotBlank()) { "title is blank" }
        return NoteText(title.trim(), body.trim(), aiSource.trimToNull(), sourceUrl.trimToNull(), prompt.trimToNull())
    }

    /** 저장할 태그 이름. 다듬고 빈 이름과 중복은 뺀다. 저장소가 사전 등록을 맡는다. */
    fun tagNames(): List<String> = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** AI 출처 구역에 채울 값이 하나라도 있는지. 편집할 때 접어 둔 구역에 값이 숨지 않게 펼치는 기준이다. */
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

/** 다이얼로그에 보일 줄 범위. 한 줄이면 `12`, 여러 줄이면 `12-15`. */
internal fun CodeAnchor.linesLabel(): String = if (startLine == endLine) "$startLine" else "$startLine-$endLine"
