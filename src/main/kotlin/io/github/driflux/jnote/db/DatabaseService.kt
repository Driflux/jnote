package io.github.driflux.jnote.db

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Path

@Service(Service.Level.APP)
class DatabaseService : Disposable {
    data class Current(val path: Path, val database: Database?)

    private val lock = Any()
    private var path: Path? = null
    private var current: Database? = null
    private var disposed = false

    private var firstDefault: Path? = null

    internal var defaultPath: () -> Path = DatabasePaths::default

    val database: Database
        get() = synchronized(lock) {
            check(!disposed) { "DatabaseService is disposed" }
            current ?: Database(path?.let { known -> { known } } ?: ::resolveDefault).also { current = it }
        }

    fun currentPath(): Path = synchronized(lock) { path } ?: resolveDefault()

    fun current(): Current {
        currentPath()
        return synchronized(lock) {
            check(!disposed) { "DatabaseService is disposed" }
            Current(checkNotNull(path), current)
        }
    }

    fun openDatabaseOrNull(): Database? = synchronized(lock) { current?.takeIf { it.isOpen } }

    fun replace(expected: Current, newPath: Path): Boolean = synchronized(lock) {
        if (disposed || current !== expected.database || path != expected.path) return false
        current = null
        path = newPath
        true
    }

    fun switchTo(newPath: Path) {
        currentPath()
        val old = synchronized(lock) {
            val previous = current
            current = null
            path = newPath
            previous
        }
        old?.close()
    }

    private fun resolveDefault(): Path {
        synchronized(lock) { firstDefault }?.let { return it }
        val resolved = defaultPath()
        return synchronized(lock) {
            val first = firstDefault ?: resolved.also { firstDefault = it }
            if (path == null) path = first
            first
        }
    }

    override fun dispose() {
        val old = synchronized(lock) {
            disposed = true
            current.also { current = null }
        }
        if (old != null) ApplicationManager.getApplication().executeOnPooledThread { closeAndLog(old) }
    }

    companion object {
        fun getInstance(): DatabaseService = service()

        /** 플러그인 해제나 IDE 종료 중에는 알릴 곳이 없어, 닫기 실패(시간 초과 포함)를 던지지 않고 로그에만 남긴다. */
        internal fun closeAndLog(
            database: AutoCloseable,
            warn: (String, Throwable) -> Unit = { message, e -> logger<DatabaseService>().warn(message, e) },
        ) {
            try {
                database.close()
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                warn("Closing the JNote database failed", e)
            }
        }
    }
}
