package io.github.driflux.jnote.ai

import io.github.driflux.jnote.db.COLUMNS
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.toNote
import io.github.driflux.jnote.model.Note
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ExportTargetIsDatabaseException : IllegalArgumentException("export target is the active database")

class NoteExporter(private val database: Database, private val activeFile: () -> Path? = { null }) {
    fun export(target: Path, selectedUids: Set<String>?, checkCanceled: () -> Unit): Int {
        checkCanceled()
        val snapshot = awaitJob(database.submit { c -> readSnapshot(c) }, checkCanceled)
        val all = snapshot.notes
        val tagsByNote = snapshot.tags
        checkCanceled()
        val chosen = if (selectedUids == null) all else all.filter { it.uid in selectedUids }
        val exported = chosen.map { toExported(it, tagsByNote[it.id].orEmpty()) }
        val partial = Files.createTempFile(target.toAbsolutePath().parent, "jnote-export-", ".tmp")
        try {
            Files.newBufferedWriter(partial, StandardCharsets.UTF_8).use { NoteFormatCodec.encode(exported, it) }
            checkCanceled()
            awaitJob(database.submit { c -> moveUnlessActiveDatabase(c, partial, target) }, checkCanceled)
        } finally {
            Files.deleteIfExists(partial)
        }
        return exported.size
    }

    private fun moveUnlessActiveDatabase(c: java.sql.Connection, partial: Path, target: Path) {
        val active = activeFile() ?: connectionFile(c)
        if (sameFile(active, target)) throw ExportTargetIsDatabaseException()
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun <T> awaitJob(future: CompletableFuture<T>, checkCanceled: () -> Unit): T {
        while (true) {
            try {
                return future.get(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            } catch (_: TimeoutException) {
                try {
                    checkCanceled()
                } catch (e: Throwable) {
                    future.cancel(true)
                    throw e
                }
            }
        }
    }

    private fun readSnapshot(c: java.sql.Connection): Snapshot {
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
            return Snapshot(notes, tags)
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    private class Snapshot(val notes: List<Note>, val tags: Map<Long, List<String>>)

    private fun connectionFile(c: java.sql.Connection): Path? =
        c.prepareStatement("PRAGMA database_list").use { s ->
            s.executeQuery().use { rs ->
                var file: String? = null
                while (rs.next()) if (rs.getString("name") == "main") file = rs.getString("file")
                file?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
            }
        }

    private fun sameFile(active: Path?, target: Path): Boolean {
        if (active == null || !Files.exists(target)) return false
        return Files.isSameFile(active, target)
    }

    internal companion object {
        private const val CANCEL_POLL_MILLIS = 100L

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
