package io.github.driflux.jnote.settings

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.db.DatabaseService
import java.util.concurrent.CancellationException

/** 복사와 전환을 취소 가능한 백그라운드 작업으로 실행한다. 취소하거나 실패하면 옛 DB가 그대로 쓰인다. */
class DbPathSwitchTask(private val plan: DbPathChange.Plan) :
    Task.Backgroundable(null, JNoteBundle.message("task.dbPath.switching"), true) {

    override fun run(indicator: ProgressIndicator) {
        try {
            DbPathSwitcher.execute(plan, DatabaseService.getInstance(), DbPathSettings.getInstance()) {
                if (indicator.isCanceled) throw CancellationException()
            }
        } catch (_: CancellationException) {
            throw ProcessCanceledException()
        }
    }

    override fun onThrowable(error: Throwable) = DbPathNotifier.failed(error)
}
