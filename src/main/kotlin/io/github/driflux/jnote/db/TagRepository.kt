package io.github.driflux.jnote.db

import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.Tag
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.CompletableFuture

sealed interface TagRenameResult {
    data class Renamed(val tag: Tag) : TagRenameResult
    data object Conflict : TagRenameResult
    data object NotFound : TagRenameResult
    data object InvalidName : TagRenameResult
}

class TagRepository(private val database: Database) {
    fun getOrCreate(name: String): CompletableFuture<Tag> = database.submit { c ->
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "tag name must not be blank" }
        require(',' !in trimmed) { "tag name must not contain a comma" }
        c.prepareStatement("INSERT OR IGNORE INTO tag (name) VALUES (?)").use { s ->
            s.setString(1, trimmed)
            s.executeUpdate()
        }
        checkNotNull(findByName(c, trimmed))
    }

    fun list(): CompletableFuture<List<Tag>> = database.submit { c ->
        c.prepareStatement("SELECT id, name FROM tag ORDER BY name").use { s ->
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toTag() else null }.toList() }
        }
    }

    fun rename(id: Long, newName: String): CompletableFuture<TagRenameResult> = database.submit { c ->
        val trimmed = newName.trim()
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

    fun replaceTags(noteId: Long, names: List<String>): CompletableFuture<List<Tag>> = database.submit { c ->
        val cleaned = cleanTagNames(names)
        c.autoCommit = false
        try {
            val tags = setNoteTags(c, noteId, cleaned)
            c.commit()
            tags
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    fun attach(noteId: Long, tagId: Long): CompletableFuture<Unit> = database.submit { c ->
        c.prepareStatement("INSERT OR IGNORE INTO note_tag (note_id, tag_id) VALUES (?, ?)").use { s ->
            s.setLong(1, noteId)
            s.setLong(2, tagId)
            s.executeUpdate()
        }
        Unit
    }

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
}

/** 다듬고 빈 이름과 중복을 뺀 태그 이름. 쉼표가 든 이름이 있으면 IllegalArgumentException. */
internal fun cleanTagNames(names: List<String>): List<String> {
    val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    require(cleaned.none { ',' in it }) { "tag name must not contain a comma" }
    return cleaned
}

/**
 * 노트에 붙은 태그를 [cleaned]와 정확히 같게 맞추고 이름순으로 돌려준다. 사전에 없는 이름은 만든다.
 * 트랜잭션은 부르는 쪽이 연다.
 */
internal fun setNoteTags(c: java.sql.Connection, noteId: Long, cleaned: List<String>): List<Tag> {
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
    return tags.sortedBy { it.name }
}

private fun findByName(c: java.sql.Connection, name: String): Tag? =
    c.prepareStatement("SELECT id, name FROM tag WHERE name = ?").use { s ->
        s.setString(1, name)
        s.executeQuery().use { if (it.next()) it.toTag() else null }
    }

private fun ResultSet.toTag() = Tag(getLong(1), getString(2))
