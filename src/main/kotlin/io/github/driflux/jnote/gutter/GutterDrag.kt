package io.github.driflux.jnote.gutter

import io.github.driflux.jnote.anchor.LineRange

/**
 * 거터 "+" 위에서 시작한 드래그 하나의 상태. 줄은 0-based이고 Swing을 몰라서 단위 테스트로 검증한다.
 * 같은 줄에서 끝난 누르기는 범위가 아니라 클릭이므로 null이고, 클릭은 거터가 렌더러의 클릭 동작으로 처리한다.
 */
internal class GutterDrag {
    private var start: Int? = null

    val active: Boolean get() = start != null

    fun press(line: Int) {
        start = line
    }

    /** 드래그 중 지금까지 훑은 범위(끝 줄 포함). 시작 줄과 같으면 아직 범위가 아니므로 null. */
    fun drag(line: Int): LineRange? {
        val from = start ?: return null
        return rangeOf(from, line)
    }

    /** 놓았을 때의 범위. 드래그 상태는 항상 끝난다. */
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
