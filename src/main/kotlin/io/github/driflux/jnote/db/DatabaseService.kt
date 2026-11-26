package io.github.driflux.jnote.db

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.nio.file.Path

@Service(Service.Level.APP)
class DatabaseService : Disposable {
    private val lock = Any()
    private var path: Path? = null
    private var current: Database? = null

    val database: Database
        get() = synchronized(lock) { current ?: Database(pathLocked()).also { current = it } }

    fun currentPath(): Path = synchronized(lock) { pathLocked() }

    /** 만들어졌고 연결까지 열린 DB. 한 번도 쓰지 않았다면 null이라 복사할 때 파일을 만들지 않는다. */
    fun openDatabaseOrNull(): Database? = synchronized(lock) { current?.takeIf { it.isOpen } }

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
        synchronized(lock) { current }?.close()
    }

    companion object {
        fun getInstance(): DatabaseService = service()
    }
}
