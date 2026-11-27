package io.github.driflux.jnote.gutter

import io.github.driflux.jnote.ui.Cancellable
import io.github.driflux.jnote.ui.DelayScheduler

internal interface PreviewHandle {
    val isOpen: Boolean

    fun close()
}

internal fun interface PreviewPresenter {
    /** 미리보기를 띄운다. 마우스가 미리보기에 들어가면 [onEntered], 나가면 [onExited]를 불러야 한다. 띄울 수 없으면 null. */
    fun show(notes: List<CachedNote>, anchorY: Int, onEntered: () -> Unit, onExited: () -> Unit): PreviewHandle?
}

/**
 * 거터 호버로 미리보기를 띄우고 닫는 시점만 정한다. EDT에서만 쓰고 Swing을 몰라서 가짜 스케줄러로 검증한다.
 * 줄이 바뀔 때만 일을 하므로 같은 줄 위의 이벤트는 비교 한 번이다. [notesAt]은 메모리 캐시만 읽는다.
 * 줄은 0-based이다.
 */
internal class GutterPreviewController(
    private val scheduler: DelayScheduler,
    private val notesAt: (Int) -> List<CachedNote>,
    private val presenter: PreviewPresenter,
    private val showDelayMs: Long = SHOW_DELAY_MS,
    private val closeDelayMs: Long = CLOSE_DELAY_MS,
) {
    private var line: Int? = null
    private var anchorY = 0
    private var handle: PreviewHandle? = null
    private var shownLine: Int? = null
    private var pendingShow: Cancellable? = null
    private var pendingClose: Cancellable? = null

    // 취소가 늦어 이미 대기열에 들어간 작업이 낡은 줄로 실행되지 않게 한다
    private var showToken = 0
    private var closeToken = 0

    internal val isShowing: Boolean get() = handle?.isOpen == true

    /** 마우스가 노트 영역 위의 [line]에 있으면 그 줄, 아니면 null. */
    fun hover(line: Int?, anchorY: Int) {
        if (line == this.line) {
            if (line != null) cancelClose()
            return
        }
        this.line = line
        this.anchorY = anchorY
        cancelShow()
        if (line != null && line == shownLine && handle?.isOpen == true) {
            cancelClose()
            return
        }
        // 다른 줄로 옮겨 가면 옛 미리보기는 잠시 뒤 닫히고, 새 줄에 노트가 있으면 그 뒤에 새로 뜬다
        scheduleClose()
        if (line != null && notesAt(line).isNotEmpty()) {
            val mine = ++showToken
            pendingShow = scheduler.schedule(showDelayMs) { if (mine == showToken) show() }
        }
    }

    fun dispose() {
        line = null
        cancelShow()
        cancelClose()
        closeNow()
    }

    private fun show() {
        pendingShow = null
        val target = line ?: return
        val notes = notesAt(target)
        if (notes.isEmpty()) return
        cancelClose()
        closeNow()
        handle = presenter.show(notes, anchorY, onEntered = ::cancelClose, onExited = ::scheduleClose)
        shownLine = target
    }

    private fun scheduleClose() {
        cancelClose()
        if (handle?.isOpen != true) return
        val mine = ++closeToken
        pendingClose = scheduler.schedule(closeDelayMs) { if (mine == closeToken) closeNow() }
    }

    private fun cancelClose() {
        closeToken++
        pendingClose?.cancel()
        pendingClose = null
    }

    private fun cancelShow() {
        showToken++
        pendingShow?.cancel()
        pendingShow = null
    }

    private fun closeNow() {
        pendingClose = null
        handle?.close()
        handle = null
        shownLine = null
    }

    companion object {
        const val SHOW_DELAY_MS = 400L

        // 마우스가 거터에서 미리보기로 건너가는 시간을 준다. 보이는 시간(400ms)보다 짧아야 옛 것이 먼저 닫힌다
        const val CLOSE_DELAY_MS = 300L
    }
}
