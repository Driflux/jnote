package io.github.driflux.jnote.relocate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExactMatcherTest {
    private fun find(
        doc: List<String>,
        snap: List<String>,
        original: Int,
        isCancelled: () -> Boolean = { false },
    ) = ExactMatcher.find(Normalizer.normalize(doc), Normalizer.normalize(snap), original, isCancelled)

    private val code = listOf(
        "class A {",
        "    fun a() {",
        "        println(1)",
        "    }",
        "}",
    )

    @Test
    fun normalizationTreatsTabsIndentAndTrailingSpacesAsEqual() {
        assertEquals("a b c", Normalizer.normalizeLine("\t a  \t b   c  \t"))
        assertEquals("", Normalizer.normalizeLine("  \t "))
        val reindented = listOf("x = 1", "\t\tfoo(  a,\tb )   ", "y")
        val found = find(reindented, listOf("foo( a, b )"), 1)
        assertEquals(RelocationResult.Match(2, 2), found)
    }

    @Test
    fun matchAtUnchangedPosition() {
        assertEquals(RelocationResult.Match(2, 4), find(code, code.subList(1, 4), 2))
    }

    @Test
    fun matchAfterShiftDownAndUp() {
        val snap = code.subList(1, 4)
        val shiftedDown = List(10) { "// pad" } + code
        assertEquals(RelocationResult.Match(12, 14), find(shiftedDown, snap, 2))
        val shiftedUp = code.drop(1)
        assertEquals(RelocationResult.Match(1, 3), find(shiftedUp, snap, 2))
    }

    @Test
    fun duplicatesChooseNearestToOriginalLine() {
        val doc = listOf("dup", "x", "x", "x", "dup", "x", "x", "x", "x", "dup")
        assertEquals(RelocationResult.Match(1, 1), find(doc, listOf("dup"), 2))
        assertEquals(RelocationResult.Match(5, 5), find(doc, listOf("dup"), 6))
        assertEquals(RelocationResult.Match(10, 10), find(doc, listOf("dup"), 40))
    }

    @Test
    fun tieChoosesEarlierLine() {
        val doc = listOf("dup", "x", "x", "x", "dup")
        // 시작 줄 3은 1과 5에서 거리가 같다
        assertEquals(RelocationResult.Match(1, 1), find(doc, listOf("dup"), 3))
    }

    @Test
    fun multiLineSnapshotAtFileStartAndEnd() {
        assertEquals(RelocationResult.Match(1, 2), find(code, code.subList(0, 2), 1))
        assertEquals(RelocationResult.Match(4, 5), find(code, code.subList(3, 5), 4))
        assertEquals(RelocationResult.Match(1, 5), find(code, code, 1))
    }

    @Test
    fun blankLinesInsideSnapshotAreIgnored() {
        val doc = listOf("a", "", "b", "c")
        assertEquals(RelocationResult.Match(1, 3), find(doc, listOf("a", "b"), 1))
        assertEquals(RelocationResult.Match(1, 3), find(doc, listOf("a", "", "", "b"), 1))
    }

    @Test
    fun emptyOrOversizeSnapshotFindsNothing() {
        assertNull(find(code, emptyList(), 1))
        assertNull(find(code, listOf("", "  "), 1))
        assertNull(find(code.subList(0, 2), code, 1))
    }

    @Test
    fun differentContentFindsNothing() {
        assertNull(find(code, listOf("class B {"), 1))
    }

    @Test
    fun cancelledSearchFindsNothing() {
        var checks = 0
        val doc = List(1000) { "line" } + listOf("target")
        assertNull(find(doc, listOf("target"), 1) { ++checks > 3 })
    }
}
