package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.Tag

/**
 * [notes]가 null이면 아직 한 번도 읽지 않은 것이고, 아니면 필터 전의 전체 행이다.
 * Swing에 기대지 않으므로 필터와 빈 상태 규칙을 단위 테스트로 검증한다.
 */
data class ToolWindowState(
    val notes: List<NoteRow>? = null,
    val allProjects: Boolean = false,
    val filter: NoteFilter = NoteFilter.ALL,
    val tags: List<Tag> = emptyList(),
    /** 켜 둔 태그 칩. 비어 있으면 태그로는 거르지 않는다. */
    val selectedTagIds: Set<Long> = emptySet(),
) {
    /** 종류 필터와 태그 필터를 모두 거친 행. 아직 읽지 않았다면 비어 있다. 태그는 고른 것을 모두 가진 노트만 남긴다(AND). */
    val visibleNotes: List<NoteRow>
        get() = notes.orEmpty().filter { row ->
            filter.accepts(row.note) && selectedTagIds.all { id -> row.tags.any { it.id == id } }
        }

    /** 태그 칩이 하나라도 켜져 있는지. 빈 목록의 안내 문구를 고르는 기준이다. */
    val tagFilterActive: Boolean get() = selectedTagIds.isNotEmpty()

    /**
     * 첫 읽기가 끝나기 전에는 안내를 띄우지 않는다(빈 목록으로 잘못 보이지 않게).
     * 다시 읽는 동안에는 이전 상태를 그대로 두어 안내가 깜빡이지 않는다.
     * 필터 때문에 비어도 안내를 띄운다. 어떤 종류가 없는지는 [filter]가 정한다.
     */
    val showEmptyState: Boolean get() = notes != null && visibleNotes.isEmpty()

    /** 읽은 사전에서 사라진 태그(다른 곳에서 지움)를 켜 둔 칩에서 뺀다. */
    fun withTags(dictionary: List<Tag>): ToolWindowState =
        copy(tags = dictionary, selectedTagIds = selectedTagIds.intersect(dictionary.map { it.id }.toSet()))

    fun toggleTag(id: Long): ToolWindowState =
        copy(selectedTagIds = if (id in selectedTagIds) selectedTagIds - id else selectedTagIds + id)
}
