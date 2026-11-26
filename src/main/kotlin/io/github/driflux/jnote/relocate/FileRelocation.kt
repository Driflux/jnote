package io.github.driflux.jnote.relocate

import io.github.driflux.jnote.model.AnchorUpdate
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus

/** 한 파일에 앵커된 노트를 그 파일의 지금 내용에서 다시 찾는다. 순수 로직이며 스레드와 I/O를 쓰지 않는다. */
object FileRelocation {
    /**
     * 스냅샷이 있는 CODE 노트마다 새 앵커를 [notes] 순서대로 하나씩 돌려준다. 스냅샷이 비었으면 찾을 근거가 없어 뺀다.
     * 정확히 일치하면 OK, 비슷하면 APPROX다. 못 찾으면 경로와 줄은 그대로 두고, 유사도까지 찾아봤으면 ORPHAN이다.
     * [similarity]를 꺼서 정확 일치만 찾았으면 OK 노트만 NEEDS_RELOCATE가 되고 나머지는 상태도 그대로다.
     * APPROX, ORPHAN, NEEDS_RELOCATE 노트는 원래 정확 일치로 찾을 수 없던 노트라, 정확 일치 실패가 새 사실이 아니기 때문이다.
     * [isCancelled]가 true가 되면 null이다. 취소된 [Relocator.locate]도 None을 돌려주므로, 취소를 ORPHAN으로 적지 않게 여기서 가른다.
     * [onProgress]는 노트를 하나 끝낼 때마다 (끝낸 수, 전체 수)로 불린다.
     */
    fun relocate(
        notes: List<Note>,
        documentLines: List<String>,
        similarity: Boolean = true,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<AnchorUpdate>? {
        val targets = notes.filter { it.kind == NoteKind.CODE && !it.snapshot.isNullOrBlank() }
        val options = RelocationOptions(similarity = similarity, isCancelled = isCancelled)
        val updates = ArrayList<AnchorUpdate>(targets.size)
        for ((index, note) in targets.withIndex()) {
            if (isCancelled()) return null
            // CODE 노트는 경로와 줄 범위가 늘 있다(Note의 init 검사)
            val relPath = note.relPath!!
            val start = note.startLine!!
            val end = note.endLine!!
            val result = Relocator.locate(documentLines, note.snapshot!!.lines(), start, options)
            if (isCancelled()) return null
            updates += when (result) {
                is RelocationResult.Match -> AnchorUpdate(note.uid, relPath, result.startLine, result.endLine, NoteStatus.OK)
                is RelocationResult.Approx -> AnchorUpdate(note.uid, relPath, result.startLine, result.endLine, NoteStatus.APPROX)
                RelocationResult.None -> AnchorUpdate(note.uid, relPath, start, end, notFoundStatus(note.status, similarity))
            }
            onProgress(index + 1, targets.size)
        }
        return updates
    }

    private fun notFoundStatus(current: NoteStatus, similarity: Boolean): NoteStatus = when {
        similarity -> NoteStatus.ORPHAN
        current == NoteStatus.OK -> NoteStatus.NEEDS_RELOCATE
        else -> current
    }
}
