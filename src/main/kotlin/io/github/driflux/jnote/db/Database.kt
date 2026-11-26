package io.github.driflux.jnote.db

import org.sqlite.JDBC
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DatabaseClosedException : IllegalStateException("database is closed")

class Database internal constructor(
    private val path: Path,
    private val closeTimeoutMillis: Long,
) : AutoCloseable {
    constructor(path: Path) : this(path, CLOSE_TIMEOUT_MS)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }

    private var connection: Connection? = null
    private var closeJobDone = false

    private val closed = AtomicBoolean(false)

    @Volatile
    internal var isOpen: Boolean = false
        private set

    fun <T> submit(block: (Connection) -> T): CompletableFuture<T> =
        try {
            CompletableFuture.supplyAsync({ block(connectionOrOpen()) }, executor)
        } catch (e: RejectedExecutionException) {
            CompletableFuture.failedFuture(closedError())
        }

    internal fun <T> submitAndWait(block: (Connection) -> T): T = submit(block).get(30, TimeUnit.SECONDS)

    private fun connectionOrOpen(): Connection =
        connection ?: open().also {
            connection = it
            isOpen = true
        }

    private fun open(): Connection {
        if (closeJobDone) throw closedError()
        path.parent?.let { Files.createDirectories(it) }
        val config = SQLiteConfig().apply {
            setBusyTimeout(BUSY_TIMEOUT_MS)
            enforceForeignKeys(true)
        }
        val opened = JDBC().connect("jdbc:sqlite:$path", config.toProperties())
            ?: error("sqlite-jdbc rejected url for $path")
        try {
            enableWal(opened)
            Migrations.run(opened)
        } catch (e: Throwable) {
            opened.close()
            throw e
        }
        return opened
    }

    /**
     * 두 연결이 새 파일을 동시에 WAL로 바꾸면 SQLite는 교착을 피하려고 busy handler를 부르지 않고 바로 SQLITE_BUSY를 돌려준다.
     * 먼저 바꾼 쪽이 끝나면 파일이 이미 WAL이라 다시 시도하면 성공한다. https://www.sqlite.org/c3ref/busy_handler.html
     */
    private fun enableWal(connection: Connection) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUSY_TIMEOUT_MS.toLong())
        while (true) {
            try {
                connection.createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
                return
            } catch (e: SQLiteException) {
                if (e.resultCode != SQLiteErrorCode.SQLITE_BUSY || System.nanoTime() > deadline) throw e
                Thread.sleep(WAL_RETRY_MS)
            }
        }
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
            executor.shutdown()
            isOpen = false
        }
    }

    private fun closedError() = DatabaseClosedException()

    companion object {
        const val THREAD_NAME = "JNote-DB"
        const val BUSY_TIMEOUT_MS = 5000
        private const val WAL_RETRY_MS = 10L
        private const val CLOSE_TIMEOUT_MS = 10_000L
    }
}
