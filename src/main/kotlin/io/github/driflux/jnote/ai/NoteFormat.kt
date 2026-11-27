package io.github.driflux.jnote.ai

import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus

/** JSON 형식 v1의 노트 한 건. 앵커가 있으면 CODE, 없으면 FREE다. */
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

/** [project]는 정규화된 프로젝트 키다. JSON의 repo_url이나 project_path는 코덱이 이 키로 바꾼다. 줄 번호는 1-based, 끝 줄 포함. */
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

/** 내보내기 전용 정보를 덧붙인 노트. 가져오기는 이 값들을 읽지 않는다. */
data class ExportedNote(
    val note: FormatNote,
    val status: NoteStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

sealed interface DecodeResult {
    /** [skipped]의 항목은 [notes]에 들어가지 않았다. */
    data class Decoded(val notes: List<FormatNote>, val skipped: List<SkippedItem>) : DecodeResult
    data class Rejected(val error: DocumentError) : DecodeResult
}

/** [index]는 notes 배열 안의 0-based 위치, [field]는 형식 문서의 필드 이름이다. */
data class SkippedItem(val index: Int, val uid: String?, val field: String, val problem: FieldProblem)

enum class FieldProblem { MISSING, WRONG_TYPE, INVALID, DUPLICATE }

enum class DocumentError { MALFORMED_JSON, NOT_AN_OBJECT, MISSING_FORMAT_VERSION, UNSUPPORTED_FORMAT_VERSION, MISSING_NOTES }
