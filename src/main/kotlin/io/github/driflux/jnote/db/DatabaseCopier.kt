package io.github.driflux.jnote.db

import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ExecutionException

/** DB 파일 복사. 취소나 실패 시 대상 경로에는 일부만 쓰인 파일이 남지 않는다. */
object DatabaseCopier {
    private const val CHUNK_SIZE = 1 shl 20

    /**
     * [openDatabase]가 열려 있으면 DB 스레드에서 체크포인트 후 복사하고(그 사이 다른 쓰기가 끼어들지 못한다),
     * 아니면 호출 스레드에서 복사한다. 원본이 없으면 아무것도 만들지 않는다.
     * [checkCancelled]는 복사 중 주기적으로 불리며, 취소하려면 예외를 던진다.
     */
    fun copy(source: Path, target: Path, openDatabase: Database?, checkCancelled: () -> Unit) {
        if (openDatabase == null) {
            checkpointLeftoverWal(source)
            copyFile(source, target, checkCancelled)
            return
        }
        try {
            openDatabase.submit { connection ->
                WalCheckpoint.truncateOrFail(connection)
                copyFile(source, target, checkCancelled)
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

    private fun copyFile(source: Path, target: Path, checkCancelled: () -> Unit) {
        if (!Files.exists(source)) return
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
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            moved = true
        } finally {
            if (!moved) Files.deleteIfExists(temp)
        }
    }
}
