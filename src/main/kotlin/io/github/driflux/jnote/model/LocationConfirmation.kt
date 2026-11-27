package io.github.driflux.jnote.model

/** 사용자가 본 위치와 그 줄의 지금 코드. 저장된 위치가 아직 이 위치일 때만 OK로 굳힌다. */
data class LocationConfirmation(
    val relPath: String,
    val startLine: Int,
    val endLine: Int,
    val snapshot: String,
)
