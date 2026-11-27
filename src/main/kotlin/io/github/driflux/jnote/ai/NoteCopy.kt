package io.github.driflux.jnote.ai

import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.ui.NoteRows
import java.io.StringWriter
import java.sql.ResultSet
import java.util.concurrent.CompletableFuture

/** 선택한 노트를 AI 대화에 붙여 넣을 Markdown이나 JSON 형식 v1 텍스트로 만든다. */
object NoteCopy {
    /**
     * [uids] 순서대로, 아직 남아 있는 노트만 돌려준다. 다른 IDE가 그 사이 지우거나 고쳐도 노트와 태그가
     * 어긋나지 않도록 v_notes_export 한 문장으로 읽는다. id는 지운 뒤 재사용될 수 있어 uid로 찾는다.
     */
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

    /** 노트마다 제목, 파일 위치, 스니펫, 본문. FREE 노트는 위치와 스니펫이 없다. */
    fun markdown(notes: List<FormatNote>): String = notes.joinToString("\n\n") { note ->
        buildList {
            add("## " + note.title)
            note.anchor?.let { anchor ->
                add("`" + anchor.relPath + ":" + NoteRows.lineRange(anchor.startLine, anchor.endLine) + "`")
                anchor.snapshot?.takeIf { it.isNotEmpty() }?.let { add(codeBlock(it, anchor.relPath)) }
            }
            note.body.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString("\n\n")
    }

    // 스니펫 안의 백틱 묶음보다 길게 펜스를 잡아야 코드 블록이 중간에 닫히지 않는다
    private fun codeBlock(snippet: String, relPath: String): String {
        val longestRun = Regex("`+").findAll(snippet).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longestRun + 1))
        val language = relPath.substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.all(Char::isLetterOrDigit) }.orEmpty()
        return fence + language + "\n" + snippet.trimEnd('\n') + "\n" + fence
    }

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
