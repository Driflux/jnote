package io.github.driflux.jnote.gutter

import io.github.driflux.jnote.anchor.LineRange

internal class GutterDrag {
    private var start: Int? = null

    val active: Boolean get() = start != null

    fun press(line: Int) {
        start = line
    }

    fun drag(line: Int): LineRange? {
        val from = start ?: return null
        return rangeOf(from, line)
    }

    fun release(line: Int): LineRange? {
        val from = start ?: return null
        start = null
        return rangeOf(from, line)
    }

    fun cancel() {
        start = null
    }

    private fun rangeOf(from: Int, to: Int): LineRange? =
        if (from == to) null else LineRange(minOf(from, to), maxOf(from, to))
}