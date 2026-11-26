package io.github.driflux.jnote.settings

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.db.DatabaseService
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 설정 화면의 Apply가 EDT에서 부르는 진입점. 파일 시스템을 읽는 계획은 백그라운드 작업에서 세우고,
 * 확인창이 필요할 때만 EDT에서 띄운 뒤 복사와 전환은 다시 백그라운드에서 한다.
 */
object DbPathChanger {
    // 전환은 한 번에 하나만 한다. 둘이 겹치면 한쪽이 복사하는 사이 다른 쪽이 경로를 바꿔 버린다
    private val inFlight = AtomicBoolean(false)

    fun request(requested: Path, confirmSwitch: (Path) -> Boolean = ::askConfirmation) {
        if (!inFlight.compareAndSet(false, true)) {
            DbPathNotifier.busy()
            return
        }
        ProgressManager.getInstance().run(PlanTask(requested, confirmSwitch))
    }

    private class PlanTask(private val requested: Path, private val confirmSwitch: (Path) -> Boolean) :
        Task.Backgroundable(null, JNoteBundle.message("task.dbPath.switching"), true) {
        private var plan: DbPathChange.Plan = DbPathChange.Plan.NoChange
        private var handedOver = false

        override fun run(indicator: ProgressIndicator) {
            plan = DbPathChange.plan(DatabaseService.getInstance().currentPath(), requested)
        }

        // EDT에서 불린다
        override fun onSuccess() {
            when (val plan = plan) {
                DbPathChange.Plan.NoChange -> Unit
                is DbPathChange.Plan.Invalid -> DbPathNotifier.invalid(plan.reason)
                is DbPathChange.Plan.CopyThenSwitch -> switchInBackground(plan)
                is DbPathChange.Plan.SwitchToExisting -> if (confirmSwitch(plan.target)) switchInBackground(plan)
            }
        }

        private fun switchInBackground(plan: DbPathChange.Plan) {
            handedOver = true
            ProgressManager.getInstance().run(DbPathSwitchTask(plan) { inFlight.set(false) })
        }

        override fun onThrowable(error: Throwable) = DbPathNotifier.failed(error)

        override fun onFinished() {
            if (!handedOver) inFlight.set(false)
        }
    }

    private fun askConfirmation(target: Path): Boolean = Messages.showYesNoDialog(
        JNoteBundle.message("dialog.dbPath.switchExisting.message", target),
        JNoteBundle.message("dialog.dbPath.switchExisting.title"),
        Messages.getQuestionIcon(),
    ) == Messages.YES
}
