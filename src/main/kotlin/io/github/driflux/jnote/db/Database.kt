package io.github.driflux.jnote.db

import org.sqlite.JDBC
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 연결 하나와 단일 스레드 실행기를 소유한다.
 * 연결은 첫 submit 때 실행기 스레드에서 열리며, 모든 DB 접근은 그 스레드에서만 일어난다.
 */
/** 닫힌 [Database]에 일을 맡겼을 때의 실패. 호출자가 DB 경로 전환과 구분해서 다룰 수 있게 타입으로 남긴다. */
class DatabaseClosedException : IllegalStateException("database is closed")

class Database internal constructor(
    private val path: Path,
    private val closeTimeoutMillis: Long,
) : AutoCloseable {
    constructor(path: Path) : this(path, CLOSE_TIMEOUT_MS)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }

    // 실행기 스레드에서만 읽고 쓴다
    private var connection: Connection? = null
    private var closeJobDone = false

    private val closed = AtomicBoolean(false)

    @Volatile
    internal var isOpen: Boolean = false
        private set

    /** 닫힌 뒤에는 예외를 던지지 않고 실패한 future를 돌려주므로 EDT 호출자도 안전하다. */
    fun <T> submit(block: (Connection) -> T): CompletableFuture<T> =
        try {
            CompletableFuture.supplyAsync({ block(connectionOrOpen()) }, executor)
        } catch (e: RejectedExecutionException) {
            CompletableFuture.failedFuture(closedError())
        }

    /** 테스트 전용 동기 헬퍼. EDT에서 호출하면 안 된다. */
    internal fun <T> submitAndWait(block: (Connection) -> T): T = submit(block).get(30, TimeUnit.SECONDS)

    private fun connectionOrOpen(): Connection =
        connection ?: open().also {
            connection = it
            isOpen = true
        }

    private fun open(): Connection {
        // 닫기 작업 뒤에 실행되는 작업은 연결을 다시 열지 않고 실패해야 한다
        if (closeJobDone) throw closedError()
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
        if (!closed.compareAndSet(false, true)) return
        try {
            executor.submit {
                connection?.close()
                connection = null
                closeJobDone = true
            }.get(closeTimeoutMillis, TimeUnit.MILLISECONDS)
        } finally {
            // 대기 시간이 지나도 shutdown하면 앞선 작업이 끝난 뒤 닫기 작업이 마저 실행되고 스레드가 끝난다
            executor.shutdown()
            isOpen = false
        }
    }

    private fun closedError() = DatabaseClosedException()

    companion object {
        const val THREAD_NAME = "JNote-DB"
        const val BUSY_TIMEOUT_MS = 5000
        private const val CLOSE_TIMEOUT_MS = 10_000L
    }
}
