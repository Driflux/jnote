package io.github.driflux.jnote.model

/** 저장 전 노트 초안. id, uid, 타임스탬프는 저장소가 채운다. 줄 번호는 1-based, 끝 줄 포함. */
data class NewNote(
    val kind: NoteKind,
    val project: String?,
    val relPath: String?,
    val className: String?,
    val startLine: Int?,
    val endLine: Int?,
    val snapshot: String?,
    val status: NoteStatus = NoteStatus.OK,
    val title: String,
    val body: String = "",
    val aiSource: String? = null,
    val sourceUrl: String? = null,
    val prompt: String? = null,
) {
    init {
        requireValidShape(kind, project, relPath, startLine, endLine)
    }

    companion object {
        fun code(
            project: String,
            relPath: String,
            startLine: Int,
            endLine: Int,
            title: String,
            body: String = "",
            className: String? = null,
            snapshot: String? = null,
            status: NoteStatus = NoteStatus.OK,
            aiSource: String? = null,
            sourceUrl: String? = null,
            prompt: String? = null,
        ) = NewNote(
            NoteKind.CODE, project, relPath, className, startLine, endLine, snapshot,
            status, title, body, aiSource, sourceUrl, prompt,
        )

        fun free(
            title: String,
            body: String = "",
            aiSource: String? = null,
            sourceUrl: String? = null,
            prompt: String? = null,
        ) = NewNote(
            NoteKind.FREE, null, null, null, null, null, null,
            NoteStatus.OK, title, body, aiSource, sourceUrl, prompt,
        )
    }
}
