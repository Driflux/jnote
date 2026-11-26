package io.github.driflux.jnote.db

import org.sqlite.JDBC
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 연결 하나와 단일 스레드 실행기를 소유한다.
 * 연결은 첫 submit 때 실행기 스레드에서 열리며, 모든 DB 접근은 그 스레드에서만 일어난다.
 */
class Database(private val path: Path) : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }

    // 실행기 스레드에서만 읽고 쓴다
    private var connection: Connection? = null

    @Volatile
    internal var isOpen: Boolean = false
        private set

    fun <T> submit(block: (Connection) -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({ block(connectionOrOpen()) }, executor)

    /** 테스트 전용 동기 헬퍼. EDT에서 호출하면 안 된다. */
    internal fun <T> submitAndWait(block: (Connection) -> T): T = submit(block).get(30, TimeUnit.SECONDS)

    private fun connectionOrOpen(): Connection =
        connection ?: open().also {
            connection = it
            isOpen = true
        }

    private fun open(): Connection {
        path.parent?.let { Files.createDirectories(it) }
        val config = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(BUSY_TIMEOUT_MS)
            enforceForeignKeys(true)
        }
        // DriverManager는 IDE 플러그인 클래스로더에서 드라이버를 못 찾을 수 있어 직접 연결한다
        return JDBC().connect("jdbc:sqlite:$path", config.toProperties())
            ?: error("sqlite-jdbc rejected url for $path")
    }

    override fun close() {
        if (executor.isShutdown) return
        executor.submit { connection?.close() }.get(10, TimeUnit.SECONDS)
        executor.shutdown()
        isOpen = false
    }

    companion object {
        const val THREAD_NAME = "JNote-DB"
        const val BUSY_TIMEOUT_MS = 5000
    }
}
