package io.github.driflux.jnote.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.db.DatabasePaths
import java.nio.file.InvalidPathException

class JNoteConfigurable : BoundConfigurable(JNoteBundle.message("settings.displayName")) {
    override fun createPanel(): DialogPanel = panel {
        row(JNoteBundle.message("settings.dbPath.label")) {
            textFieldWithBrowseButton(JNoteBundle.message("settings.dbPath.browse.title"))
                .align(AlignX.FILL)
                .bindText(
                    { DatabasePaths.default().toString() },
                    { DbPathChanger.request(DatabasePaths.resolve(it)) },
                )
                .validationOnInput { field -> invalidMessage(field.text)?.let { error(it) } }
                .validationOnApply { field -> invalidMessage(field.text)?.let { error(it) } }
        }
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
