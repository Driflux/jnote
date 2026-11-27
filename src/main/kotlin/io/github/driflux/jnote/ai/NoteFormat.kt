package io.github.driflux.jnote.ai

import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus

data class FormatNote(
    val uid: String?,
    val title: String,
    val body: String = "",
    val tags: List<String> = emptyList(),
    val anchor: FormatAnchor? = null,
    val aiSource: String? = null,
    val sourceUrl: String? = null,
    val prompt: String? = null,
) {
    val kind: NoteKind get() = if (anchor == null) NoteKind.FREE else NoteKind.CODE
}

data class FormatAnchor(
    val project: String,
    val relPath: String,
    val startLine: Int,
    val endLine: Int,
    val className: String? = null,
    val snapshot: String? = null,
) {
    init {
        require(project.isNotBlank() && relPath.isNotBlank()) { "anchor needs a project and a path" }
        require(startLine >= 1 && endLine >= startLine) { "invalid line range $startLine..$endLine" }
    }
}

data class ExportedNote(
    val note: FormatNote,
    val status: NoteStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

sealed interface DecodeResult {
    data class Decoded(val notes: List<FormatNote>, val skipped: List<SkippedItem>) : DecodeResult
    data class Rejected(val error: DocumentError) : DecodeResult
}

data class SkippedItem(val index: Int, val uid: String?, val field: String, val problem: FieldProblem)

enum class FieldProblem { MISSING, WRONG_TYPE, INVALID }

enum class DocumentError { MALFORMED_JSON, NOT_AN_OBJECT, MISSING_FORMAT_VERSION, UNSUPPORTED_FORMAT_VERSION, MISSING_NOTES }
