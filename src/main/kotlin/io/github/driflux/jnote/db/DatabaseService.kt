package io.github.driflux.jnote.db

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/** 앱 레벨 서비스. plugin.xml 등록 없이 첫 사용 때 만들어지고, 플러그인 언로드 시 DB를 닫는다. */
@Service(Service.Level.APP)
class DatabaseService : Disposable {
    private val lazyDatabase = lazy { Database(DatabasePaths.default()) }

    val database: Database get() = lazyDatabase.value

    override fun dispose() {
        // 한 번도 쓰지 않았다면 닫기 위해 DB를 만들 필요가 없다
        if (lazyDatabase.isInitialized()) lazyDatabase.value.close()
    }

    companion object {
        fun getInstance(): DatabaseService = service()
    }
}
