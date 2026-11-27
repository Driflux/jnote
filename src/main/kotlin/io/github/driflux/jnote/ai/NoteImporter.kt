package io.github.driflux.jnote.ai

import io.github.driflux.jnote.db.COLUMNS
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.toNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CompletableFuture

sealed interface PlannedItem {
    val note: FormatNote

    data class Create(override val note: FormatNote) : PlannedItem

    data class Update(override val note: FormatNote, val existing: Note, val emptiedBody: Boolean, val emptiedTags: Boolean) : PlannedItem

    data class Unchanged(override val note: FormatNote, val existing: Note) : PlannedItem
}

data class ImportPlan(val items: List<PlannedItem>, val skippedDuplicates: List<FormatNote> = emptyList()) {
    val creates: List<PlannedItem.Create> get() = items.filterIsInstance<PlannedItem.Create>()
    val updates: List<PlannedItem.Update> get() = items.filterIsInstance<PlannedItem.Update>()
    val unchanged: List<PlannedItem.Unchanged> get() = items.filterIsInstance<PlannedItem.Unchanged>()
}

data class ImportResult(val created: Int, val updated: Int, val unchanged: Int)

class PlanOutdatedException : IllegalStateException("notes changed since the preview; review the import again")

class NoteImporter(
    private val database: Database,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun plan(notes: List<FormatNote>): CompletableFuture<ImportPlan> = database.submit { c ->
        c.autoCommit = false
        try {
            val repeated = notes.mapNotNull { it.uid }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            val (skipped, kept) = notes.partition { it.uid in repeated }
            ImportPlan(kept.map { note -> classify(c, note) }, skipped).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    fun apply(plan: ImportPlan, checkCanceled: () -> Unit = {}): CompletableFuture<ImportResult> = database.submit { c ->
        c.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            var created = 0
            var updated = 0
            var unchanged = 0
            val now = clock()
            for (planned in plan.items) {
                checkCanceled()
                val note = planned.note
                when (val item = classify(c, note)) {
                    is PlannedItem.Create -> {
                        insert(c, note, now)
                        created++
                    }
                    is PlannedItem.Update -> {
                        if (item.emptiesMoreThan(planned)) throw PlanOutdatedException()
                        update(c, note, item.existing, now)
                        updated++
                    }
                    is PlannedItem.Unchanged -> unchanged++
                }
            }
            checkCanceled()
            c.createStatement().use { it.execute("COMMIT") }
            ImportResult(created, updated, unchanged)
        } catch (e: Throwable) {
            runCatching { c.createStatement().use { it.execute("ROLLBACK") } }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
    }

    private fun PlannedItem.Update.emptiesMoreThan(planned: PlannedItem): Boolean {
        val seen = planned as? PlannedItem.Update
        return (emptiedBody && seen?.emptiedBody != true) || (emptiedTags && seen?.emptiedTags != true)
    }

    private fun classify(c: Connection, note: FormatNote): PlannedItem {
        val existing = note.uid?.let { findByUid(c, it) } ?: return PlannedItem.Create(note)
        val existingTags = tagNames(c, existing.id)
        if (sameContent(existing, existingTags, note)) return PlannedItem.Unchanged(note, existing)
        return PlannedItem.Update(
            note = note,
            existing = existing,
            emptiedBody = existing.body.isNotEmpty() && note.body.isEmpty(),
            emptiedTags = existingTags.isNotEmpty() && note.tags.isEmpty(),
        )
    }

    private fun sameContent(existing: Note, existingTags: Set<String>, note: FormatNote): Boolean {
        val anchor = note.anchor
        return existing.kind == note.kind &&
            existing.title == note.title &&
            existing.body == note.body &&
            existingTags == note.tags.toSet() &&
            existing.aiSource == note.aiSource &&
            existing.sourceUrl == note.sourceUrl &&
            existing.prompt == note.prompt &&
            existing.project == anchor?.project &&
            existing.relPath == anchor?.relPath &&
            existing.startLine == anchor?.startLine &&
            existing.endLine == anchor?.endLine &&
            existing.className == anchor?.className &&
            existing.snapshot == anchor?.snapshot
    }

    private fun insert(c: Connection, note: FormatNote, now: Long) {
        val anchor = note.anchor
        val id = c.prepareStatement(
            "INSERT INTO note (uid, kind, project, rel_path, class_name, start_line, end_line, snapshot, status, title, body, " +
                "ai_source, source_url, prompt, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ).use { s ->
            var i = 1
            s.setString(i++, note.uid ?: UUID.randomUUID().toString())
            s.setString(i++, note.kind.name)
            s.setString(i++, anchor?.project)
            s.setString(i++, anchor?.relPath)
            s.setString(i++, anchor?.className)
            s.setObject(i++, anchor?.startLine)
            s.setObject(i++, anchor?.endLine)
            s.setString(i++, anchor?.snapshot)
            s.setString(i++, NoteStatus.OK.name)
            s.setString(i++, note.title)
            s.setString(i++, note.body)
            s.setString(i++, note.aiSource)
            s.setString(i++, note.sourceUrl)
            s.setString(i++, note.prompt)
            s.setLong(i++, now)
            s.setLong(i, now)
            s.executeUpdate()
            c.prepareStatement("SELECT last_insert_rowid()").use { q -> q.executeQuery().use { it.next(); it.getLong(1) } }
        }
        replaceTags(c, id, note.tags)
    }

    private fun update(c: Connection, note: FormatNote, existing: Note, now: Long) {
        val anchor = note.anchor
        val sameLocation = existing.project == anchor?.project && existing.relPath == anchor?.relPath &&
            existing.startLine == anchor?.startLine && existing.endLine == anchor?.endLine
        val status = if (note.kind == NoteKind.CODE && sameLocation) existing.status else NoteStatus.OK
        c.prepareStatement(
            "UPDATE note SET kind=?, project=?, rel_path=?, class_name=?, start_line=?, end_line=?, snapshot=?, status=?, " +
                "title=?, body=?, ai_source=?, source_url=?, prompt=?, updated_at=? WHERE id=?",
        ).use { s ->
            var i = 1
            s.setString(i++, note.kind.name)
            s.setString(i++, anchor?.project)
            s.setString(i++, anchor?.relPath)
            s.setString(i++, anchor?.className)
            s.setObject(i++, anchor?.startLine)
            s.setObject(i++, anchor?.endLine)
            s.setString(i++, anchor?.snapshot)
            s.setString(i++, status.name)
            s.setString(i++, note.title)
            s.setString(i++, note.body)
            s.setString(i++, note.aiSource)
            s.setString(i++, note.sourceUrl)
            s.setString(i++, note.prompt)
            s.setLong(i++, now)
            s.setLong(i, existing.id)
            s.executeUpdate()
        }
        replaceTags(c, existing.id, note.tags)
    }

    private fun replaceTags(c: Connection, noteId: Long, names: List<String>) {
        c.prepareStatement("DELETE FROM note_tag WHERE note_id = ?").use { s ->
            s.setLong(1, noteId)
            s.executeUpdate()
        }
        for (name in names) {
            c.prepareStatement("INSERT OR IGNORE INTO tag (name) VALUES (?)").use { s ->
                s.setString(1, name)
                s.executeUpdate()
            }
            c.prepareStatement("INSERT OR IGNORE INTO note_tag (note_id, tag_id) SELECT ?, id FROM tag WHERE name = ?").use { s ->
                s.setLong(1, noteId)
                s.setString(2, name)
                s.executeUpdate()
            }
        }
    }

    private fun findByUid(c: Connection, uid: String): Note? =
        c.prepareStatement("SELECT $COLUMNS FROM note WHERE uid = ?").use { s ->
            s.setString(1, uid)
            s.executeQuery().use { if (it.next()) it.toNote() else null }
        }

    private fun tagNames(c: Connection, noteId: Long): Set<String> =
        c.prepareStatement("SELECT t.name FROM note_tag nt JOIN tag t ON t.id = nt.tag_id WHERE nt.note_id = ?").use { s ->
            s.setLong(1, noteId)
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toSet() }
        }
}
