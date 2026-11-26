package io.github.driflux.jnote.settings

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.db.DatabaseService
import java.nio.file.Path

/** 설정 화면의 Apply가 부르는 진입점. 계획을 세워 알림, 확인창, 백그라운드 작업 중 하나로 보낸다. EDT에서 부른다. */
object DbPathChanger {
    fun request(requested: Path, confirmSwitch: (Path) -> Boolean = ::askConfirmation) {
        when (val plan = DbPathChange.plan(DatabaseService.getInstance().currentPath(), requested)) {
            DbPathChange.Plan.NoChange -> Unit
            is DbPathChange.Plan.Invalid -> DbPathNotifier.invalid(plan.reason)
            is DbPathChange.Plan.CopyThenSwitch -> ProgressManager.getInstance().run(DbPathSwitchTask(plan))
            is DbPathChange.Plan.SwitchToExisting ->
                if (confirmSwitch(plan.target)) ProgressManager.getInstance().run(DbPathSwitchTask(plan))
        }
    }

    private fun askConfirmation(target: Path): Boolean = Messages.showYesNoDialog(
        JNoteBundle.message("dialog.dbPath.switchExisting.message", target),
        JNoteBundle.message("dialog.dbPath.switchExisting.title"),
        Messages.getQuestionIcon(),
    ) == Messages.YES
}
