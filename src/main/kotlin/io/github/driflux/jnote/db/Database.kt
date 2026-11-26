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
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(BUSY_TIMEOUT_MS)
            enforceForeignKeys(true)
        }
        val opened = JDBC().connect("jdbc:sqlite:$path", config.toProperties())
            ?: error("sqlite-jdbc rejected url for $path")
        try {
            Migrations.run(opened)
        } catch (e: Throwable) {
            opened.close()
            throw e
        }
        return opened
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
        private const val CLOSE_TIMEOUT_MS = 10_000L
    }
}
