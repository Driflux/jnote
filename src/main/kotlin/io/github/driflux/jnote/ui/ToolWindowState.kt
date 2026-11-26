package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.Tag

/** [query]로 검색해 일치한 노트 id. 늦게 도착한 결과가 다른 질의의 것인지 알 수 있도록 질의를 같이 들고 있다. */
data class SearchMatch(val query: String, val ids: Set<Long>)

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
    /** 검색창 입력(앞뒤 공백 제거). 최소 길이보다 짧으면 검색하지 않는다. */
    val query: String = "",
    /** 가장 최근에 받은 검색 결과. 새 결과가 올 때까지 그대로 두어 타이핑 중 목록이 깜빡이지 않게 한다. */
    val match: SearchMatch? = null,
) {
    val searching: Boolean get() = SearchQuery.isSearchable(query)

    /**
     * 종류 필터, 태그 필터, 검색을 모두 거친 행. 아직 읽지 않았다면 비어 있다. 태그는 고른 것을 모두 가진 노트만 남긴다(AND).
     * 검색 결과는 노트 목록과 같은 최근 수정 순이라 목록을 id로 거르면 순서가 그대로 유지된다.
     */
    val visibleNotes: List<NoteRow>
        get() {
            val ids = if (searching) match?.ids else null
            return notes.orEmpty().filter { row ->
                filter.accepts(row.note) &&
                    selectedTagIds.all { id -> row.tags.any { it.id == id } } &&
                    (ids == null || row.note.id in ids)
            }
        }

    /** 태그 칩이 하나라도 켜져 있는지. 빈 목록의 안내 문구를 고르는 기준이다. */
    val tagFilterActive: Boolean get() = selectedTagIds.isNotEmpty()

    /** 현재 질의의 검색 결과가 도착했는데 보일 노트가 없다. 결과가 오기 전에는 false라 안내가 먼저 뜨지 않는다. */
    val noSearchResults: Boolean get() = searching && match?.query == query && visibleNotes.isEmpty()

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
