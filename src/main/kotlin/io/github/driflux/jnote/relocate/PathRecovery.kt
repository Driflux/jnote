package io.github.driflux.jnote.relocate

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.driflux.jnote.anchor.AnchorLines
import io.github.driflux.jnote.model.AnchorUpdate
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus

/** 경로 복구 결과. */
internal sealed interface Recovery {
    /** [file]에서 노트를 찾았다. [update]의 relPath는 그 파일의 상대 경로다. */
    data class Found(val file: VirtualFile, val update: AnchorUpdate) : Recovery

    /** 찾아봤지만 없다. [update]는 경로와 줄이 그대로이고 상태만 ORPHAN 또는 NEEDS_RELOCATE다. */
    data class NotFound(val update: AnchorUpdate) : Recovery

    /** 찾지 않았다(인덱싱 중, 스냅샷 없음, 프로젝트 경로 없음). DB를 바꾸지 않는다. */
    data object Skipped : Recovery
}

/**
 * 경로가 깨진 CODE 노트를 파일 이름과 클래스 이름으로 FilenameIndex에서 다시 찾는다(intent.md 6절).
 * 인덱스와 파일을 읽으므로 EDT 밖에서 부른다.
 */
internal object PathRecovery {
    /** 살펴볼 후보 파일 수 상한. 흔한 이름이 프로젝트 곳곳에 있어도 비용이 커지지 않게 한다. */
    private const val MAX_CANDIDATES = 20

    fun recover(project: Project, note: Note): Recovery {
        val base = project.basePath
        if (note.kind != NoteKind.CODE || note.snapshot.isNullOrBlank() || base == null) return Recovery.Skipped
        val candidates = candidateFiles(project, base, candidateNames(note.relPath!!, note.className)) ?: return Recovery.Skipped
        var approx: Recovery.Found? = null
        var exactOnly = false
        for ((file, relPath) in candidates) {
            val text = ReadAction.compute<String?, Throwable> { FileDocumentManager.getInstance().getDocument(file)?.text } ?: continue
            // 스냅샷과 같은 규칙으로 줄을 나눈다
            val lines = text.lines()
            val similarity = OpenValidationService.similarityAllowed(lines)
            if (!similarity) exactOnly = true
            val update = FileRelocation.relocate(listOf(note), lines, similarity)!!.single()
            // 정확 일치만 찾으면 못 찾은 APPROX 노트도 APPROX로 돌아오므로, APPROX는 유사도를 켠 후보에서만 찾은 것이다
            val found = update.status == NoteStatus.OK || (similarity && update.status == NoteStatus.APPROX)
            if (!found) continue
            val hit = Recovery.Found(file, update.copy(relPath = relPath))
            if (update.status == NoteStatus.OK) return hit
            if (approx == null) approx = hit
        }
        if (approx != null) return approx
        // 큰 파일은 정확 일치만 찾았으므로 아직 다시 찾을 여지가 있다
        val status = if (exactOnly) NoteStatus.NEEDS_RELOCATE else NoteStatus.ORPHAN
        return Recovery.NotFound(AnchorUpdate(note.uid, note.relPath, note.startLine!!, note.endLine!!, status))
    }

    /**
     * 찾을 파일 이름. 경로의 파일 이름이 먼저이고, 클래스 이름이 있으면 가장 바깥 이름에 원래 확장자를 붙인 이름이 다음이다.
     * 예: "util/Helpers.kt"와 "Widget.render"는 ["Helpers.kt", "Widget.kt"].
     */
    internal fun candidateNames(relPath: String, className: String?): List<String> {
        val fileName = relPath.substringAfterLast('/')
        val extension = fileName.substringAfterLast('.', "")
        val outer = className?.substringBefore('.')?.takeIf { it.isNotBlank() }
        val classFile = outer?.let { if (extension.isEmpty()) it else "$it.$extension" }
        return listOfNotNull(fileName, classFile).distinct()
    }

    /** 이름 순서대로, 같은 이름 안에서는 상대 경로순. 인덱싱 중이면 기다리지 않고 null. */
    private fun candidateFiles(project: Project, base: String, names: List<String>): List<Pair<VirtualFile, String>>? =
        ReadAction.compute<List<Pair<VirtualFile, String>>?, Throwable> {
            if (project.isDisposed || DumbService.isDumb(project)) return@compute null
            val scope = GlobalSearchScope.projectScope(project)
            try {
                names.flatMap { name ->
                    FilenameIndex.getVirtualFilesByName(name, scope)
                        .filter { !it.isDirectory }
                        .mapNotNull { file -> AnchorLines.relativePath(base, file.path)?.let { file to it } }
                        .sortedBy { it.second }
                }.distinctBy { it.first }.take(MAX_CANDIDATES)
            } catch (e: IndexNotReadyException) {
                // 확인한 뒤 인덱싱이 시작됐다
                null
            }
        }
}
