package io.github.driflux.jnote.gutter

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.model.AnchorUpdate
import io.github.driflux.jnote.model.NoteStatus

/**
 * 열린 문서에서 노트 위치를 RangeMarker로 따라간다(ADR 0009). 마커 패스는 데몬 백그라운드 스레드의 읽기 액션에서,
 * 저장과 파일 닫기는 EDT에서, 쓰기 확인은 DB 스레드에서 부르므로 모든 접근을 한 잠금으로 감싼다.
 */
internal class NotePositions {
    /** DB 행의 위치 부분. 1-based, 끝 줄 포함. */
    internal data class Placement(val start: Int, val end: Int, val status: NoteStatus)

    /**
     * [stored]는 이 추적기가 아는 DB 값, [seen]은 캐시가 마지막으로 보여 준 값이다.
     * 빈 줄 노트의 마커는 처음부터 비어 있으므로, 비어 있지 않던 마커가 비었을 때만 범위가 지워진 것이다.
     */
    private class Tracked(val marker: RangeMarker, val nonEmpty: Boolean, var stored: Placement, var seen: Placement) {
        val lost: Boolean get() = !marker.isValid || (nonEmpty && marker.startOffset == marker.endOffset)

        /** DB에 있어야 할 위치. 범위가 지워졌으면 저장된 줄을 재배치 힌트로 남긴다. */
        fun target(document: Document): Placement =
            if (lost) {
                stored.copy(status = NoteStatus.NEEDS_RELOCATE)
            } else {
                Placement(document.getLineNumber(marker.startOffset) + 1, document.getLineNumber(marker.endOffset) + 1, stored.status)
            }
    }

    private class TrackedDocument(val database: Database, val relPath: String, val notes: HashMap<String, Tracked>) {
        fun dispose() = notes.values.forEach { it.marker.dispose() }
    }

    /** 쓰는 노트 하나. [marker]는 그 노트를 아직 같은 마커가 따라가는지 [confirm]이 알아보는 데 쓴다. */
    internal class Pending(val uid: String, val marker: RangeMarker, val target: Placement)

    class WriteBack internal constructor(val database: Database, val relPath: String, internal val pending: List<Pending>) {
        val updates: List<AnchorUpdate> = pending.map { AnchorUpdate(it.uid, relPath, it.target.start, it.target.end, it.target.status) }
    }

    private val lock = Any()
    private val documents = HashMap<Document, TrackedDocument>()

    /**
     * 캐시의 노트를 마커와 맞춘다. 추적 중인 노트는 마커의 현재 줄로 바꾸고, 범위가 지워진 노트는 뺀다.
     * 추적하지 않는 노트(ORPHAN, NEEDS_RELOCATE, 문서 밖)는 그대로 돌려준다.
     */
    fun sync(document: Document, relPath: String, database: Database, notes: List<CachedNote>): List<CachedNote> = synchronized(lock) {
        // 위치는 노트를 읽어 온 DB와 경로로 써야 하므로 둘 중 하나가 바뀌면 새로 시작한다
        val tracked = documents[document]?.takeIf { it.database === database && it.relPath == relPath } ?: run {
            documents.remove(document)?.dispose()
            TrackedDocument(database, relPath, HashMap())
        }
        for (uid in tracked.notes.keys - notes.mapTo(HashSet()) { it.uid }) tracked.notes.remove(uid)!!.marker.dispose()
        val result = ArrayList<CachedNote>(notes.size)
        for (note in notes) {
            val current = tracked.reconcile(document, note.uid, Placement(note.startLine, note.endLine, note.status))
            when {
                current == null -> result += note
                current.lost -> {}
                else -> {
                    val lines = current.target(document)
                    result += note.copy(startLine = lines.start, endLine = lines.end)
                }
            }
        }
        if (tracked.notes.isEmpty()) documents.remove(document) else documents[document] = tracked
        result
    }

    /** 편집이 옮긴 노트와 범위가 지워진 노트의 새 위치. DB에 있다고 아는 값과 같은 노트는 빼고, 쓸 것이 없으면 null. */
    fun writeBackFor(document: Document): WriteBack? = synchronized(lock) {
        val tracked = documents[document] ?: return null
        val pending = tracked.notes.mapNotNull { (uid, note) ->
            val target = note.target(document)
            if (target == note.stored) null else Pending(uid, note.marker, target)
        }
        if (pending.isEmpty()) null else WriteBack(tracked.database, tracked.relPath, pending)
    }

    /**
     * 쓰기가 성공한 뒤 쓴 값을 stored로 삼는다. 쓰는 사이 다른 쓰기를 보고 마커를 다시 만들었거나, 문서를 닫았다 다시 열어
     * 새 마커로 추적하는 노트는 건너뛴다. 그 노트는 다시 읽힌 캐시가 DB 값과 맞춘다.
     * stored가 쓰기 직전 값인지는 보지 않는다. 첫 쓰기가 끝나기 전에 시작한 다음 저장도 자기가 쓴 값을 남겨야 한다.
     */
    fun confirm(document: Document, writeBack: WriteBack) {
        synchronized(lock) {
            val notes = documents[document]?.notes ?: return
            for (written in writeBack.pending) {
                val note = notes[written.uid]
                if (note != null && note.marker === written.marker) note.stored = written.target
            }
        }
    }

    fun forget(document: Document) {
        synchronized(lock) { documents.remove(document)?.dispose() }
    }

    fun clear() {
        synchronized(lock) {
            documents.values.forEach { it.dispose() }
            documents.clear()
        }
    }

    fun isTracking(document: Document): Boolean = synchronized(lock) { document in documents }

    private fun TrackedDocument.reconcile(document: Document, uid: String, cache: Placement): Tracked? {
        val current = notes[uid]
        if (cache.status == NoteStatus.ORPHAN || cache.status == NoteStatus.NEEDS_RELOCATE) {
            notes.remove(uid)?.marker?.dispose()
            return null
        }
        if (current != null) {
            // 캐시가 지난번과 같으면 그 줄은 편집 전 값일 뿐이므로 마커가 따라간 위치를 믿는다
            if (cache == current.seen) return current
            current.seen = cache
            if (cache == current.stored) return current
            // 다른 쓰기(열 때 검증, 수동 재배치, 가져오기)가 DB 값을 바꿨다
            if (cache.start == current.stored.start && cache.end == current.stored.end) {
                current.stored = cache
                return current
            }
            notes.remove(uid)!!.marker.dispose()
        }
        if (cache.start < 1 || cache.end > document.lineCount) return null
        val marker = document.createRangeMarker(document.getLineStartOffset(cache.start - 1), document.getLineEndOffset(cache.end - 1))
        return Tracked(marker, marker.startOffset != marker.endOffset, cache, cache).also { notes[uid] = it }
    }
}
