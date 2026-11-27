package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteSummary
import io.github.driflux.jnote.model.Tag

/** 목록 한 행에 보여줄 값. Swing과 번들에 기대지 않으므로 단위 테스트로 검증한다. */
data class NoteRow(
    val summary: NoteSummary,
    val subtitle: String,
    /** 다른 프로젝트의 노트일 때만 채운다. */
    val projectName: String?,
    val aiBadge: Boolean,
    /** 이름순. 태그가 없으면 비어 있다. */
    val tags: List<Tag> = emptyList(),
) {
    val title: String get() = summary.title
}

object NoteRows {
    /** [freeLabel]은 FREE 노트의 부제목으로, 번들 문자열을 호출한 쪽에서 넘긴다. */
    fun of(summary: NoteSummary, currentProjectKey: String?, freeLabel: String, tags: List<Tag> = emptyList()): NoteRow = NoteRow(
        summary = summary,
        subtitle = subtitle(summary, freeLabel),
        projectName = projectName(summary, currentProjectKey),
        aiBadge = !summary.aiSource.isNullOrBlank(),
        tags = tags,
    )

    /** CODE는 "클래스 · L3-5"(클래스가 없으면 파일 이름), FREE는 [freeLabel]. */
    fun subtitle(summary: NoteSummary, freeLabel: String): String {
        if (summary.kind == NoteKind.FREE) return freeLabel
        val place = summary.className?.takeIf { it.isNotBlank() } ?: summary.relPath?.substringAfterLast('/').orEmpty()
        return listOf(place, lineRange(summary.startLine, summary.endLine)).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    fun lineRange(start: Int?, end: Int?): String = when {
        start == null -> ""
        end == null || end == start -> "L$start"
        else -> "L$start-$end"
    }

    private fun projectName(summary: NoteSummary, currentProjectKey: String?): String? {
        val key = summary.project ?: return null
        if (key == currentProjectKey) return null
        // 키는 host/org/repo 또는 경로 꼴이라 마지막 조각이 사람이 알아볼 이름이다
        return key.trimEnd('/').substringAfterLast('/')
    }
}
