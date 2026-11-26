package io.github.driflux.jnote.db

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.nio.file.Path

@Service(Service.Level.APP)
class DatabaseService : Disposable {
    /** 한 번의 잠금으로 함께 읽은 경로와 그 경로로 만들어진 DB. DB를 만든 쪽은 곧 submit하므로 열린 것으로 다룬다. */
    data class Current(val path: Path, val database: Database?)

    private val lock = Any()
    private var path: Path? = null
    private var current: Database? = null
    private var disposed = false

    val database: Database
        get() = synchronized(lock) {
            check(!disposed) { "DatabaseService is disposed" }
            current ?: Database(pathLocked()).also { current = it }
        }

    fun currentPath(): Path = synchronized(lock) { pathLocked() }

    /** 경로와 DB를 따로 읽으면 그 사이 전환이 끼어들어 서로 다른 DB의 것이 될 수 있어 한 번에 읽는다. */
    fun current(): Current = synchronized(lock) {
        check(!disposed) { "DatabaseService is disposed" }
        Current(pathLocked(), current)
    }

    /** 만들어졌고 연결까지 열린 DB. 한 번도 쓰지 않았다면 null이다. */
    fun openDatabaseOrNull(): Database? = synchronized(lock) { current?.takeIf { it.isOpen } }

    /**
     * 상태가 아직 [expected]일 때만 [newPath]로 바꾸고 true를 돌려준다. 옛 DB는 닫지 않으므로 부른 쪽이 닫는다.
     * 기다리지 않으므로 옛 DB의 스레드 안에서도 부를 수 있다.
     */
    fun replace(expected: Current, newPath: Path): Boolean = synchronized(lock) {
        if (disposed || current !== expected.database || pathLocked() != expected.path) return false
        current = null
        path = newPath
        true
    }

    /** 새 경로로 전환한다. 옛 DB는 만들어진 경우에만 닫고, 새 DB는 첫 사용 때 만든다. */
    fun switchTo(newPath: Path) {
        val old = synchronized(lock) {
            val previous = current
            current = null
            path = newPath
            previous
        }
        old?.close()
    }

    private fun pathLocked(): Path = path ?: DatabasePaths.default().also { path = it }

    override fun dispose() {
        val old = synchronized(lock) {
            disposed = true
            current.also { current = null }
        }
        // EDT에서 불릴 수 있어 닫기를 기다리지 않는다. 한 번도 쓰지 않았다면 닫을 DB도 없다
        if (old != null) ApplicationManager.getApplication().executeOnPooledThread { old.close() }
    }

    companion object {
        fun getInstance(): DatabaseService = service()
    }
}
