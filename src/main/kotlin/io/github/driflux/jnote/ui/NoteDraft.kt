package io.github.driflux.jnote.ui

import io.github.driflux.jnote.anchor.CodeAnchor
import io.github.driflux.jnote.model.NewNote

/** 다이얼로그에 입력된 값. 화면과 저장 사이의 경계라 UI 없이 테스트할 수 있다. */
data class NoteDraft(
    val title: String,
    val body: String = "",
    val aiSource: String = "",
    val sourceUrl: String = "",
    val prompt: String = "",
) {
    /** 앞뒤 공백을 다듬고, 비어 있는 AI 출처 값은 null로 저장한다. 제목이 비어 있으면 거부한다. */
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
