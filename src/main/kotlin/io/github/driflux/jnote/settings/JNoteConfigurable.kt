package io.github.driflux.jnote.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.db.DatabasePaths
import java.nio.file.InvalidPathException
import java.util.concurrent.CompletableFuture

class JNoteConfigurable : BoundConfigurable(JNoteBundle.message("settings.displayName")) {
    internal var loadSettings: () -> Unit = { DbPathSettings.getInstance() }

    private var loaded = false
    private var loading: CompletableFuture<*>? = null

    override fun createPanel(): DialogPanel = panel {
        row(JNoteBundle.message("settings.dbPath.label")) {
            val cell = textFieldWithBrowseButton(JNoteBundle.message("settings.dbPath.browse.title"))
                .align(AlignX.FILL)
                .enabled(false)
                .bindText(
                    { if (loaded) DatabasePaths.default().toString() else "" },
                    { text -> if (loaded) DbPathChanger.request(DatabasePaths.resolve(text)) },
                )
                .validationOnInput { field -> invalidMessage(field.text)?.let { error(it) } }
                .validationOnApply { field -> invalidMessage(field.text)?.let { error(it) } }
            // 설정 서비스는 처음 만들어질 때 jnote.xml을 읽으므로 백그라운드에서 먼저 만들고, 그 전에는 필드를 잠가 둔다
            loading = CompletableFuture.runAsync(loadSettings, AppExecutorUtil.getAppExecutorService())
                .thenRunAsync({
                    loaded = true
                    reset()
                    cell.component.isEnabled = true
                }) { ApplicationManager.getApplication().invokeLater(it, ModalityState.any()) }
        }
    }

    override fun disposeUIResources() {
        loading?.cancel(false)
        loading = null
        super.disposeUIResources()
    }

    private fun invalidMessage(text: String): String? = invalidReason(text)?.let { JNoteBundle.message(it.messageKey) }

    // 검사기는 EDT에서 돌므로 파일 시스템을 보지 않고 형태만 본다. 폴더인지, 쓸 수 있는지는 Apply 뒤 백그라운드에서 가린다
    private fun invalidReason(text: String): DbPathChange.Reason? {
        val requested = try {
            DatabasePaths.resolve(text)
        } catch (_: InvalidPathException) {
            return DbPathChange.Reason.NOT_ABSOLUTE
        }
        return if (requested.isAbsolute) null else DbPathChange.Reason.NOT_ABSOLUTE
    }
}
