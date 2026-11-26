package io.github.driflux.jnote.db

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
class DatabaseService : Disposable {
    private val lazyDatabase = lazy { Database(DatabasePaths.default()) }

    val database: Database get() = lazyDatabase.value

    override fun dispose() {
        if (lazyDatabase.isInitialized()) lazyDatabase.value.close()
    }

    companion object {
        fun getInstance(): DatabaseService = service()
    }
}
