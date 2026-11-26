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

        val scorer = WindowScorer(document, snapshot)
        var bestIndex = -1
        var bestMean = 0.0
        var bestDistance = Int.MAX_VALUE
        for (i in 0..document.size - m) {
            if (isCancelled()) return null
            // 이미 찾은 최선보다 낮을 수 없는 구간은 일찍 버린다 (동점은 거리 비교를 위해 통과)
            val need = if (bestIndex >= 0) max(threshold, bestMean) else threshold
            val mean = scorer.windowMean(i, need)
            if (mean == CANCELLED) return null
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

    /** 검색 한 번 동안 유지하는 상태: 줄별 문자 히스토그램, 반복되는 줄 쌍의 거리 메모, 구간의 줄별 상한 */
    private inner class WindowScorer(
        private val document: NormalizedLines,
        private val snapshot: NormalizedLines,
    ) {
        private val docHistograms = arrayOfNulls<IntArray>(document.size)
        private val snapHistograms = Array(snapshot.size) { histogram(snapshot.lines[it]) }
        private val docIds = contentIds(document.lines)
        private val snapIds = contentIds(snapshot.lines)
        private val docRepeated = repeated(docIds)
        private val snapRepeated = repeated(snapIds)
        private val memo = HashMap<Long, Int>()
        private val upper = DoubleArray(snapshot.size)
        private val lower = IntArray(snapshot.size)

        /** 구간의 줄 유사도 평균. need에 도달할 수 없으면 REJECTED, 취소되면 CANCELLED */
        fun windowMean(offset: Int, need: Double): Double {
            val m = snapshot.size
            // 원래 판정(평균 >= need - EPS)보다 MARGIN만큼 낮게 잡아서, 상한으로 버린 구간은 끝까지 계산해도 버려지게 한다
            val goal = (need - EPS) * m - MARGIN
            var upperSum = 0.0
            for (k in 0 until m) {
                if (cancelledAt(k)) return CANCELLED
                if (!bound(offset + k, k)) return REJECTED
                upperSum += upper[k]
                if (upperSum + (m - k - 1) < goal) return REJECTED
            }
            var sum = 0.0
            var upperRest = upperSum
            for (k in 0 until m) {
                if (cancelledAt(k)) return CANCELLED
                upperRest -= upper[k]
                val remaining = m - k - 1
                // 남은 줄이 모두 1.0이라는 가정과 남은 줄의 상한 합 가운데 더 엄격한 쪽으로 이 줄의 최소 유사도를 정한다
                val minSimilarity = maxOf(LINE_FLOOR, need * m - sum - remaining - EPS, goal - sum - upperRest)
                val similarity = lineSimilarity(offset + k, k, minSimilarity)
                if (similarity < 0) return REJECTED
                sum += similarity
            }
            val mean = sum / m
            return if (mean >= need - EPS) mean else REJECTED
        }

        private fun cancelledAt(k: Int) = k % CANCEL_CHECK_LINES == CANCEL_CHECK_LINES - 1 && isCancelled()

        /** 줄 쌍의 거리 하한(lower)과 유사도 상한(upper)을 채운다. 하한만으로 LINE_FLOOR 미만이면 false */
        private fun bound(j: Int, k: Int): Boolean {
            val a = document.lines[j]
            val b = snapshot.lines[k]
            if (a == b) {
                lower[k] = 0
                upper[k] = 1.0
                return true
            }
            val maxLen = max(a.length, b.length)
            val floorDistance = allowedDistance(LINE_FLOOR, maxLen)
            if (abs(a.length - b.length) > floorDistance) return false
            val docHistogram = docHistograms[j] ?: histogram(a).also { docHistograms[j] = it }
            val lowerBound = histogramDistance(docHistogram, snapHistograms[k])
            if (lowerBound > floorDistance) return false
            lower[k] = lowerBound
            upper[k] = 1.0 - lowerBound.toDouble() / maxLen
            return true
        }

        /** 1 - distance / max(len). minSimilarity에 못 미치면 -1 */
        private fun lineSimilarity(j: Int, k: Int, minSimilarity: Double): Double {
            val a = document.lines[j]
            val b = snapshot.lines[k]
            if (a == b) return 1.0
            val maxLen = max(a.length, b.length)
            val maxDistance = allowedDistance(minSimilarity, maxLen)
            // 하한이 이미 한도를 넘으면 DP 없이 탈락 (길이 차이도 하한에 포함된다)
            if (maxDistance < 0 || lower[k] > maxDistance) return -1.0
            val distance = distance(j, k, maxDistance)
            if (distance > maxDistance) return -1.0
            return 1.0 - distance.toDouble() / maxLen
        }

        /** 같은 내용의 줄 쌍이 다시 나올 수 있을 때만 메모한다. 음수는 "거리가 -값 이상"이라는 하한이다 */
        private fun distance(j: Int, k: Int, maxDistance: Int): Int {
            if (!docRepeated[j] && !snapRepeated[k]) return boundedDistance(document.lines[j], snapshot.lines[k], maxDistance)
            val key = (docIds[j].toLong() shl 32) or snapIds[k].toLong()
            val known = memo[key]
            if (known != null && (known >= 0 || -known > maxDistance)) return if (known >= 0) known else -known
            val distance = boundedDistance(document.lines[j], snapshot.lines[k], maxDistance)
            if (memo.size < MEMO_LIMIT) memo[key] = if (distance <= maxDistance) distance else -distance
            return distance
        }
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
        const val MARGIN = 1e-6
        const val CANCEL_CHECK_LINES = 16
        const val MEMO_LIMIT = 1 shl 16
        const val REJECTED = -1.0
        const val CANCELLED = -2.0

        /** minSimilarity를 지키면서 허용되는 최대 편집 거리 */
        fun allowedDistance(minSimilarity: Double, maxLen: Int): Int = floor((1.0 - minSimilarity) * maxLen + EPS).toInt()

        /** 내용이 같은 줄에 같은 id를 준다 */
        fun contentIds(lines: List<String>): IntArray {
            val ids = HashMap<String, Int>()
            return IntArray(lines.size) { ids.getOrPut(lines[it]) { ids.size } }
        }

        /** 줄마다 같은 내용이 두 번 이상 나오는지 */
        fun repeated(ids: IntArray): BooleanArray {
            val counts = IntArray(ids.size)
            for (id in ids) counts[id]++
            return BooleanArray(ids.size) { counts[ids[it]] > 1 }
        }

        /** 정렬된 (문자 shl 16) or 개수 목록 */
        fun histogram(line: String): IntArray {
            val chars = line.toCharArray()
            chars.sort()
            val packed = IntArray(chars.size)
            var size = 0
            var i = 0
            while (i < chars.size) {
                var j = i
                while (j < chars.size && chars[j] == chars[i]) j++
                packed[size++] = (chars[i].code shl 16) or minOf(j - i, 0xFFFF)
                i = j
            }
            return packed.copyOf(size)
        }

        /**
         * 편집 한 번은 x에만 있는 문자 수와 y에만 있는 문자 수를 각각 최대 1만큼 줄이므로,
         * 둘 중 큰 값이 Levenshtein 거리의 하한이다.
         */
        fun histogramDistance(x: IntArray, y: IntArray): Int {
            var i = 0
            var j = 0
            var onlyX = 0
            var onlyY = 0
            while (i < x.size && j < y.size) {
                val cx = x[i] ushr 16
                val cy = y[j] ushr 16
                when {
                    cx == cy -> {
                        val diff = (x[i] and 0xFFFF) - (y[j] and 0xFFFF)
                        if (diff > 0) onlyX += diff else onlyY -= diff
                        i++
                        j++
                    }
                    cx < cy -> onlyX += x[i++] and 0xFFFF
                    else -> onlyY += y[j++] and 0xFFFF
                }
            }
            while (i < x.size) onlyX += x[i++] and 0xFFFF
            while (j < y.size) onlyY += y[j++] and 0xFFFF
            return max(onlyX, onlyY)
        }
    }
}
