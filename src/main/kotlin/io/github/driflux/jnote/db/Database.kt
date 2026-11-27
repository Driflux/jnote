package io.github.driflux.jnote.db

import com.intellij.openapi.diagnostic.logger
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
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class DatabaseClosedException : IllegalStateException("database is closed")

class Database internal constructor(
    private val resolvePath: () -> Path,
    private val closeTimeoutMillis: Long,
) : AutoCloseable {
    constructor(path: Path) : this({ path }, CLOSE_TIMEOUT_MS)

    internal constructor(path: Path, closeTimeoutMillis: Long) : this({ path }, closeTimeoutMillis)

    internal constructor(resolvePath: () -> Path) : this(resolvePath, CLOSE_TIMEOUT_MS)

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
        val path = resolvePath()
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
        val job = CompletableFuture.runAsync({
            connection?.close()
            connection = null
            closeJobDone = true
        }, executor)
        try {
            job.get(closeTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // 기다림을 그만둔 뒤 닫기가 실패하면 결과를 볼 쪽이 없어 그때 로그로 남긴다
            job.whenComplete { _, error -> if (error != null) logger<Database>().warn("Closing the database failed after the wait ended", error) }
            throw e
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
