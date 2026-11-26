package io.github.driflux.jnote.ui

/**
 * 목록 화면의 상태. [notes]가 null이면 아직 한 번도 읽지 않은 것이다.
 * Swing에 기대지 않으므로 빈 상태 규칙을 단위 테스트로 검증한다.
 */
data class ToolWindowState(
    val notes: List<NoteRow>? = null,
    val allProjects: Boolean = false,
) {
    /**
     * 첫 읽기가 끝나기 전에는 안내를 띄우지 않는다(빈 목록으로 잘못 보이지 않게).
     * 다시 읽는 동안에는 이전 상태를 그대로 두어 안내가 깜빡이지 않는다.
     */
    val showEmptyState: Boolean get() = notes != null && notes.isEmpty()
}
