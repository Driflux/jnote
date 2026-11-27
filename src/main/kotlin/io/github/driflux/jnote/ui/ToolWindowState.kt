package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.Tag

data class SearchMatch(val query: String, val ids: Set<Long>)

data class ToolWindowState(
    val notes: List<NoteRow>? = null,
    val allProjects: Boolean = false,
    val filter: NoteFilter = NoteFilter.ALL,
    val tags: List<Tag> = emptyList(),
    val selectedTagIds: Set<Long> = emptySet(),
    val query: String = "",
    val match: SearchMatch? = null,
    /** "현재 파일만" 보기. 켜면 [currentRelPath]의 이 프로젝트 CODE 노트만 남는다. */
    val currentFileOnly: Boolean = false,
    /** 편집기에서 선택한 파일의 프로젝트 기준 경로. 열린 파일이 없거나 프로젝트 밖이면 null이라 아무 노트도 맞지 않는다. */
    val currentRelPath: String? = null,
) {
    val searching: Boolean get() = SearchQuery.isSearchable(query)

    val visibleNotes: List<NoteRow>
        get() {
            val ids = if (searching) match?.ids else null
            return notes.orEmpty().filter { row ->
                filter.accepts(row.summary.kind) &&
                    inCurrentFile(row) &&
                    selectedTagIds.all { id -> row.tags.any { it.id == id } } &&
                    (ids == null || row.summary.id in ids)
            }
        }

    // projectName은 다른 프로젝트의 노트에만 채워지므로, CODE 노트에서 null이면 이 프로젝트의 노트다
    private fun inCurrentFile(row: NoteRow): Boolean =
        !currentFileOnly || (row.summary.kind == NoteKind.CODE && row.projectName == null && row.summary.relPath == currentRelPath && currentRelPath != null)

    /** 태그 칩이 하나라도 켜져 있는지. 빈 목록의 안내 문구를 고르는 기준이다. */
    val tagFilterActive: Boolean get() = selectedTagIds.isNotEmpty()

    val noSearchResults: Boolean get() = searching && match?.query == query && visibleNotes.isEmpty()

    val showEmptyState: Boolean get() = notes != null && visibleNotes.isEmpty()

    fun withTags(dictionary: List<Tag>): ToolWindowState =
        copy(tags = dictionary, selectedTagIds = selectedTagIds.intersect(dictionary.map { it.id }.toSet()))

    fun toggleTag(id: Long): ToolWindowState =
        copy(selectedTagIds = if (id in selectedTagIds) selectedTagIds - id else selectedTagIds + id)
}
