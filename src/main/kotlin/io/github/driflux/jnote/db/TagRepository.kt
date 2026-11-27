package io.github.driflux.jnote.db

import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.Tag
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.CompletableFuture

/** 태그 이름 변경 결과. 중복은 예외가 아니라 값으로 돌려준다. */
sealed interface TagRenameResult {
    data class Renamed(val tag: Tag) : TagRenameResult
    data object Conflict : TagRenameResult
    data object NotFound : TagRenameResult
    data object InvalidName : TagRenameResult
}

/**
 * 태그 사전과 노트-태그 연결. 태그는 노트가 쓰지 않아도 사전에 남고, 지우는 것은 [delete]뿐이다.
 * 이름은 앞뒤 공백을 자르며 대소문자를 구분해 유일하다.
 */
class TagRepository(private val database: Database) {
    /** 이미 있으면 그 태그를, 없으면 만들어 돌려준다. 빈 이름이나 쉼표가 든 이름이면 future가 IllegalArgumentException으로 끝난다. */
    fun getOrCreate(name: String): CompletableFuture<Tag> = database.submit { c ->
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "tag name must not be blank" }
        require(',' !in trimmed) { "tag name must not contain a comma" }
        // IDE 프로세스 둘이 같은 이름을 동시에 만들어도 UNIQUE로 실패하지 않도록 넣고 나서 읽는다
        c.prepareStatement("INSERT OR IGNORE INTO tag (name) VALUES (?)").use { s ->
            s.setString(1, trimmed)
            s.executeUpdate()
        }
        checkNotNull(findByName(c, trimmed))
    }

    /** 이름순(바이너리 순서) 전체 태그 */
    fun list(): CompletableFuture<List<Tag>> = database.submit { c ->
        c.prepareStatement("SELECT id, name FROM tag ORDER BY name").use { s ->
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toTag() else null }.toList() }
        }
    }

    fun rename(id: Long, newName: String): CompletableFuture<TagRenameResult> = database.submit { c ->
        val trimmed = newName.trim()
        // 쉼표는 v_notes_export의 tags 열(group_concat) 구분자와 겹친다
        if (trimmed.isEmpty() || ',' in trimmed) return@submit TagRenameResult.InvalidName
        if (!exists(c, id)) return@submit TagRenameResult.NotFound
        val clash = findByName(c, trimmed)
        when {
            clash != null && clash.id != id -> TagRenameResult.Conflict
            else -> try {
                val changed = c.prepareStatement("UPDATE tag SET name = ? WHERE id = ?").use { s ->
                    s.setString(1, trimmed)
                    s.setLong(2, id)
                    s.executeUpdate()
                }
                if (changed == 0) TagRenameResult.NotFound else TagRenameResult.Renamed(Tag(id, trimmed))
            } catch (e: SQLiteException) {
                // 확인과 UPDATE 사이에 다른 IDE 프로세스가 같은 이름을 넣으면 여기서 UNIQUE로 실패한다
                if (e.resultCode != SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE) throw e
                TagRenameResult.Conflict
            }
        }
    }

    fun delete(id: Long): CompletableFuture<Boolean> = database.submit { c ->
        c.prepareStatement("DELETE FROM tag WHERE id = ?").use { s ->
            s.setLong(1, id)
            s.executeUpdate() > 0
        }
    }

    /**
     * 노트에 붙은 태그를 [names]와 정확히 같게 맞춘다. 사전에 없는 이름은 만들고, 빠진 연결은 끊는다.
     * 한 트랜잭션이라 중간에 실패하면 사전도 연결도 그대로다. 이름은 다듬고 중복은 하나로 본다.
     * 쉼표가 든 이름이나 없는 노트면 future가 예외로 끝난다. 돌려주는 태그는 이름순이다.
     */
    fun replaceTags(noteId: Long, names: List<String>): CompletableFuture<List<Tag>> = database.submit { c ->
        val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(cleaned.none { ',' in it }) { "tag name must not contain a comma" }
        c.autoCommit = false
        try {
            val tags = cleaned.map { name ->
                c.prepareStatement("INSERT OR IGNORE INTO tag (name) VALUES (?)").use { s ->
                    s.setString(1, name)
                    s.executeUpdate()
                }
                checkNotNull(findByName(c, name))
            }
            c.prepareStatement("DELETE FROM note_tag WHERE note_id = ?").use { s ->
                s.setLong(1, noteId)
                s.executeUpdate()
            }
            c.prepareStatement("INSERT INTO note_tag (note_id, tag_id) VALUES (?, ?)").use { s ->
                for (tag in tags) {
                    s.setLong(1, noteId)
                    s.setLong(2, tag.id)
                    s.executeUpdate()
                }
            }
            c.commit()
            tags.sortedBy { it.name }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    /** 이미 붙어 있어도 성공한다. 없는 노트나 태그면 SQLException(외래 키)으로 끝난다. */
    fun attach(noteId: Long, tagId: Long): CompletableFuture<Unit> = database.submit { c ->
        c.prepareStatement("INSERT OR IGNORE INTO note_tag (note_id, tag_id) VALUES (?, ?)").use { s ->
            s.setLong(1, noteId)
            s.setLong(2, tagId)
            s.executeUpdate()
        }
        Unit
    }

    /** 연결만 끊고 태그는 사전에 남긴다. 끊은 연결이 있으면 true. */
    fun detach(noteId: Long, tagId: Long): CompletableFuture<Boolean> = database.submit { c ->
        c.prepareStatement("DELETE FROM note_tag WHERE note_id = ? AND tag_id = ?").use { s ->
            s.setLong(1, noteId)
            s.setLong(2, tagId)
            s.executeUpdate() > 0
        }
    }

    fun tagsOf(noteId: Long): CompletableFuture<List<Tag>> = database.submit { c ->
        c.prepareStatement(
            "SELECT t.id, t.name FROM tag t JOIN note_tag nt ON nt.tag_id = t.id WHERE nt.note_id = ? ORDER BY t.name",
        ).use { s ->
            s.setLong(1, noteId)
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toTag() else null }.toList() }
        }
    }

    /** 한 파일의 노트들에 붙은 태그를 노트 id별로 묶어 돌려준다. 태그가 없는 노트는 키가 없다. */
    fun tagsByPath(project: String, relPath: String): CompletableFuture<Map<Long, List<Tag>>> = database.submit { c ->
        c.prepareStatement(
            "SELECT nt.note_id, t.id, t.name FROM note_tag nt JOIN tag t ON t.id = nt.tag_id " +
                "WHERE nt.note_id IN (SELECT id FROM note WHERE project = ? AND rel_path = ?) ORDER BY nt.note_id, t.name",
        ).use { s ->
            s.setString(1, project)
            s.setString(2, relPath)
            s.executeQuery().use { rs ->
                val result = LinkedHashMap<Long, MutableList<Tag>>()
                while (rs.next()) result.getOrPut(rs.getLong(1)) { mutableListOf() } += Tag(rs.getLong(2), rs.getString(3))
                result
            }
        }
    }

    /** 모든 노트의 태그를 노트 id별로 묶어 돌려준다. 태그가 없는 노트는 키가 없다. 목록 화면이 한 번에 읽는다. */
    fun assignments(): CompletableFuture<Map<Long, List<Tag>>> = database.submit { c ->
        c.prepareStatement(
            "SELECT nt.note_id, t.id, t.name FROM note_tag nt JOIN tag t ON t.id = nt.tag_id ORDER BY nt.note_id, t.name",
        ).use { s ->
            s.executeQuery().use { rs ->
                val result = LinkedHashMap<Long, MutableList<Tag>>()
                while (rs.next()) result.getOrPut(rs.getLong(1)) { mutableListOf() } += Tag(rs.getLong(2), rs.getString(3))
                result
            }
        }
    }

    /** 태그가 붙은 노트. 정렬은 [NoteRepository.list]와 같다. */
    fun notesOf(tagId: Long): CompletableFuture<List<Note>> = database.submit { c ->
        c.prepareStatement(
            "SELECT $COLUMNS FROM note WHERE id IN (SELECT note_id FROM note_tag WHERE tag_id = ?) " +
                "ORDER BY updated_at DESC, id DESC",
        ).use { s ->
            s.setLong(1, tagId)
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toNote() else null }.toList() }
        }
    }

    private fun exists(c: java.sql.Connection, id: Long): Boolean =
        c.prepareStatement("SELECT 1 FROM tag WHERE id = ?").use { s ->
            s.setLong(1, id)
            s.executeQuery().use { it.next() }
        }

    private fun findByName(c: java.sql.Connection, name: String): Tag? =
        c.prepareStatement("SELECT id, name FROM tag WHERE name = ?").use { s ->
            s.setString(1, name)
            s.executeQuery().use { if (it.next()) it.toTag() else null }
        }

    private fun ResultSet.toTag() = Tag(getLong(1), getString(2))
}
