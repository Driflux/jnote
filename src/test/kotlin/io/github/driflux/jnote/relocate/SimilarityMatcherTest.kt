package io.github.driflux.jnote.relocate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

class SimilarityMatcherTest {
    @Test
    fun matchesStraightforwardReferenceOnRandomInputs() {
        val thresholds = listOf(0.0, 0.3, 0.5, 0.6, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1.0)
        var found = 0
        repeat(4000) { seed ->
            val random = Random(seed)
            val pool = List(random.nextInt(2, 12)) { randomLine(random) }
            val document = List(random.nextInt(1, 60)) {
                when (random.nextInt(10)) {
                    0 -> "   "
                    in 1..4 -> pool[random.nextInt(pool.size)]
                    else -> randomLine(random)
                }
            }
            val snapshot = if (random.nextInt(5) == 0) {
                List(random.nextInt(1, 6)) { randomLine(random) }
            } else {
                val start = random.nextInt(document.size)
                document.subList(start, minOf(document.size, start + random.nextInt(1, 9))).map { mutate(it, random) }
            }
            val threshold = thresholds[random.nextInt(thresholds.size)]
            val original = random.nextInt(1, document.size + 5)
            val doc = Normalizer.normalize(document)
            val snap = Normalizer.normalize(snapshot)
            val expected = reference(doc, snap, original, threshold)
            assertEquals("seed=$seed", expected, SimilarityMatcher(threshold).find(doc, snap, original))
            if (expected != null) found++
        }
        // 생성기가 찾는 경우와 못 찾는 경우를 모두 충분히 만드는지
        assertTrue("found=$found", found in 500..3500)
    }

    @Test
    fun cancellationIsCheckedInsideOneLongWindow() {
        val snapshot = (0 until 200).map { "val value$it = compute($it)" }
        // 문서와 스냅샷 크기가 같아 구간이 하나뿐이고, 모든 줄이 비슷해서 끝까지 비교해야 한다
        val doc = Normalizer.normalize(snapshot.map { "$it;" })
        val snap = Normalizer.normalize(snapshot)
        assertNotNull(SimilarityMatcher(0.8).find(doc, snap, 1))
        var polls = 0
        assertNull(SimilarityMatcher(0.8) { ++polls > 1 }.find(doc, snap, 1))
    }

    private fun randomLine(random: Random): String {
        val alphabet = "aab c(=)1"
        return String(CharArray(random.nextInt(1, 14)) { alphabet[random.nextInt(alphabet.length)] })
    }

    private fun mutate(line: String, random: Random): String {
        val sb = StringBuilder(line)
        repeat(random.nextInt(0, 4)) {
            val at = random.nextInt(sb.length + 1)
            when (random.nextInt(3)) {
                0 -> sb.insert(at, 'x')
                1 -> if (at < sb.length) sb.deleteCharAt(at)
                else -> if (at < sb.length) sb.setCharAt(at, 'y')
            }
        }
        return sb.toString()
    }

    /** 경계나 가지치기 없이 모든 구간과 모든 줄을 끝까지 계산하는 기준 구현 */
    private fun reference(
        doc: NormalizedLines,
        snap: NormalizedLines,
        original: Int,
        threshold: Double,
    ): RelocationResult.Approx? {
        val m = snap.size
        if (m == 0 || m > doc.size) return null
        var bestIndex = -1
        var bestMean = 0.0
        var bestDistance = Int.MAX_VALUE
        for (i in 0..doc.size - m) {
            var sum = 0.0
            var belowFloor = false
            for (k in 0 until m) {
                val a = doc.lines[i + k]
                val b = snap.lines[k]
                val similarity = if (a == b) 1.0 else 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
                if (similarity < 0.5 - 1e-9) belowFloor = true
                sum += similarity
            }
            val mean = sum / m
            if (belowFloor || mean < threshold - 1e-9) continue
            val distance = abs(doc.lineNumbers[i] - original)
            if (bestIndex < 0 || mean > bestMean + 1e-9 || (mean >= bestMean - 1e-9 && distance < bestDistance)) {
                bestIndex = i
                bestMean = mean
                bestDistance = distance
            }
        }
        if (bestIndex < 0) return null
        return RelocationResult.Approx(doc.lineNumbers[bestIndex], doc.lineNumbers[bestIndex + m - 1], bestMean)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j - 1] + cost, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
