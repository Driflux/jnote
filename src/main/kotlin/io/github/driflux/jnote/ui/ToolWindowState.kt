package io.github.driflux.jnote.ui

/**
 * 목록 화면의 상태. [notes]가 null이면 아직 한 번도 읽지 않은 것이고, 아니면 필터 전의 전체 행이다.
 * Swing에 기대지 않으므로 필터와 빈 상태 규칙을 단위 테스트로 검증한다.
 */
data class ToolWindowState(
    val notes: List<NoteRow>? = null,
    val allProjects: Boolean = false,
    val filter: NoteFilter = NoteFilter.ALL,
) {
    /** 필터를 거친 행. 아직 읽지 않았다면 비어 있다. */
    val visibleNotes: List<NoteRow> get() = notes.orEmpty().filter { filter.accepts(it.note) }

    /**
     * 첫 읽기가 끝나기 전에는 안내를 띄우지 않는다(빈 목록으로 잘못 보이지 않게).
     * 다시 읽는 동안에는 이전 상태를 그대로 두어 안내가 깜빡이지 않는다.
     * 필터 때문에 비어도 안내를 띄운다. 어떤 종류가 없는지는 [filter]가 정한다.
     */
    val showEmptyState: Boolean get() = notes != null && visibleNotes.isEmpty()
}
