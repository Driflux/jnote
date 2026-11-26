package io.github.driflux.jnote.relocate

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * 줄별 Levenshtein 유사도로 스냅샷과 가장 비슷한 같은 크기의 구간을 찾는다.
 * DP 행 버퍼를 인스턴스에 두고 재사용하므로 스레드 간에 공유하면 안 된다.
 */
internal class SimilarityMatcher(
    private val threshold: Double,
    private val isCancelled: () -> Boolean = { false },
) {
    private var prev = IntArray(0)
    private var cur = IntArray(0)

    /** 평균이 threshold 이상이고 모든 줄이 LINE_FLOOR 이상인 최선의 구간. 없거나 취소되면 null */
    fun find(document: NormalizedLines, snapshot: NormalizedLines, originalStartLine: Int): RelocationResult.Approx? {
        val m = snapshot.size
        if (m == 0 || m > document.size) return null

        var bestIndex = -1
        var bestMean = 0.0
        var bestDistance = Int.MAX_VALUE
        for (i in 0..document.size - m) {
            if (isCancelled()) return null
            // 이미 찾은 최선보다 낮을 수 없는 구간은 일찍 버린다 (동점은 거리 비교를 위해 통과)
            val need = if (bestIndex >= 0) max(threshold, bestMean) else threshold
            val mean = windowMean(document, snapshot, i, need)
            if (mean < 0) continue
            val distance = abs(document.lineNumbers[i] - originalStartLine)
            val better = bestIndex < 0 || mean > bestMean + EPS ||
                (mean >= bestMean - EPS && distance < bestDistance)
            if (better) {
                bestIndex = i
                bestMean = mean
                bestDistance = distance
            }
        }
        if (bestIndex < 0) return null
        return RelocationResult.Approx(
            document.lineNumbers[bestIndex],
            document.lineNumbers[bestIndex + m - 1],
            bestMean,
        )
    }

    /** 구간의 줄 유사도 평균. need에 도달할 수 없으면 -1 */
    private fun windowMean(document: NormalizedLines, snapshot: NormalizedLines, offset: Int, need: Double): Double {
        val m = snapshot.size
        var sum = 0.0
        for (k in 0 until m) {
            val remaining = m - k - 1
            // 남은 줄이 모두 1.0이어도 평균이 need에 닿으려면 이 줄에 필요한 최소 유사도
            val minSimilarity = max(LINE_FLOOR, need * m - sum - remaining - EPS)
            val similarity = lineSimilarity(document.lines[offset + k], snapshot.lines[k], minSimilarity)
            if (similarity < 0) return -1.0
            sum += similarity
        }
        val mean = sum / m
        return if (mean >= need - EPS) mean else -1.0
    }

    /** 1 - distance / max(len). minSimilarity에 못 미치면 -1 */
    private fun lineSimilarity(a: String, b: String, minSimilarity: Double): Double {
        if (a == b) return 1.0
        val maxLen = max(a.length, b.length)
        val maxDistance = floor((1.0 - minSimilarity) * maxLen + EPS).toInt()
        if (maxDistance < 0) return -1.0
        // 길이 차이만으로 이미 한도를 넘으면 DP 없이 탈락
        if (abs(a.length - b.length) > maxDistance) return -1.0
        val distance = boundedDistance(a, b, maxDistance)
        if (distance > maxDistance) return -1.0
        return 1.0 - distance.toDouble() / maxLen
    }

    /** 대각선 폭 k로 제한한 Levenshtein 거리. k를 넘으면 k + 1을 돌려준다. */
    private fun boundedDistance(a: String, b: String, k: Int): Int {
        val la = a.length
        val lb = b.length
        val over = k + 1
        if (prev.size < lb + 2) {
            prev = IntArray(lb + 2)
            cur = IntArray(lb + 2)
        }
        var p = prev
        var c = cur
        for (j in 0..lb) p[j] = if (j <= k) j else over
        for (i in 1..la) {
            val lo = max(1, i - k)
            val hi = minOf(lb, i + k)
            c[lo - 1] = if (lo == 1 && i <= k) i else over
            var rowMin = c[lo - 1]
            val ca = a[i - 1]
            for (j in lo..hi) {
                val cost = if (ca == b[j - 1]) 0 else 1
                val v = minOf(p[j - 1] + cost, p[j] + 1, c[j - 1] + 1)
                c[j] = if (v > over) over else v
                if (c[j] < rowMin) rowMin = c[j]
            }
            // 다음 행이 읽을 수 있는 밴드 바깥 칸
            if (hi < lb) c[hi + 1] = over
            if (rowMin > k) return over
            val t = p
            p = c
            c = t
        }
        return minOf(p[lb], over)
    }

    private companion object {
        /** 어떤 줄도 이보다 낮으면 구간을 거부한다. 근거는 ADR 0012 */
        const val LINE_FLOOR = 0.5
        const val EPS = 1e-9
    }
}
