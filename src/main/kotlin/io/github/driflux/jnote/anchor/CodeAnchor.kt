package io.github.driflux.jnote.anchor

/** 노트가 붙을 코드 위치. 줄 번호는 1-based, 끝 줄 포함. [relPath]는 프로젝트 루트 기준이며 `/`로 구분한다. */
data class CodeAnchor(
    val relPath: String,
    val startLine: Int,
    val endLine: Int,
    val snapshot: String,
    val className: String?,
)
