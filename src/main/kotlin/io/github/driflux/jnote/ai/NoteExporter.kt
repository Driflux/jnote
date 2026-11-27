package io.github.driflux.jnote.ai

import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.Tag
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 노트를 형식 v1 파일로 쓴다. DB를 기다리고 파일을 쓰므로 EDT 밖에서 부른다. */
class NoteExporter(private val notes: NoteRepository, private val tags: TagRepository) {
    /**
     * [selectedIds]가 null이면 모든 프로젝트의 노트 전부를, 아니면 그중 남아 있는 노트만 최근 수정 순으로 쓰고 쓴 수를 돌려준다.
     * 임시 파일에 다 쓴 뒤 한 번에 바꿔 끼우므로, [checkCanceled]가 던지거나 실패하면 [target]은 이전 그대로다.
     */
    fun export(target: Path, selectedIds: Set<Long>?, checkCanceled: () -> Unit): Int {
        val all = notes.list(null, allProjects = true).get()
        checkCanceled()
        val tagsByNote = tags.assignments().get()
        checkCanceled()
        val chosen = if (selectedIds == null) all else all.filter { it.id in selectedIds }
        val exported = chosen.map { toExported(it, tagsByNote[it.id].orEmpty()) }
        val partial = Files.createTempFile(target.toAbsolutePath().parent, target.fileName.toString(), ".tmp")
        try {
            Files.newBufferedWriter(partial, StandardCharsets.UTF_8).use { NoteFormatCodec.encode(exported, it) }
            checkCanceled()
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(partial)
        }
        return exported.size
    }

    internal companion object {
        fun toExported(note: Note, tags: List<Tag>): ExportedNote {
            val anchor = if (note.project != null && note.relPath != null && note.startLine != null && note.endLine != null) {
                FormatAnchor(note.project, note.relPath, note.startLine, note.endLine, note.className, note.snapshot)
            } else {
                null
            }
            val formatNote = FormatNote(
                uid = note.uid,
                title = note.title,
                body = note.body,
                tags = tags.map { it.name },
                anchor = anchor,
                aiSource = note.aiSource,
                sourceUrl = note.sourceUrl,
                prompt = note.prompt,
            )
            return ExportedNote(formatNote, note.status, note.createdAt, note.updatedAt)
        }
    }
}
