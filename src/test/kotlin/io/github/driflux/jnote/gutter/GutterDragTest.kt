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
        drag.press(2)
        assertEquals(LineRange(2, 3), drag.drag(3))
        assertEquals(LineRange(2, 5), drag.drag(5))
        assertEquals(LineRange(2, 5), drag.release(5))
    }

    @Test
    fun draggingUpIsOrderedFromTheSmallerLine() {
        val drag = GutterDrag()
        drag.press(8)
        assertEquals(LineRange(4, 8), drag.release(4))
    }

    @Test
    fun releasingOnTheSameLineIsAClickNotARange() {
        val drag = GutterDrag()
        drag.press(3)
        assertNull(drag.drag(3))
        assertNull(drag.release(3))
    }

    @Test
    fun draggingBackToTheStartLineLeavesNoRange() {
        val drag = GutterDrag()
        drag.press(3)
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
        drag.press(1)
        assertTrue(drag.active)
        drag.release(2)
        assertFalse(drag.active)
        drag.press(1)
        drag.cancel()
        assertFalse(drag.active)
        assertNull(drag.release(4))
    }
}