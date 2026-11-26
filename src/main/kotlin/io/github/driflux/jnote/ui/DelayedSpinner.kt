package io.github.driflux.jnote.ui

fun interface Cancellable {
    fun cancel()
}

/** 일정 시간 뒤에 작업을 실행한다. 작업은 [DelayedSpinner]를 부르는 스레드(EDT)에서 실행되어야 한다. */
fun interface DelayScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

/**
 * 읽기가 [delayMs]보다 오래 걸릴 때만 스피너를 보여 준다. 빠른 읽기에서는 한 번도 켜지지 않아 깜빡이지 않는다.
 * EDT에서만 쓴다.
 */
class DelayedSpinner(
    private val scheduler: DelayScheduler,
    private val onVisibleChanged: (Boolean) -> Unit,
    private val delayMs: Long = DEFAULT_DELAY_MS,
) {
    var visible = false
        private set

    private var pending: Cancellable? = null

    // 취소가 늦어 이미 대기열에 들어간 작업이 새 읽기의 스피너를 켜지 못하게 한다
    private var token = 0

    /** 이미 기다리는 중이거나 보이는 중이면 아무것도 하지 않아, 겹친 읽기도 처음 시작 시각부터 잰다. */
    fun start() {
        if (pending != null || visible) return
        val mine = ++token
        pending = scheduler.schedule(delayMs) {
            if (mine == token) {
                pending = null
                setVisible(true)
            }
        }
    }

    fun stop() {
        token++
        pending?.cancel()
        pending = null
        setVisible(false)
    }

    private fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        onVisibleChanged(value)
    }

    companion object {
        const val DEFAULT_DELAY_MS = 300L
    }
}
