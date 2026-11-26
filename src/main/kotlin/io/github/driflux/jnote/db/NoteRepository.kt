package io.github.driflux.jnote.db

import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * 노트 CRUD. 모든 접근은 [Database.submit]을 거치므로 EDT에서 호출해도 블로킹하지 않는다.
 * 제약 위반 등 SQL 오류는 반환된 future가 예외로 완료되는 것으로 드러난다.
 */
class NoteRepository(
    private val database: Database,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** uid를 UUID로 생성하고 createdAt, updatedAt을 같은 시각으로 채워 저장한다. */
    fun create(draft: NewNote): CompletableFuture<Note> = database.submit { c ->
        val now = clock()
        val uid = UUID.randomUUID().toString()
        val id = c.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS).use { s ->
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
        requireNotNull(findById(c, id))
    }

    fun get(id: Long): CompletableFuture<Note?> = database.submit { c -> findById(c, id) }

    fun getByUid(uid: String): CompletableFuture<Note?> = database.submit { c ->
        c.prepareStatement("SELECT $COLUMNS FROM note WHERE uid = ?").use { s ->
            s.setString(1, uid)
            s.executeQuery().use { if (it.next()) it.toNote() else null }
        }
    }

    /**
     * id, uid, createdAt을 제외한 모든 필드를 덮어쓰고 updatedAt을 갱신한다. 태그는 건드리지 않는다.
     * 해당 id가 없으면 null.
     */
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

    /** 노트와 (cascade로) 태그 연결을 지운다. 지운 행이 있으면 true. */
    fun delete(id: Long): CompletableFuture<Boolean> = database.submit { c ->
        c.prepareStatement("DELETE FROM note WHERE id = ?").use { s ->
            s.setLong(1, id)
            s.executeUpdate() > 0
        }
    }

    /**
     * 최근 수정 순(같으면 id가 큰 순). allProjects면 전부, 아니면 해당 프로젝트의 노트와
     * 모든 프로젝트에서 보이는 전역 FREE 노트. project가 null이면 전역 FREE 노트만 돌려준다.
     */
    fun list(project: String?, allProjects: Boolean): CompletableFuture<List<Note>> = database.submit { c ->
        val where = if (allProjects) "" else "WHERE (kind = 'FREE' AND project IS NULL) OR project = ?"
        c.prepareStatement("SELECT $COLUMNS FROM note $where ORDER BY updated_at DESC, id DESC").use { s ->
            if (!allProjects) s.setString(1, project)
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toNote() else null }.toList() }
        }
    }

    private companion object {
        const val INSERT = "INSERT INTO note (uid, kind, project, rel_path, class_name, start_line, end_line, snapshot, " +
            "status, title, body, ai_source, source_url, prompt, created_at, updated_at) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        const val UPDATE = "UPDATE note SET kind=?, project=?, rel_path=?, class_name=?, start_line=?, end_line=?, " +
            "snapshot=?, status=?, title=?, body=?, ai_source=?, source_url=?, prompt=?, updated_at=? WHERE id=?"
    }
}

internal const val COLUMNS = "id, uid, kind, project, rel_path, class_name, start_line, end_line, snapshot, status, " +
    "title, body, ai_source, source_url, prompt, created_at, updated_at"

internal fun findById(c: Connection, id: Long): Note? =
    c.prepareStatement("SELECT $COLUMNS FROM note WHERE id = ?").use { s ->
        s.setLong(1, id)
        s.executeQuery().use { if (it.next()) it.toNote() else null }
    }

/** [COLUMNS] 순서의 현재 행을 노트로 읽는다. */
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
