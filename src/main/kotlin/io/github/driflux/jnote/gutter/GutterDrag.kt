package io.github.driflux.jnote.gutter

import io.github.driflux.jnote.anchor.LineRange

internal class GutterDrag {
    private class Pressed(val line: Int, val onEnd: () -> Unit)

    private var pressed: Pressed? = null

    val active: Boolean get() = pressed != null

    fun press(line: Int, onEnd: () -> Unit) {
        cancel()
        pressed = Pressed(line, onEnd)
    }

    fun drag(line: Int): LineRange? {
        val from = pressed ?: return null
        return rangeOf(from.line, line)
    }

    fun release(line: Int): LineRange? {
        val from = pressed ?: return null
        cancel()
        return rangeOf(from.line, line)
    }

    fun cancel() {
        val ended = pressed ?: return
        pressed = null
        ended.onEnd()
    }

    private fun rangeOf(from: Int, to: Int): LineRange? =
        if (from == to) null else LineRange(minOf(from, to), maxOf(from, to))
}