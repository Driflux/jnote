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
    val currentFileOnly: Boolean = false,
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

    private fun inCurrentFile(row: NoteRow): Boolean =
        !currentFileOnly || (row.summary.kind == NoteKind.CODE && row.projectName == null && row.summary.relPath == currentRelPath && currentRelPath != null)

    val tagFilterActive: Boolean get() = selectedTagIds.isNotEmpty()

    val noSearchResults: Boolean get() = searching && match?.query == query && visibleNotes.isEmpty()

    val showEmptyState: Boolean get() = notes != null && visibleNotes.isEmpty()

    fun withTags(dictionary: List<Tag>): ToolWindowState =
        copy(tags = dictionary, selectedTagIds = selectedTagIds.intersect(dictionary.map { it.id }.toSet()))

    fun toggleTag(id: Long): ToolWindowState =
        copy(selectedTagIds = if (id in selectedTagIds) selectedTagIds - id else selectedTagIds + id)
}
