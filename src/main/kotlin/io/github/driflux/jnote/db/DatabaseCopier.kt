package io.github.driflux.jnote.db

import org.sqlite.JDBC
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ExecutionException

/** DB 파일 복사. 취소나 실패 시 대상 경로에는 일부만 쓰인 파일이 남지 않는다. */
object DatabaseCopier {
    private const val CHUNK_SIZE = 1 shl 20

    /**
     * [source]를 [target]으로 복사하고, 제자리로 옮긴 직후 같은 스레드에서 [commit]을 불러 그 결과를 돌려준다.
     * [openDatabase]가 있으면 체크포인트, 복사, commit을 그 DB 스레드의 한 작업으로 실행해 그 사이 다른 작업이 끼어들지 못한다.
     * 없으면 호출 스레드에서 복사하며, 원본이 없으면 아무것도 만들지 않고 commit만 부른다.
     * [checkCancelled]는 옮기기 전까지만 주기적으로 불리며, 취소하려면 예외를 던진다.
     * commit이 false를 돌려주거나 실패하면 복사본을 지운다.
     */
    fun copy(source: Path, target: Path, openDatabase: Database?, checkCancelled: () -> Unit, commit: () -> Boolean): Boolean {
        if (openDatabase == null) {
            checkpointLeftoverWal(source)
            return copyFile(source, target, checkCancelled, commit)
        }
        try {
            return openDatabase.submit { connection ->
                WalCheckpoint.truncateOrFail(connection)
                copyFile(source, target, checkCancelled, commit).also { committed ->
                    // 이 작업 뒤에 이미 줄 선 쓰기가 전환 뒤 옛 파일에만 남지 않도록, 옛 연결은 쓰기를 거부하게 한다
                    if (committed) connection.createStatement().use { it.execute("PRAGMA query_only = ON") }
                }
            }.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    // 이번 세션에서 열지 않은 DB라도 이전 비정상 종료로 -wal에 데이터가 남아 있을 수 있어, 그때만 잠깐 연결해 비운다
    private fun checkpointLeftoverWal(source: Path) {
        val wal = source.resolveSibling(source.fileName.toString() + "-wal")
        if (!Files.exists(source) || !Files.exists(wal) || Files.size(wal) == 0L) return
        val connection = JDBC().connect("jdbc:sqlite:$source", Properties())
            ?: error("sqlite-jdbc rejected url for $source")
        connection.use { WalCheckpoint.truncateOrFail(it) }
    }

    private fun copyFile(source: Path, target: Path, checkCancelled: () -> Unit, commit: () -> Boolean): Boolean {
        requireNothingAt(target)
        if (!Files.exists(source)) return commit()
        val parent = target.toAbsolutePath().parent
        Files.createDirectories(parent)
        // 임시 파일에 다 쓴 뒤 한 번에 옮겨, 대상 경로가 중간 상태로 보이는 일이 없게 한다
        val temp = Files.createTempFile(parent, target.fileName.toString(), ".copying")
        var moved = false
        try {
            Files.newInputStream(source).use { input ->
                Files.newOutputStream(temp).use { output ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    while (true) {
                        checkCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            checkCancelled()
            // ATOMIC_MOVE는 있는 파일을 덮어쓰므로, 복사하는 사이 누가 만들었는지 옮기기 직전에 다시 본다
            requireNothingAt(target)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            moved = true
        } finally {
            if (!moved) Files.deleteIfExists(temp)
        }
        // 옮긴 뒤로는 취소를 받지 않는다. 복사본은 전환과 함께 남거나 함께 지워진다
        val committed = try {
            commit()
        } catch (e: Throwable) {
            Files.deleteIfExists(target)
            throw e
        }
        if (!committed) Files.deleteIfExists(target)
        return committed
    }

    // 남은 -wal/-shm은 새 파일을 열 때 그 파일의 내용인 것처럼 합쳐질 수 있어, 대상과 함께 셋 다 없어야 한다
    private fun requireNothingAt(target: Path) {
        for (suffix in listOf("", "-wal", "-shm")) {
            val path = target.resolveSibling(target.fileName.toString() + suffix)
            if (Files.exists(path)) throw FileAlreadyExistsException(path.toString())
        }
    }
}
