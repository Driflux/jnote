package io.github.driflux.jnote.gutter

import io.github.driflux.jnote.ui.Cancellable
import io.github.driflux.jnote.ui.DelayScheduler

internal interface PreviewHandle {
    val isOpen: Boolean

    fun close()
}

internal interface PreviewSource {
    fun notesAt(document: com.intellij.openapi.editor.Document, line: Int): List<CachedNote>

    fun database(): io.github.driflux.jnote.db.Database?

    object None : PreviewSource {
        override fun notesAt(document: com.intellij.openapi.editor.Document, line: Int): List<CachedNote> = emptyList()

        override fun database(): io.github.driflux.jnote.db.Database? = null
    }
}

internal fun interface PreviewPresenter {
    fun show(notes: List<CachedNote>, anchorY: Int, onEntered: () -> Unit, onExited: () -> Unit): PreviewHandle?
}

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

    private var showToken = 0
    private var closeToken = 0

    internal val isShowing: Boolean get() = handle?.isOpen == true

    fun hover(line: Int?, anchorY: Int) {
        if (line == this.line) {
            if (line != null && line == shownLine) cancelClose()
            return
        }
        this.line = line
        this.anchorY = anchorY
        cancelShow()
        if (line != null && line == shownLine && handle?.isOpen == true) {
            cancelClose()
            return
        }
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
        handle = presenter.show(notes, anchorY, onEntered = ::entered, onExited = ::scheduleClose)
        shownLine = target
    }

    private fun entered() {
        cancelShow()
        cancelClose()
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

        const val CLOSE_DELAY_MS = 300L
    }
}
