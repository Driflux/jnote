package io.github.driflux.jnote.ai

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/** 읽은 순간의 파일. 이름, 크기, 수정 시각이 같으면 같은 파일로 본다. */
data class InboxFile(val path: Path, val size: Long, val modified: Long)

/** 에이전트가 JSON 문서를 떨어뜨리는 폴더(`~/.jnote/inbox`). 모든 메서드가 파일 시스템을 쓰므로 EDT 밖에서 부른다. */
class Inbox(val dir: Path = defaultDir()) {
    /** 바로 아래의 `*.json` 일반 파일을 이름순으로. 디렉터리 목록과 속성만 읽고 내용은 읽지 않는다. 폴더가 없으면 빈 목록. */
    fun scan(): List<InboxFile> {
        if (!Files.isDirectory(dir)) return emptyList()
        val found = try {
            Files.newDirectoryStream(dir, "*.json").use { stream -> stream.mapNotNull(::describe) }
        } catch (_: NoSuchFileException) {
            emptyList()
        }
        return found.sortedBy { it.path.fileName.toString() }
    }

    private fun describe(path: Path): InboxFile? = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        if (attributes.isRegularFile) InboxFile(path, attributes.size(), attributes.lastModifiedTime().toMillis()) else null
    } catch (_: IOException) {
        null
    }

    companion object {
        fun defaultDir(): Path = Paths.get(System.getProperty("user.home"), ".jnote", "inbox")
    }
}
