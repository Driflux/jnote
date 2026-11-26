package io.github.driflux.jnote.settings

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.db.DatabaseService
import java.nio.file.Path

/**
 * 설정 화면의 Apply가 EDT에서 부르는 진입점. 파일 시스템을 읽는 계획은 백그라운드 작업에서 세우고,
 * 확인창이 필요할 때만 EDT에서 띄운 뒤 복사와 전환은 다시 백그라운드에서 한다.
 */
object DbPathChanger {
    fun request(requested: Path, confirmSwitch: (Path) -> Boolean = ::askConfirmation) {
        ProgressManager.getInstance().run(PlanTask(requested, confirmSwitch))
    }

    private class PlanTask(private val requested: Path, private val confirmSwitch: (Path) -> Boolean) :
        Task.Backgroundable(null, JNoteBundle.message("task.dbPath.switching"), true) {
        private var plan: DbPathChange.Plan = DbPathChange.Plan.NoChange

        override fun run(indicator: ProgressIndicator) {
            plan = DbPathChange.plan(DatabaseService.getInstance().currentPath(), requested)
        }

        // EDT에서 불린다
        override fun onSuccess() {
            when (val plan = plan) {
                DbPathChange.Plan.NoChange -> Unit
                is DbPathChange.Plan.Invalid -> DbPathNotifier.invalid(plan.reason)
                is DbPathChange.Plan.CopyThenSwitch -> ProgressManager.getInstance().run(DbPathSwitchTask(plan))
                is DbPathChange.Plan.SwitchToExisting ->
                    if (confirmSwitch(plan.target)) ProgressManager.getInstance().run(DbPathSwitchTask(plan))
            }
        }

        override fun onThrowable(error: Throwable) = DbPathNotifier.failed(error)
    }

    private fun askConfirmation(target: Path): Boolean = Messages.showYesNoDialog(
        JNoteBundle.message("dialog.dbPath.switchExisting.message", target),
        JNoteBundle.message("dialog.dbPath.switchExisting.title"),
        Messages.getQuestionIcon(),
    ) == Messages.YES
}
