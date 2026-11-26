package io.github.driflux.jnote.ui

import io.github.driflux.jnote.anchor.CodeAnchor
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind

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

    /**
     * 편집 결과를 [note]에 덮어쓴 노트. 앵커, 종류, 프로젝트, 상태는 그대로 두고 제목, 본문, AI 출처만 바꾼다.
     * 태그는 노트 행에 없으므로 건드리지 않고, updatedAt은 저장소가 갱신한다.
     */
    fun applyTo(note: Note): Note {
        require(title.isNotBlank()) { "title is blank" }
        return note.copy(
            title = title.trim(),
            body = body.trim(),
            aiSource = aiSource.trimToNull(),
            sourceUrl = sourceUrl.trimToNull(),
            prompt = prompt.trimToNull(),
        )
    }

    /** AI 출처 구역에 채울 값이 하나라도 있는지. 편집할 때 접어 둔 구역에 값이 숨지 않게 펼치는 기준이다. */
    fun hasAiSource(): Boolean = listOf(aiSource, sourceUrl, prompt).any { it.isNotBlank() }

    private fun String.trimToNull(): String? = trim().takeIf { it.isNotEmpty() }

    companion object {
        fun of(note: Note) = NoteDraft(
            title = note.title,
            body = note.body,
            aiSource = note.aiSource.orEmpty(),
            sourceUrl = note.sourceUrl.orEmpty(),
            prompt = note.prompt.orEmpty(),
        )
    }
}

internal fun Note.anchorOrNull(): CodeAnchor? {
    if (kind != NoteKind.CODE) return null
    return CodeAnchor(relPath!!, startLine!!, endLine!!, snapshot.orEmpty(), className)
}

/** 다이얼로그에 보일 줄 범위. 한 줄이면 `12`, 여러 줄이면 `12-15`. */
internal fun CodeAnchor.linesLabel(): String = if (startLine == endLine) "$startLine" else "$startLine-$endLine"
