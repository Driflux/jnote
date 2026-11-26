package io.github.driflux.jnote.model

/** 스키마 v1의 열과 1:1로 대응한다. 줄 번호는 1-based, 끝 줄 포함. */
data class Note(
    val id: Long,
    val uid: String,
    val kind: NoteKind,
    val project: String?,
    val relPath: String?,
    val className: String?,
    val startLine: Int?,
    val endLine: Int?,
    val snapshot: String?,
    val status: NoteStatus,
    val title: String,
    val body: String,
    val aiSource: String?,
    val sourceUrl: String?,
    val prompt: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        requireValidShape(kind, project, relPath, startLine, endLine)
    }
}

/** CODE는 프로젝트, 경로, 줄 범위가 필수이고 FREE는 앵커와 프로젝트가 없어야 한다. */
internal fun requireValidShape(kind: NoteKind, project: String?, relPath: String?, startLine: Int?, endLine: Int?) {
    when (kind) {
        NoteKind.CODE -> {
            require(!project.isNullOrBlank()) { "CODE note needs a project" }
            require(!relPath.isNullOrBlank()) { "CODE note needs relPath" }
            require(startLine != null && endLine != null) { "CODE note needs a line range" }
            require(startLine >= 1 && endLine >= startLine) { "invalid line range $startLine..$endLine" }
        }
        NoteKind.FREE -> {
            require(project == null) { "FREE note has no project" }
            require(relPath == null && startLine == null && endLine == null) { "FREE note has no anchor" }
        }
    }
}
