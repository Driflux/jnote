package io.github.driflux.jnote.ai

import io.github.driflux.jnote.db.COLUMNS
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.toNote
import io.github.driflux.jnote.model.Note
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 노트를 형식 v1 파일로 쓴다. DB를 기다리고 파일을 쓰므로 EDT 밖에서 부른다. */
class NoteExporter(private val database: Database) {
    /**
     * [selectedUids]가 null이면 모든 프로젝트의 노트 전부를, 아니면 그중 남아 있는 노트만 최근 수정 순으로 쓰고 쓴 수를 돌려준다.
     * 임시 파일에 다 쓴 뒤 한 번에 바꿔 끼우므로, [checkCanceled]가 던지거나 실패하면 [target]은 이전 그대로다.
     */
    fun export(target: Path, selectedUids: Set<String>?, checkCanceled: () -> Unit): Int {
        checkCanceled()
        val (all, tagsByNote) = database.submit { c -> readSnapshot(c) }.get()
        checkCanceled()
        val chosen = if (selectedUids == null) all else all.filter { it.uid in selectedUids }
        val exported = chosen.map { toExported(it, tagsByNote[it.id].orEmpty()) }
        val partial = Files.createTempFile(target.toAbsolutePath().parent, "jnote-export-", ".tmp")
        try {
            Files.newBufferedWriter(partial, StandardCharsets.UTF_8).use { NoteFormatCodec.encode(exported, it) }
            checkCanceled()
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(partial)
        }
        return exported.size
    }

    /**
     * 노트와 태그를 한 읽기 트랜잭션에서 읽는다. 따로 읽으면 그 사이 지워진 노트의 id를 새 노트가 다시 받아
     * 다른 노트의 태그가 붙을 수 있다(INTEGER PRIMARY KEY는 마지막 id를 재사용한다).
     */
    private fun readSnapshot(c: java.sql.Connection): Pair<List<Note>, Map<Long, List<String>>> {
        c.autoCommit = false
        try {
            val notes = c.prepareStatement("SELECT $COLUMNS FROM note ORDER BY updated_at DESC, id DESC").use { s ->
                s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toNote() else null }.toList() }
            }
            val tags = c.prepareStatement(
                "SELECT nt.note_id, t.name FROM note_tag nt JOIN tag t ON t.id = nt.tag_id ORDER BY nt.note_id, t.name",
            ).use { s ->
                s.executeQuery().use { rs ->
                    val result = HashMap<Long, MutableList<String>>()
                    while (rs.next()) result.getOrPut(rs.getLong(1)) { mutableListOf() } += rs.getString(2)
                    result
                }
            }
            c.commit()
            return notes to tags
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    internal companion object {
        fun toExported(note: Note, tags: List<String>): ExportedNote {
            val anchor = if (note.project != null && note.relPath != null && note.startLine != null && note.endLine != null) {
                FormatAnchor(note.project, note.relPath, note.startLine, note.endLine, note.className, note.snapshot)
            } else {
                null
            }
            val formatNote = FormatNote(
                uid = note.uid,
                title = note.title,
                body = note.body,
                tags = tags,
                anchor = anchor,
                aiSource = note.aiSource,
                sourceUrl = note.sourceUrl,
                prompt = note.prompt,
            )
            return ExportedNote(formatNote, note.status, note.createdAt, note.updatedAt)
        }
    }
}
