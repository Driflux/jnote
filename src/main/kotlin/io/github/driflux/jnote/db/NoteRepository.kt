package io.github.driflux.jnote.db

import io.github.driflux.jnote.model.AnchorUpdate
import io.github.driflux.jnote.model.LocationConfirmation
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.model.NoteSummary
import io.github.driflux.jnote.model.NoteText
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class SavedNote(val note: Note, val locationMoved: Boolean = false)

class NoteRepository(
    private val database: Database,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun create(draft: NewNote, tagNames: List<String> = emptyList()): CompletableFuture<Note> = database.submit { c ->
        val names = cleanTagNames(tagNames)
        val now = clock()
        val uid = UUID.randomUUID().toString()
        c.autoCommit = false
        try {
            val id = insert(c, draft, uid, now)
            if (names.isNotEmpty()) setNoteTags(c, id, names)
            val note = requireNotNull(findById(c, id))
            c.commit()
            note
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    private fun insert(c: Connection, draft: NewNote, uid: String, now: Long): Long =
        c.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS).use { s ->
            var i = 1
            s.setString(i++, uid)
            s.setString(i++, draft.kind.name)
            s.setString(i++, draft.project)
            s.setString(i++, draft.relPath)
            s.setString(i++, draft.className)
            s.setObject(i++, draft.startLine)
            s.setObject(i++, draft.endLine)
            s.setString(i++, draft.snapshot)
            s.setString(i++, draft.status.name)
            s.setString(i++, draft.title)
            s.setString(i++, draft.body)
            s.setString(i++, draft.aiSource)
            s.setString(i++, draft.sourceUrl)
            s.setString(i++, draft.prompt)
            s.setLong(i++, now)
            s.setLong(i, now)
            s.executeUpdate()
            s.generatedKeys.use { it.next(); it.getLong(1) }
        }

    fun get(id: Long): CompletableFuture<Note?> = database.submit { c -> findById(c, id) }

    fun getByUid(uid: String): CompletableFuture<Note?> = database.submit { c -> findByUid(c, uid) }

    private fun findByUid(c: Connection, uid: String): Note? =
        c.prepareStatement("SELECT $COLUMNS FROM note WHERE uid = ?").use { s ->
            s.setString(1, uid)
            s.executeQuery().use { if (it.next()) it.toNote() else null }
        }

    fun update(note: Note): CompletableFuture<Note?> = database.submit { c ->
        val now = clock()
        val changed = c.prepareStatement(UPDATE).use { s ->
            var i = 1
            s.setString(i++, note.kind.name)
            s.setString(i++, note.project)
            s.setString(i++, note.relPath)
            s.setString(i++, note.className)
            s.setObject(i++, note.startLine)
            s.setObject(i++, note.endLine)
            s.setString(i++, note.snapshot)
            s.setString(i++, note.status.name)
            s.setString(i++, note.title)
            s.setString(i++, note.body)
            s.setString(i++, note.aiSource)
            s.setString(i++, note.sourceUrl)
            s.setString(i++, note.prompt)
            s.setLong(i++, now)
            s.setLong(i, note.id)
            s.executeUpdate()
        }
        if (changed == 0) null else findById(c, note.id)
    }

    fun updateText(uid: String, text: NoteText, tagNames: List<String>, confirm: LocationConfirmation? = null): CompletableFuture<SavedNote?> =
        database.submit { c ->
            val names = cleanTagNames(tagNames)
            transaction(c) { save(c, uid, text, names, confirm) }
        }

    fun confirmLocation(uid: String, confirm: LocationConfirmation): CompletableFuture<SavedNote?> =
        database.submit { c -> transaction(c) { save(c, uid, null, null, confirm) } }

    private fun save(c: Connection, uid: String, text: NoteText?, tagNames: List<String>?, confirm: LocationConfirmation?): SavedNote? {
        val now = clock()
        if (text != null) {
            val changed = c.prepareStatement(UPDATE_TEXT).use { s ->
                s.setString(1, text.title)
                s.setString(2, text.body)
                s.setString(3, text.aiSource)
                s.setString(4, text.sourceUrl)
                s.setString(5, text.prompt)
                s.setLong(6, now)
                s.setString(7, uid)
                s.executeUpdate()
            }
            if (changed == 0) return null
        }
        if (tagNames != null) setNoteTags(c, checkNotNull(findByUid(c, uid)).id, tagNames)
        val confirmed = confirm == null || c.prepareStatement(CONFIRM_LOCATION).use { s ->
            s.setString(1, confirm.snapshot)
            s.setLong(2, now)
            s.setString(3, uid)
            s.setString(4, confirm.relPath)
            s.setInt(5, confirm.startLine)
            s.setInt(6, confirm.endLine)
            s.executeUpdate()
        } > 0
        val note = findByUid(c, uid) ?: return null
        return SavedNote(note, locationMoved = !confirmed)
    }

    private fun <T> transaction(c: Connection, block: () -> T): T {
        c.autoCommit = false
        try {
            val result = block()
            c.commit()
            return result
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    fun updateAnchors(updates: List<AnchorUpdate>): CompletableFuture<Int> = database.submit { c ->
        c.autoCommit = false
        try {
            val changed = c.prepareStatement(UPDATE_ANCHOR).use { s ->
                updates.sumOf { update ->
                    s.setString(1, update.relPath)
                    s.setInt(2, update.startLine)
                    s.setInt(3, update.endLine)
                    s.setString(4, update.status.name)
                    s.setString(5, update.uid)
                    s.executeUpdate()
                }
            }
            c.commit()
            changed
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    fun restore(note: Note, tagIds: List<Long>): CompletableFuture<Note> = database.submit { c ->
        c.autoCommit = false
        try {
            c.prepareStatement(RESTORE).use { s ->
                var i = 1
                s.setLong(i++, note.id)
                s.setString(i++, note.uid)
                s.setString(i++, note.kind.name)
                s.setString(i++, note.project)
                s.setString(i++, note.relPath)
                s.setString(i++, note.className)
                s.setObject(i++, note.startLine)
                s.setObject(i++, note.endLine)
                s.setString(i++, note.snapshot)
                s.setString(i++, note.status.name)
                s.setString(i++, note.title)
                s.setString(i++, note.body)
                s.setString(i++, note.aiSource)
                s.setString(i++, note.sourceUrl)
                s.setString(i++, note.prompt)
                s.setLong(i++, note.createdAt)
                s.setLong(i, note.updatedAt)
                s.executeUpdate()
            }
            c.prepareStatement("INSERT OR IGNORE INTO note_tag (note_id, tag_id) SELECT ?, id FROM tag WHERE id = ?").use { s ->
                for (tagId in tagIds) {
                    s.setLong(1, note.id)
                    s.setLong(2, tagId)
                    s.executeUpdate()
                }
            }
            c.commit()
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
        requireNotNull(findById(c, note.id))
    }

    fun delete(id: Long): CompletableFuture<Boolean> = database.submit { c ->
        c.prepareStatement("DELETE FROM note WHERE id = ?").use { s ->
            s.setLong(1, id)
            s.executeUpdate() > 0
        }
    }

    internal fun deleteReturningTagIds(id: Long): CompletableFuture<Pair<Note, List<Long>>?> = database.submit { c ->
        c.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            val note = findById(c, id)
            val result = if (note == null) {
                null
            } else {
                val tagIds = c.prepareStatement("SELECT tag_id FROM note_tag WHERE note_id = ? ORDER BY tag_id").use { s ->
                    s.setLong(1, id)
                    s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong(1) else null }.toList() }
                }
                c.prepareStatement("DELETE FROM note WHERE id = ?").use { s ->
                    s.setLong(1, id)
                    s.executeUpdate()
                }
                note to tagIds
            }
            c.createStatement().use { it.execute("COMMIT") }
            result
        } catch (e: Throwable) {
            c.createStatement().use { it.execute("ROLLBACK") }
            throw e
        }
    }

    fun list(project: String?, allProjects: Boolean): CompletableFuture<List<Note>> = database.submit { c ->
        val where = if (allProjects) "" else "WHERE (kind = 'FREE' AND project IS NULL) OR project = ?"
        c.prepareStatement("SELECT $COLUMNS FROM note $where ORDER BY updated_at DESC, id DESC").use { s ->
            if (!allProjects) s.setString(1, project)
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toNote() else null }.toList() }
        }
    }

    fun listSummaries(project: String?, allProjects: Boolean): CompletableFuture<List<NoteSummary>> = database.submit { c ->
        val where = if (allProjects) "" else "WHERE (kind = 'FREE' AND project IS NULL) OR project = ?"
        c.prepareStatement("SELECT $SUMMARY_COLUMNS FROM note $where ORDER BY updated_at DESC, id DESC").use { s ->
            if (!allProjects) s.setString(1, project)
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toSummary() else null }.toList() }
        }
    }

    fun listByPath(project: String, relPath: String): CompletableFuture<List<Note>> = database.submit { c ->
        c.prepareStatement("SELECT $COLUMNS FROM note WHERE project = ? AND rel_path = ? ORDER BY start_line, id").use { s ->
            s.setString(1, project)
            s.setString(2, relPath)
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toNote() else null }.toList() }
        }
    }

    private companion object {
        const val INSERT = "INSERT INTO note (uid, kind, project, rel_path, class_name, start_line, end_line, snapshot, " +
            "status, title, body, ai_source, source_url, prompt, created_at, updated_at) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        const val RESTORE = "INSERT INTO note (id, uid, kind, project, rel_path, class_name, start_line, end_line, snapshot, " +
            "status, title, body, ai_source, source_url, prompt, created_at, updated_at) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        const val UPDATE = "UPDATE note SET kind=?, project=?, rel_path=?, class_name=?, start_line=?, end_line=?, " +
            "snapshot=?, status=?, title=?, body=?, ai_source=?, source_url=?, prompt=?, updated_at=? WHERE id=?"

        const val UPDATE_TEXT = "UPDATE note SET title=?, body=?, ai_source=?, source_url=?, prompt=?, updated_at=? WHERE uid=?"

        const val CONFIRM_LOCATION = "UPDATE note SET status='OK', snapshot=?, updated_at=? " +
            "WHERE uid=? AND rel_path=? AND start_line=? AND end_line=?"

        const val UPDATE_ANCHOR = "UPDATE note SET rel_path=?1, start_line=?2, end_line=?3, status=?4 " +
            "WHERE uid=?5 AND (rel_path, start_line, end_line, status) IS NOT (?1, ?2, ?3, ?4)"
    }
}

internal const val COLUMNS = "id, uid, kind, project, rel_path, class_name, start_line, end_line, snapshot, status, " +
    "title, body, ai_source, source_url, prompt, created_at, updated_at"

internal const val SUMMARY_COLUMNS = "id, uid, kind, project, rel_path, class_name, start_line, end_line, status, " +
    "title, ai_source, updated_at"

internal fun findById(c: Connection, id: Long): Note? =
    c.prepareStatement("SELECT $COLUMNS FROM note WHERE id = ?").use { s ->
        s.setLong(1, id)
        s.executeQuery().use { if (it.next()) it.toNote() else null }
    }

internal fun ResultSet.toSummary(): NoteSummary = NoteSummary(
    id = getLong(1),
    uid = getString(2),
    kind = NoteKind.valueOf(getString(3)),
    project = getString(4),
    relPath = getString(5),
    className = getString(6),
    startLine = getInt(7).takeUnless { wasNull() },
    endLine = getInt(8).takeUnless { wasNull() },
    status = NoteStatus.valueOf(getString(9)),
    title = getString(10),
    aiSource = getString(11),
    updatedAt = getLong(12),
)

internal fun ResultSet.toNote(): Note = Note(
    id = getLong(1),
    uid = getString(2),
    kind = NoteKind.valueOf(getString(3)),
    project = getString(4),
    relPath = getString(5),
    className = getString(6),
    startLine = getInt(7).takeUnless { wasNull() },
    endLine = getInt(8).takeUnless { wasNull() },
    snapshot = getString(9),
    status = NoteStatus.valueOf(getString(10)),
    title = getString(11),
    body = getString(12),
    aiSource = getString(13),
    sourceUrl = getString(14),
    prompt = getString(15),
    createdAt = getLong(16),
    updatedAt = getLong(17),
)
