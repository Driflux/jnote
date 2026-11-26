package io.github.driflux.jnote.ui

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/** 노트의 상대 경로를 프로젝트 안의 실제 파일로 풀어 준다. */
internal object CodeTargets {
    /**
     * 절대 경로나 `..` 조각이 든 경로는 프로젝트 밖을 가리킬 수 있으므로 거부한다.
     * 노트는 JSON 가져오기로도 들어오므로 경로를 믿지 않는다.
     */
    fun isSafeRelPath(relPath: String): Boolean {
        if (relPath.isBlank() || relPath.startsWith("/") || relPath.startsWith("\\")) return false
        if (relPath.length > 1 && relPath[1] == ':') return false
        return relPath.split('/', '\\').none { it == ".." }
    }

    /** 파일 시스템을 읽으므로 EDT 밖에서 부른다. 없거나 폴더이거나 안전하지 않은 경로면 null. */
    fun find(project: Project, relPath: String): VirtualFile? {
        if (!isSafeRelPath(relPath)) return null
        val baseDir = ReadAction.compute<VirtualFile?, RuntimeException> { project.guessProjectDir() } ?: return null
        val cached = baseDir.findFileByRelativePath(relPath)
        // 밖에서 막 생긴 파일은 VFS가 아직 모를 수 있어 디스크를 한 번 새로 확인한다
        val found = cached ?: project.basePath?.let {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(it).resolve(relPath))
        }
        return found?.takeIf { !it.isDirectory }
    }
}
