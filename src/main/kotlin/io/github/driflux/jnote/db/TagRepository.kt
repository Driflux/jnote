package io.github.driflux.jnote.db

import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.Tag
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
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
    /** 이미 있으면 그 태그를, 없으면 만들어 돌려준다. 빈 이름이면 future가 IllegalArgumentException으로 끝난다. */
    fun getOrCreate(name: String): CompletableFuture<Tag> = database.submit { c ->
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "tag name must not be blank" }
        findByName(c, trimmed) ?: c.prepareStatement("INSERT INTO tag (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS).use { s ->
            s.setString(1, trimmed)
            s.executeUpdate()
            Tag(s.generatedKeys.use { it.next(); it.getLong(1) }, trimmed)
        }
    }

    /** 이름순(바이너리 순서) 전체 태그 */
    fun list(): CompletableFuture<List<Tag>> = database.submit { c ->
        c.prepareStatement("SELECT id, name FROM tag ORDER BY name").use { s ->
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toTag() else null }.toList() }
        }
    }

    fun rename(id: Long, newName: String): CompletableFuture<TagRenameResult> = database.submit { c ->
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return@submit TagRenameResult.InvalidName
        val clash = findByName(c, trimmed)
        when {
            clash != null && clash.id != id -> TagRenameResult.Conflict
            else -> {
                val changed = c.prepareStatement("UPDATE tag SET name = ? WHERE id = ?").use { s ->
                    s.setString(1, trimmed)
                    s.setLong(2, id)
                    s.executeUpdate()
                }
                if (changed == 0) TagRenameResult.NotFound else TagRenameResult.Renamed(Tag(id, trimmed))
            }
        }
    }

    /** 태그와 그 연결을 지운다(노트는 그대로). 지운 태그가 있으면 true. */
    fun delete(id: Long): CompletableFuture<Boolean> = database.submit { c ->
        c.prepareStatement("DELETE FROM tag WHERE id = ?").use { s ->
            s.setLong(1, id)
            s.executeUpdate() > 0
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

    private fun findByName(c: java.sql.Connection, name: String): Tag? =
        c.prepareStatement("SELECT id, name FROM tag WHERE name = ?").use { s ->
            s.setString(1, name)
            s.executeQuery().use { if (it.next()) it.toTag() else null }
        }

    private fun ResultSet.toTag() = Tag(getLong(1), getString(2))
}
