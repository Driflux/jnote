package io.github.driflux.jnote.relocate

import kotlin.math.abs

internal object ExactMatcher {
    /** 취소되면 null을 돌려준다. */
    fun find(
        document: NormalizedLines,
        snapshot: NormalizedLines,
        originalStartLine: Int,
        isCancelled: () -> Boolean = { false },
    ): RelocationResult.Match? {
        val m = snapshot.size
        if (m == 0 || m > document.size) return null

        var bestStart = -1
        var bestDistance = Int.MAX_VALUE
        for (i in 0..document.size - m) {
            if (isCancelled()) return null
            if (!matchesAt(document, snapshot, i)) continue
            val start = document.lineNumbers[i]
            val distance = abs(start - originalStartLine)
            // 앞에서부터 훑으므로 거리가 같으면 먼저 찾은(앞쪽) 것을 유지한다
            if (distance < bestDistance) {
                bestDistance = distance
                bestStart = i
            }
        }
        if (bestStart < 0) return null
        return RelocationResult.Match(document.lineNumbers[bestStart], document.lineNumbers[bestStart + m - 1])
    }

    private fun matchesAt(document: NormalizedLines, snapshot: NormalizedLines, offset: Int): Boolean {
        for (k in 0 until snapshot.size) {
            if (document.lines[offset + k] != snapshot.lines[k]) return false
        }
        return true
    }
}
