package io.github.driflux.jnote.ai

import io.github.driflux.jnote.db.COLUMNS
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.toNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import org.sqlite.JDBC
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** 미리보기에 보여 줄 한 항목. 실제 반영은 [NoteImporter.apply]가 그 시점의 DB를 기준으로 다시 판단한다. */
sealed interface PlannedItem {
    val note: FormatNote

    data class Create(override val note: FormatNote) : PlannedItem

    /** [emptiedBody], [emptiedTags]는 값이 있던 본문이나 태그가 이 항목 때문에 비워진다는 뜻이다. */
    data class Update(override val note: FormatNote, val existing: Note, val emptiedBody: Boolean, val emptiedTags: Boolean) : PlannedItem

    /** uid가 같은 노트와 모든 필드가 같아 쓰지 않는다. */
    data class Unchanged(override val note: FormatNote, val existing: Note) : PlannedItem
}

data class ImportPlan(val items: List<PlannedItem>) {
    val creates: List<PlannedItem.Create> get() = items.filterIsInstance<PlannedItem.Create>()
    val updates: List<PlannedItem.Update> get() = items.filterIsInstance<PlannedItem.Update>()
    val unchanged: List<PlannedItem.Unchanged> get() = items.filterIsInstance<PlannedItem.Unchanged>()
}

data class ImportResult(val created: Int, val updated: Int, val unchanged: Int)

/** 기본 DB jnote.db는 jnote.pre-import.bak. 다른 이름은 .db만 떼어 서로 다른 DB가 같은 백업을 덮어쓰지 않게 한다. */
internal fun backupName(dbFileName: String): String = dbFileName.removeSuffix(".db") + ".pre-import.bak"

/**
 * 미리보기 뒤에 다른 IDE가 노트에 본문이나 태그를 더해, 사람이 보지 못한 값이 이번 가져오기로 지워지게 된 경우.
 * 아무것도 쓰지 않았으므로 다시 계획해 미리보기를 보여 주면 된다.
 */
class PlanOutdatedException : IllegalStateException("notes changed since the preview; review the import again")

/**
 * uid 기준 업서트. 항목은 노트의 새 상태 전체라서 빠진 필드는 비워지고, 가져오기는 노트를 지우지 않는다.
 * 모든 접근은 DB 스레드에서 하므로 EDT에서 불러도 막히지 않는다.
 */
class NoteImporter(
    private val database: Database,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 노트와 태그를 한 읽기 트랜잭션에서 읽어, 그 사이 지워진 노트의 id를 새 노트가 받아도 섞이지 않는다. */
    fun plan(notes: List<FormatNote>): CompletableFuture<ImportPlan> = database.submit { c ->
        c.autoCommit = false
        try {
            ImportPlan(notes.map { note -> classify(c, note) }).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    /**
     * 한 쓰기 트랜잭션(BEGIN IMMEDIATE)에서 항목마다 그 순간의 uid로 다시 판단해 쓴다. 미리보기 뒤에 다른 IDE가 노트를
     * 지우거나 만들었어도 업서트 규칙대로 반영된다. 다만 [plan]이 보여 주지 않은 본문·태그 비움이 새로 생기면
     * [PlanOutdatedException]으로 끝난다. [checkCanceled]가 던지면 아무것도 쓰지 않은 채 되돌린다.
     */
    fun apply(plan: ImportPlan, checkCanceled: () -> Unit = {}): CompletableFuture<ImportResult> = database.submit { c ->
        checkCanceled()
        c.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            // 쓰기 잠금을 잡은 뒤에 백업해, 백업이 정확히 이 가져오기 직전 상태가 되게 한다. 실패하면 가져오지 않는다
            backup(c, checkCanceled)
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
            // 마지막 쓰기 중에 누른 취소도 커밋 전에 받아 준다
            checkCanceled()
            c.createStatement().use { it.execute("COMMIT") }
            ImportResult(created, updated, unchanged)
        } catch (e: Throwable) {
            runCatching { c.createStatement().use { it.execute("ROLLBACK") } }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
    }

    /**
     * DB 파일 옆에 `<파일 이름에서 .db를 뗀 것>.pre-import.bak`(기본 DB면 jnote.pre-import.bak)을 만들고 이전 것을 덮어쓴다.
     * 이 연결이 쓰기 잠금을 쥔 채 트랜잭션 안이라 VACUUM INTO를 직접 못 하므로, 읽기 전용 연결을 하나 더 열어 복사한다.
     * 잠금 때문에 그 사이 아무도 커밋할 수 없어 사본은 이 가져오기 직전 상태와 같다.
     * 임시 파일은 소유자만 읽을 수 있게 만든 빈 파일을 그대로 쓰고, 다 쓴 뒤 한 번에 바꿔 끼워 실패해도 이전 백업이 남는다.
     */
    private fun backup(c: Connection, checkCanceled: () -> Unit) {
        val file = c.createStatement().use { s ->
            s.executeQuery("PRAGMA database_list").use { rs ->
                var found: String? = null
                while (rs.next()) if (rs.getString("name") == "main") found = rs.getString("file")
                found
            }
        }
        if (file.isNullOrEmpty()) return
        val source = Paths.get(file)
        val backup = source.resolveSibling(backupName(source.fileName.toString()))
        // 다른 IDE가 동시에 백업해도 서로의 임시 파일을 건드리지 않도록 이름을 따로 받는다
        val partial = Files.createTempFile(source.toAbsolutePath().parent, "jnote-pre-import-", ".tmp")
        try {
            val config = SQLiteConfig().apply {
                setReadOnly(true)
                setBusyTimeout(Database.BUSY_TIMEOUT_MS)
            }
            val reader = JDBC().connect("jdbc:sqlite:$source", config.toProperties()) ?: error("sqlite-jdbc rejected $source")
            reader.use { r ->
                r.prepareStatement("VACUUM INTO ?").use { s ->
                    s.setString(1, partial.toString())
                    s.execute()
                }
            }
            checkCanceled()
            Files.move(partial, backup, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(partial)
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
        // 위치가 그대로면 재배치가 정한 상태(APPROX 등)를 지키고, 위치가 바뀌었으면 새로 받은 위치를 믿는다
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
            // 사전에 없는 태그는 등록한다
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
