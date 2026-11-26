package io.github.driflux.jnote.model

/**
 * 재배치, 경로 복구, 위치 추적이 노트에 새로 남길 앵커(경로, 줄 범위, 상태). 줄은 1-based, 끝 줄 포함.
 * 노트를 uid로 가리킨다. id는 가장 큰 id의 노트가 지워지면 새 노트에 다시 쓰이므로,
 * 오래 걸린 계산이 끝났을 때 다른 노트에 쓰지 않게 하려는 것이다.
 */
data class AnchorUpdate(
    val uid: String,
    val relPath: String,
    val startLine: Int,
    val endLine: Int,
    val status: NoteStatus,
) {
    init {
        require(relPath.isNotBlank()) { "relPath must not be blank" }
        require(startLine >= 1 && endLine >= startLine) { "invalid line range $startLine..$endLine" }
    }
}
