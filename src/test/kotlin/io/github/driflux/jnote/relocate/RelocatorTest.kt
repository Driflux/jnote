package io.github.driflux.jnote.relocate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RelocatorTest {
    private fun locate(
        doc: List<String>,
        snap: List<String>,
        original: Int,
        options: RelocationOptions = RelocationOptions(),
    ) = Relocator.locate(doc, snap, original, options)

    private val snippet = listOf(
        "fun compute(total: Int): Int {",
        "    val doubled = total * 2",
        "    return doubled + 1",
        "}",
    )

    private fun filler(n: Int, tag: String = "pad") = List(n) { "// $tag $it unrelated filler text" }

    @Test
    fun exactMatchWinsOverSimilarity() {
        val doc = filler(5) + snippet + filler(5)
        assertEquals(RelocationResult.Match(6, 9), locate(doc, snippet, 1))
    }

    @Test
    fun emptyAndOversizeSnapshotReturnNone() {
        assertEquals(RelocationResult.None, locate(snippet, emptyList(), 1))
        assertEquals(RelocationResult.None, locate(snippet, snippet + "extra", 1))
    }

    @Test
    fun singleLineSnapshotApprox() {
        val doc = listOf("a", "val result = computeTotal(items)", "b")
        val r = locate(doc, listOf("val result = computeTotals(items)"), 2)
        r as RelocationResult.Approx
        assertEquals(2, r.startLine)
        assertEquals(2, r.endLine)
        assertTrue(r.similarity > 0.9 && r.similarity < 1.0)
    }

    @Test
    fun partialEditIsApproxWithSimilarityInRange() {
        val edited = listOf(
            "fun compute(total: Int): Int {",
            "    val tripled = total * 3",
            "    return tripled + 1",
            "}",
        )
        val doc = filler(10) + edited + filler(10)
        val r = locate(doc, snippet, 11) as RelocationResult.Approx
        assertEquals(11, r.startLine)
        assertEquals(14, r.endLine)
        assertTrue("similarity=${r.similarity}", r.similarity >= 0.8 && r.similarity < 1.0)
    }

    @Test
    fun unrelatedBlockReturnsNone() {
        val doc = filler(20) + listOf("import java.io.File", "class Other", "object X", "enum class E")
        assertEquals(RelocationResult.None, locate(doc, snippet, 3))
    }

    @Test
    fun thresholdBoundary() {
        val snap = listOf("abcdefghijklmnopqrst")
        val doc = listOf("abcdefghijklmnopqrsX") // 유사도 0.95
        val at = locate(doc, snap, 1, RelocationOptions(similarityThreshold = 0.95))
        assertTrue(at is RelocationResult.Approx)
        assertEquals(0.95, (at as RelocationResult.Approx).similarity, 1e-9)
        assertEquals(
            RelocationResult.None,
            locate(doc, snap, 1, RelocationOptions(similarityThreshold = 0.951)),
        )
        assertTrue(locate(doc, snap, 1, RelocationOptions(similarityThreshold = 0.94)) is RelocationResult.Approx)
    }

    @Test
    fun defaultThresholdIsEightyPercent() {
        val snap = listOf("abcdefghij")
        // 2자 차이: 0.8 이상이라 통과, 3자 차이: 0.7이라 탈락
        assertTrue(locate(listOf("abcdefghXX"), snap, 1) is RelocationResult.Approx)
        assertEquals(RelocationResult.None, locate(listOf("abcdefgXXX"), snap, 1))
    }

    private val tenLines = (1..10).map { "statement number $it;" }

    @Test
    fun lineAboveFloorIsAcceptedWhenMeanIsHigh() {
        val snap = tenLines.dropLast(1) + "abcdefghij"
        val doc = tenLines.dropLast(1) + "abcdefXXXX" // 마지막 줄 유사도 0.6
        val r = locate(doc, snap, 1) as RelocationResult.Approx
        assertEquals(0.96, r.similarity, 1e-9)
    }

    @Test
    fun lineBelowFloorRejectsWindowEvenWhenMeanIsHigh() {
        val snap = tenLines.dropLast(1) + "abcdefghij"
        val doc = tenLines.dropLast(1) + "abcdXXXXXX" // 마지막 줄 유사도 0.4, 평균은 0.94
        assertEquals(RelocationResult.None, locate(doc, snap, 1))
    }

    @Test
    fun approxTiesChooseNearestThenEarlier() {
        val snap = listOf("alpha beta gamma z")
        val doc = listOf("alpha beta gamma a") + filler(4) + listOf("alpha beta gamma b") + filler(4) +
            listOf("alpha beta gamma c")
        assertEquals(1, (locate(doc, snap, 1) as RelocationResult.Approx).startLine)
        assertEquals(6, (locate(doc, snap, 7) as RelocationResult.Approx).startLine)
        assertEquals(11, (locate(doc, snap, 40) as RelocationResult.Approx).startLine)
        // 1과 7이 시작 줄 4에서 거리가 같으면 앞쪽
        val tie = listOf("alpha beta gamma a") + filler(5) + listOf("alpha beta gamma c")
        assertEquals(1, (locate(tie, snap, 4) as RelocationResult.Approx).startLine)
    }

    @Test
    fun bestSimilarityBeatsNearerButWorseWindow() {
        val snap = listOf("alpha beta gamma delta")
        val doc = listOf("alpha beta gamma delte") + filler(3) + listOf("alpha beta gamma dxlta?") // 앞쪽이 더 비슷하다
        val r = locate(doc, snap, 5) as RelocationResult.Approx
        assertEquals(1, r.startLine)
    }

    /** isCancelled를 몇 번 확인하는지 센다. 취소는 하지 않는다 */
    private fun pollCount(run: (() -> Boolean) -> Unit): Int {
        var polls = 0
        run { polls++; false }
        return polls
    }

    @Test
    fun cancellationReturnsNonePromptly() {
        val doc = (0 until 5000).map { "val v$it = compute($it, ${it * 7}) // note" }
        val snap = doc.subList(4990, 4995).map { "$it;" }
        assertTrue(locate(doc, snap, 1) is RelocationResult.Approx)
        val exactPolls = pollCount { ExactMatcher.find(Normalizer.normalize(doc), Normalizer.normalize(snap), 1, it) }
        val fullPolls = pollCount { locate(doc, snap, 1, RelocationOptions(isCancelled = it)) }
        // 정확 일치 검색이 끝난 뒤, 유사도 검색이 아직 많이 남았을 때 취소한다
        val cancelAt = exactPolls + 5
        assertTrue("fullPolls=$fullPolls", fullPolls > cancelAt + 1000)
        var polls = 0
        val result = locate(doc, snap, 1, RelocationOptions(isCancelled = { ++polls >= cancelAt }))
        assertEquals(RelocationResult.None, result)
        assertEquals(cancelAt, polls)
    }

    @Test
    fun cancelledRunReturnsNoneEvenAfterFindingApproxCandidate() {
        val edited = listOf(
            "fun compute(total: Int): Int {",
            "    val tripled = total * 3",
            "    return tripled + 1",
            "}",
        )
        val doc = edited + filler(3000)
        assertTrue(locate(doc, snippet, 1) is RelocationResult.Approx)
        // 마지막 확인에서야 취소한다. 맨 앞의 근사 후보는 그 전에 이미 찾았다
        val lastPoll = pollCount { locate(doc, snippet, 1, RelocationOptions(isCancelled = it)) }
        var polls = 0
        val result = locate(doc, snippet, 1, RelocationOptions(isCancelled = { ++polls >= lastPoll }))
        assertEquals(RelocationResult.None, result)
    }

    @Test
    fun cancelledBeforeAnythingReturnsNoneEvenIfMatchExists() {
        val result = locate(snippet, snippet, 1, RelocationOptions(isCancelled = { true }))
        assertEquals(RelocationResult.None, result)
    }

    @Test
    fun largeTemplatedDocumentStaysWithinDpBudget() {
        val doc = (0 until 5000).map { "    val value$it = compute($it, ${it * 7}) // generated line" }
        val snap = (0 until 200).map { "    val valueX$it = compute($it, ${it * 5}) // generated lines" }
        val result = locate(doc, snap, 2500) as RelocationResult.Approx
        assertEquals(1, result.startLine)
        assertEquals(200, result.endLine)
        // 모든 줄이 서로 비슷한 템플릿 코드라도 DP는 문서 줄 수 이내다 (작업량 제한 전에는 417,304번)
        val matcher = SimilarityMatcher(RelocationOptions().similarityThreshold)
        matcher.find(Normalizer.normalize(doc), Normalizer.normalize(snap), 2500)
        assertTrue("dpCalls=${matcher.dpCalls}", matcher.dpCalls <= doc.size)
    }

    @Test
    fun similarityThresholdOutsideZeroToOneIsRejected() {
        for (threshold in listOf(-0.01, 1.01, Double.NaN)) {
            assertThrows(IllegalArgumentException::class.java) { RelocationOptions(similarityThreshold = threshold) }
        }
        RelocationOptions(similarityThreshold = 0.0)
        RelocationOptions(similarityThreshold = 1.0)
    }
}
