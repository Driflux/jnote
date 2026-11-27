package io.github.driflux.jnote.gutter

import io.github.driflux.jnote.anchor.LineRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GutterDragTest {
    @Test
    fun draggingDownGivesTheLinesFromPressToRelease() {
        val drag = GutterDrag()
        drag.press(2) {}
        assertEquals(LineRange(2, 3), drag.drag(3))
        assertEquals(LineRange(2, 5), drag.drag(5))
        assertEquals(LineRange(2, 5), drag.release(5))
    }

    @Test
    fun draggingUpIsOrderedFromTheSmallerLine() {
        val drag = GutterDrag()
        drag.press(8) {}
        assertEquals(LineRange(4, 8), drag.release(4))
    }

    @Test
    fun releasingOnTheSameLineIsAClickNotARange() {
        val drag = GutterDrag()
        drag.press(3) {}
        assertNull(drag.drag(3))
        assertNull(drag.release(3))
    }

    @Test
    fun draggingBackToTheStartLineLeavesNoRange() {
        val drag = GutterDrag()
        drag.press(3) {}
        drag.drag(6)
        assertNull(drag.release(3))
    }

    @Test
    fun withoutAPressNothingHappens() {
        val drag = GutterDrag()
        assertFalse(drag.active)
        assertNull(drag.drag(4))
        assertNull(drag.release(4))
    }

    @Test
    fun releaseAndCancelEndTheDrag() {
        val drag = GutterDrag()
        drag.press(1) {}
        assertTrue(drag.active)
        drag.release(2)
        assertFalse(drag.active)
        drag.press(1) {}
        drag.cancel()
        assertFalse(drag.active)
        assertNull(drag.release(4))
    }

    @Test
    fun releaseOrCancelRunsTheEndCallbackOnce() {
        val drag = GutterDrag()
        var ended = 0
        drag.press(1) { ended++ }
        drag.drag(3)
        assertEquals(0, ended)
        drag.release(3)
        drag.release(3)
        assertEquals(1, ended)

        drag.press(1) { ended++ }
        drag.cancel()
        drag.cancel()
        assertEquals(2, ended)
    }

    @Test
    fun aNewPressEndsThePreviousDrag() {
        val drag = GutterDrag()
        val ended = mutableListOf<Int>()
        drag.press(1) { ended += 1 }
        drag.press(5) { ended += 5 }
        assertEquals(listOf(1), ended)
        assertEquals(LineRange(5, 7), drag.release(7))
        assertEquals(listOf(1, 5), ended)
    }
}