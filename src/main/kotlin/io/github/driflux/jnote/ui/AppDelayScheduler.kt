package io.github.driflux.jnote.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

/** 앱 스케줄러로 기다린 뒤 EDT에서 작업을 실행한다. 대기 중에는 스레드를 잡지 않는다. */
internal object AppDelayScheduler : DelayScheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val future = AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { ApplicationManager.getApplication().invokeLater({ task() }, ModalityState.any()) },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
        return Cancellable { future.cancel(false) }
    }
}
