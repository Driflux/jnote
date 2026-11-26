package io.github.driflux.jnote.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.db.DatabasePaths
import java.nio.file.InvalidPathException

/** Settings > Tools > JNote. 항목은 DB 경로 하나뿐이며, 화면 구성만 맡고 판단은 DbPathChange에 맡긴다. */
class JNoteConfigurable : BoundConfigurable(JNoteBundle.message("settings.displayName")) {
    override fun createPanel(): DialogPanel = panel {
        row(JNoteBundle.message("settings.dbPath.label")) {
            textFieldWithBrowseButton(JNoteBundle.message("settings.dbPath.browse.title"))
                .align(AlignX.FILL)
                .bindText(
                    { DatabasePaths.default().toString() },
                    { DbPathSettings.getInstance().store(DatabasePaths.resolve(it)) },
                )
                .validationOnInput { field -> invalidMessage(field.text)?.let { error(it) } }
                .validationOnApply { field -> invalidMessage(field.text)?.let { error(it) } }
        }
    }

    private fun invalidMessage(text: String): String? = invalidReason(text)?.let { JNoteBundle.message(it.messageKey) }

    private fun invalidReason(text: String): DbPathChange.Reason? {
        val plan = try {
            DbPathChange.plan(DatabasePaths.default(), DatabasePaths.resolve(text))
        } catch (_: InvalidPathException) {
            return DbPathChange.Reason.NOT_ABSOLUTE
        }
        return (plan as? DbPathChange.Plan.Invalid)?.reason
    }
}
