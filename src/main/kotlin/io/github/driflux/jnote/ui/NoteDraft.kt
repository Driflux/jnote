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

    private fun String.trimToNull(): String? = trim().takeIf { it.isNotEmpty() }

    companion object {
        /** 편집 다이얼로그를 채울 값. */
        fun of(note: Note) = NoteDraft(
            title = note.title,
            body = note.body,
            aiSource = note.aiSource.orEmpty(),
            sourceUrl = note.sourceUrl.orEmpty(),
            prompt = note.prompt.orEmpty(),
        )
    }
}

/** 편집 다이얼로그에 보여줄 앵커 요약. FREE 노트는 앵커가 없어 null. */
internal fun Note.anchorOrNull(): CodeAnchor? {
    if (kind != NoteKind.CODE) return null
    return CodeAnchor(relPath!!, startLine!!, endLine!!, snapshot.orEmpty(), className)
}

/** 다이얼로그에 보일 줄 범위. 한 줄이면 `12`, 여러 줄이면 `12-15`. */
internal fun CodeAnchor.linesLabel(): String = if (startLine == endLine) "$startLine" else "$startLine-$endLine"
