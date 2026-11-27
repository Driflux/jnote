package io.github.driflux.jnote.ai

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** 이 창이 선점해 `processing/`에 둔 파일. [name]은 inbox에서의 원래 이름이다. */
data class ClaimedFile(val name: String, val path: Path)

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

    /**
     * 파일을 `processing/`으로 원자적으로 옮겨 이 창이 선점한다. 이미 없으면(다른 창이 먼저 가져갔거나 지워졌으면) null.
     * 선점한 뒤에 읽으므로, 가져온 파일과 나중에 옮겨 두는 파일이 같고 두 창이 같은 문서를 반영하지 않는다.
     */
    fun claim(file: InboxFile): ClaimedFile? {
        val processing = Files.createDirectories(dir.resolve(PROCESSING))
        val name = file.path.fileName.toString()
        // 원래 이름이 길어도 넘치지 않도록 선점 이름은 고정 길이로 하고, 원래 이름은 메모리에만 둔다
        val target = processing.resolve(UUID.randomUUID().toString() + ".json")
        return try {
            Files.move(file.path, target, StandardCopyOption.ATOMIC_MOVE)
            ClaimedFile(name, target)
        } catch (_: NoSuchFileException) {
            null
        }
    }

    /** 처리를 마친 파일을 `done/`으로. 같은 이름이 있으면 덮어쓰지 않고 번호를 붙인다. */
    fun archive(claimed: ClaimedFile): Path = moveWithoutOverwrite(claimed, Files.createDirectories(dir.resolve(DONE)))

    /** 처리하지 않은 파일을 inbox로 되돌린다. 그 사이 같은 이름이 생겼으면 번호를 붙인다. */
    fun release(claimed: ClaimedFile): Path = moveWithoutOverwrite(claimed, dir)

    /**
     * 하드 링크는 대상이 이미 있으면 원자적으로 실패하므로 두 창이 같은 이름을 골라도 서로 덮어쓰지 않는다.
     * ATOMIC_MOVE는 플랫폼에 따라 기존 파일을 덮어쓴다. 링크를 못 만드는 볼륨에서는 덮어쓰지 않는 일반 이동으로 대신한다.
     */
    private fun moveWithoutOverwrite(claimed: ClaimedFile, folder: Path): Path {
        val stem = claimed.name.removeSuffix(".json")
        for (n in 0 until MAX_NAME_TRIES) {
            val target = folder.resolve(if (n == 0) claimed.name else "$stem-$n.json")
            try {
                try {
                    Files.createLink(target, claimed.path)
                    Files.delete(claimed.path)
                } catch (e: FileAlreadyExistsException) {
                    throw e
                } catch (_: UnsupportedOperationException) {
                    Files.move(claimed.path, target)
                } catch (_: FileSystemException) {
                    Files.move(claimed.path, target)
                }
                return target
            } catch (_: FileAlreadyExistsException) {
                continue
            }
        }
        throw IOException("no free name for ${claimed.name} in $folder")
    }

    private fun describe(path: Path): InboxFile? = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        if (attributes.isRegularFile) InboxFile(path, attributes.size(), attributes.lastModifiedTime().toMillis()) else null
    } catch (_: IOException) {
        null
    }

    companion object {
        const val DONE = "done"
        const val PROCESSING = "processing"
        private const val MAX_NAME_TRIES = 1000

        fun defaultDir(): Path = Paths.get(System.getProperty("user.home"), ".jnote", "inbox")
    }
}
