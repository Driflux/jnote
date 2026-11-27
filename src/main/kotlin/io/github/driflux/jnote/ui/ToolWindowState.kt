package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.Tag

data class SearchMatch(val query: String, val ids: Set<Long>)

/** "현재 파일" 필터가 보는 활성 편집기. 편집기가 없는 것과 프로젝트 밖 파일은 안내가 달라서 나눈다. */
sealed interface CurrentFile {
    data object None : CurrentFile
    data object OutsideProject : CurrentFile
    data class InProject(val relPath: String) : CurrentFile
}

data class ToolWindowState(
    val notes: List<NoteRow>? = null,
    val allProjects: Boolean = false,
    val filter: NoteFilter = NoteFilter.ALL,
    val tags: List<Tag> = emptyList(),
    val selectedTagIds: Set<Long> = emptySet(),
    val query: String = "",
    val match: SearchMatch? = null,
    val currentFileOnly: Boolean = false,
    val currentFile: CurrentFile = CurrentFile.None,
    val aiOnly: Boolean = false,
) {
    val searching: Boolean get() = SearchQuery.isSearchable(query)

    val visibleNotes: List<NoteRow>
        get() {
            val ids = if (searching) match?.ids else null
            return notes.orEmpty().filter { row ->
                filter.accepts(row.summary.kind) &&
                    (!aiOnly || row.aiBadge) &&
                    inCurrentFile(row) &&
                    selectedTagIds.all { id -> row.tags.any { it.id == id } } &&
                    (ids == null || row.summary.id in ids)
            }
        }

    private fun inCurrentFile(row: NoteRow): Boolean =
        !currentFileOnly || (row.summary.kind == NoteKind.CODE && row.projectName == null && row.summary.relPath == (currentFile as? CurrentFile.InProject)?.relPath)

    val aiFilterEmptiedTheList: Boolean get() = aiOnly && showEmptyState && copy(aiOnly = false).visibleNotes.isNotEmpty()

    val tagFilterActive: Boolean get() = selectedTagIds.isNotEmpty()

    val noSearchResults: Boolean get() = searching && match?.query == query && visibleNotes.isEmpty()

    val showEmptyState: Boolean get() = notes != null && visibleNotes.isEmpty()

    fun withTags(dictionary: List<Tag>): ToolWindowState =
        copy(tags = dictionary, selectedTagIds = selectedTagIds.intersect(dictionary.map { it.id }.toSet()))

    fun toggleTag(id: Long): ToolWindowState =
        copy(selectedTagIds = if (id in selectedTagIds) selectedTagIds - id else selectedTagIds + id)
}
