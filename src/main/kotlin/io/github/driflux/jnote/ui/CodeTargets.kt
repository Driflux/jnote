package io.github.driflux.jnote.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/** 노트의 상대 경로를 프로젝트 안의 실제 파일로 풀어 준다. */
internal object CodeTargets {
    private val driveLetter = Regex("^[A-Za-z]:")

    /**
     * 절대 경로나 `..` 조각이 든 경로는 프로젝트 밖을 가리킬 수 있으므로 거부한다.
     * 노트는 JSON 가져오기로도 들어오므로 경로를 믿지 않는다.
     * 드라이브 문자(`C:`)는 Windows에서만 경로를 바꾸므로 거기서만 거부한다. POSIX에서 `a:b/x.kt`는 평범한 상대 경로다.
     */
    fun isSafeRelPath(relPath: String, windows: Boolean = SystemInfo.isWindows): Boolean {
        if (relPath.isBlank() || relPath.startsWith("/") || relPath.startsWith("\\")) return false
        if (windows && driveLetter.containsMatchIn(relPath)) return false
        return relPath.split('/', '\\').none { it == ".." }
    }

    /** 파일 시스템을 읽으므로 EDT 밖에서 부른다. 없거나 폴더이거나 안전하지 않은 경로면 null. */
    fun find(project: Project, relPath: String): VirtualFile? = project.basePath?.let { findUnder(it, relPath) }

    /**
     * 노트의 relPath는 `project.basePath` 기준이다(ADR 0005). 모듈이 여러 개면 guessProjectDir()가 달라질 수 있어
     * VFS 조회와 새로고침 모두 같은 [basePath]를 쓴다.
     */
    internal fun findUnder(basePath: String, relPath: String): VirtualFile? {
        if (!isSafeRelPath(relPath)) return null
        val path = Path.of(basePath).resolve(relPath)
        val fileSystem = LocalFileSystem.getInstance()
        // 밖에서 막 생긴 파일은 VFS가 아직 모를 수 있어 디스크를 한 번 새로 확인한다
        val found = fileSystem.findFileByNioFile(path) ?: fileSystem.refreshAndFindFileByNioFile(path)
        return found?.takeIf { !it.isDirectory }
    }
}
