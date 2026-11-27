package io.github.driflux.jnote.ai

import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.ui.NoteRows
import java.io.StringWriter
import java.sql.ResultSet
import java.util.concurrent.CompletableFuture

object NoteCopy {
    fun read(database: Database, uids: List<String>): CompletableFuture<List<ExportedNote>> {
        if (uids.isEmpty()) return CompletableFuture.completedFuture(emptyList())
        return database.submit { c ->
            val marks = uids.joinToString(",") { "?" }
            val byUid = c.prepareStatement("SELECT $VIEW_COLUMNS FROM v_notes_export WHERE uid IN ($marks)").use { s ->
                uids.forEachIndexed { i, uid -> s.setString(i + 1, uid) }
                s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toExported() else null }.associateBy { it.note.uid } }
            }
            uids.distinct().mapNotNull { byUid[it] }
        }
    }

    fun json(notes: List<ExportedNote>): String = StringWriter().also { NoteFormatCodec.encode(notes, it) }.toString()

    fun markdown(notes: List<FormatNote>): String = notes.joinToString("\n\n") { note ->
        buildList {
            add("## " + note.title)
            note.anchor?.let { anchor ->
                add(inlineCode(anchor.relPath + ":" + NoteRows.lineRange(anchor.startLine, anchor.endLine)))
                anchor.snapshot?.takeIf { it.isNotEmpty() }?.let { add(codeBlock(it, language(anchor.relPath))) }
            }
            note.body.takeIf { it.isNotBlank() }?.let { add(codeBlock(it, "text")) }
        }.joinToString("\n\n")
    }

    private fun inlineCode(text: String): String {
        val tick = "`".repeat(longestBacktickRun(text) + 1)
        val pad = if (text.startsWith('`') || text.endsWith('`')) " " else ""
        return tick + pad + text + pad + tick
    }

    private fun codeBlock(text: String, language: String): String {
        val fence = "`".repeat(maxOf(3, longestBacktickRun(text) + 1))
        return fence + language + "\n" + text.trimEnd('\n') + "\n" + fence
    }

    private fun language(relPath: String): String =
        relPath.substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.all(Char::isLetterOrDigit) }.orEmpty()

    private fun longestBacktickRun(text: String): Int = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0

    private const val VIEW_COLUMNS = "uid, kind, project, rel_path, class_name, start_line, end_line, snapshot, status, " +
        "title, body, ai_source, source_url, prompt, created_at, updated_at, tags"

    private fun ResultSet.toExported(): ExportedNote {
        val project = getString("project")
        val relPath = getString("rel_path")
        val anchor = if (getString("kind") == "CODE" && project != null && relPath != null) {
            FormatAnchor(project, relPath, getInt("start_line"), getInt("end_line"), getString("class_name"), getString("snapshot"))
        } else {
            null
        }
        val note = FormatNote(
            uid = getString("uid"),
            title = getString("title"),
            body = getString("body"),
            tags = getString("tags")?.split(',').orEmpty(),
            anchor = anchor,
            aiSource = getString("ai_source"),
            sourceUrl = getString("source_url"),
            prompt = getString("prompt"),
        )
        return ExportedNote(note, NoteStatus.valueOf(getString("status")), getLong("created_at"), getLong("updated_at"))
    }
}
