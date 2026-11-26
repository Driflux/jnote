package io.github.driflux.jnote.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import io.github.driflux.jnote.db.DatabasePaths
import java.nio.file.Path

/** DB 경로 설정. 앱 서비스로 첫 사용 때 만들어지며 plugin.xml 등록이 필요 없다. */
@Service(Service.Level.APP)
@State(name = "JNoteSettings", storages = [Storage("jnote.xml", roamingType = RoamingType.DISABLED)])
class DbPathSettings : PersistentStateComponent<DbPathSettings.State> {
    /** dbPath가 비어 있으면 기본 경로를 뜻한다. */
    class State(var dbPath: String = "")

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** 기본 경로와 같으면 빈 값으로 저장해, 기본값이 바뀌어도 따라가게 한다. */
    fun store(path: Path) {
        state.dbPath = if (path == DatabasePaths.builtInDefault()) "" else path.toString()
    }

    companion object {
        fun getInstance(): DbPathSettings = service()
    }
}
