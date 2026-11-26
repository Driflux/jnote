package io.github.driflux.jnote.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorLinesTest {
    // 인자는 0-based 줄과 열: (시작 줄, 시작 열, 끝 줄, 끝 열). 결과는 1-based
    @Test fun singleLineSelection() = assertEquals(LineRange(4, 4), AnchorLines.ofSelection(3, 2, 3, 8))

    @Test fun multiLineSelection() = assertEquals(LineRange(4, 7), AnchorLines.ofSelection(3, 2, 6, 5))

    @Test fun selectionEndingAtColumnZeroExcludesThatLine() =
        assertEquals(LineRange(4, 6), AnchorLines.ofSelection(3, 0, 6, 0))

    @Test fun wholeSingleLineSelectedWithTrailingNewline() =
        assertEquals(LineRange(4, 4), AnchorLines.ofSelection(3, 0, 4, 0))

    @Test fun reversedSelectionIsOrdered() = assertEquals(LineRange(4, 7), AnchorLines.ofSelection(6, 5, 3, 2))

    @Test fun reversedSelectionEndingAtColumnZero() = assertEquals(LineRange(4, 6), AnchorLines.ofSelection(6, 0, 3, 0))

    @Test fun noSelectionUsesCaretLine() = assertEquals(LineRange(10, 10), AnchorLines.ofSelection(9, 4, 9, 4))

    @Test fun caretAtColumnZeroWithoutSelectionStaysOnItsLine() =
        assertEquals(LineRange(1, 1), AnchorLines.ofSelection(0, 0, 0, 0))

    @Test fun relativePathUsesForwardSlashes() {
        assertEquals("src/a/B.kt", AnchorLines.relativePath("/work/proj", "/work/proj/src/a/B.kt"))
        assertEquals("src/a/B.kt", AnchorLines.relativePath("/work/proj/", "/work/proj/src/a/B.kt"))
        assertEquals("src/B.kt", AnchorLines.relativePath("C:\\work\\proj", "C:\\work\\proj\\src\\B.kt"))
    }

    @Test fun fileOutsideProjectIsNotEligible() {
        assertNull(AnchorLines.relativePath("/work/proj", "/work/other/B.kt"))
        // 이름만 접두사가 같은 형제 폴더를 안쪽으로 착각하지 않는다
        assertNull(AnchorLines.relativePath("/work/proj", "/work/project/B.kt"))
        assertNull(AnchorLines.relativePath("/work/proj", "/work/proj"))
        assertNull(AnchorLines.relativePath(null, "/work/proj/B.kt"))
    }

    @Test fun smallSnapshotIsKept() = assertEquals("a\nb", AnchorLines.capSnapshot("a\nb"))

    @Test fun snapshotAtLimitIsKept() {
        val text = "x".repeat(AnchorLines.MAX_SNAPSHOT_CHARS)
        assertEquals(text, AnchorLines.capSnapshot(text))
    }

    @Test fun hugeSnapshotIsCutAtLastLineBoundary() {
        val line = "y".repeat(99)
        val text = (1..1000).joinToString("\n") { line }
        val capped = AnchorLines.capSnapshot(text)
        assertTrue(capped.length <= AnchorLines.MAX_SNAPSHOT_CHARS)
        assertTrue(text.startsWith(capped))
        assertTrue(capped.split("\n").all { it == line })
    }

    @Test fun hugeSingleLineIsCutByChars() {
        val capped = AnchorLines.capSnapshot("z".repeat(AnchorLines.MAX_SNAPSHOT_CHARS + 10))
        assertEquals(AnchorLines.MAX_SNAPSHOT_CHARS, capped.length)
    }
}
