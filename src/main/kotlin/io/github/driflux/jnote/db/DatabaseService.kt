package io.github.driflux.jnote.db

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
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
        if (old != null) ApplicationManager.getApplication().executeOnPooledThread { old.close() }
    }

    companion object {
        fun getInstance(): DatabaseService = service()
    }
}
