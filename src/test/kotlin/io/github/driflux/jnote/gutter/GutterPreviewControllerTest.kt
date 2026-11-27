package io.github.driflux.jnote.gutter

import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.ui.Cancellable
import io.github.driflux.jnote.ui.DelayScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GutterPreviewControllerTest {
    private class FakeScheduler : DelayScheduler {
        private class Task(val at: Long, val run: () -> Unit) { var cancelled = false }

        private val tasks = mutableListOf<Task>()
        var now = 0L

        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val t = Task(now + delayMs, task)
            tasks += t
            return Cancellable { t.cancelled = true }
        }

        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(next)
                now = next.at
                next.run()
            }
            now = end
        }
    }

    private class FakeHandle : PreviewHandle {
        override var isOpen = true

        override fun close() {
            isOpen = false
        }
    }

    private val scheduler = FakeScheduler()
    private val shown = mutableListOf<Pair<List<Long>, FakeHandle>>()
    private var enter: () -> Unit = {}
    private var exit: () -> Unit = {}
    private val notesByLine = mutableMapOf<Int, List<CachedNote>>()

    private fun note(id: Long) = CachedNote(id, "u$id", "t$id", "", emptyList(), null, NoteStatus.OK, 1, 1, "")

    private val controller = GutterPreviewController(
        scheduler,
        { notesByLine[it].orEmpty() },
        { notes, _, onEntered, onExited ->
            enter = onEntered
            exit = onExited
            FakeHandle().also { shown += notes.map { n -> n.id } to it }
        },
    )

    private fun open() = shown.filter { it.second.isOpen }

    @Test
    fun theFirstPreviewAppearsOnlyAfterTheShowDelay() {
        notesByLine[3] = listOf(note(1))

        controller.hover(3, 40)
        scheduler.advance(GutterPreviewController.SHOW_DELAY_MS - 1)
        assertTrue(shown.isEmpty())
        scheduler.advance(1)

        assertEquals(listOf(listOf(1L)), shown.map { it.first })
    }

    @Test
    fun aSecondEventOnALineWithoutNotesKeepsTheCloseTimer() {
        notesByLine[3] = listOf(note(1))

        controller.hover(3, 40)
        scheduler.advance(GutterPreviewController.SHOW_DELAY_MS)
        controller.hover(4, 50)
        controller.hover(4, 50)
        scheduler.advance(GutterPreviewController.CLOSE_DELAY_MS)

        assertTrue(open().isEmpty())
    }

    @Test
    fun aLineWithoutNotesNeverShowsAPreview() {
        controller.hover(2, 10)
        scheduler.advance(5_000)

        assertTrue(shown.isEmpty())
    }

    @Test
    fun leavingBeforeTheDelayCancelsThePreview() {
        notesByLine[3] = listOf(note(1))

        controller.hover(3, 40)
        scheduler.advance(200)
        controller.hover(null, 0)
        scheduler.advance(5_000)

        assertTrue(shown.isEmpty())
    }

    @Test
    fun leavingAnOpenPreviewClosesItAfterTheCloseDelay() {
        notesByLine[3] = listOf(note(1))
        controller.hover(3, 40)
        scheduler.advance(GutterPreviewController.SHOW_DELAY_MS)

        controller.hover(null, 0)
        scheduler.advance(GutterPreviewController.CLOSE_DELAY_MS - 1)
        assertEquals(1, open().size)
        scheduler.advance(1)

        assertTrue(open().isEmpty())
    }

    @Test
    fun enteringThePreviewKeepsItOpenAndLeavingItClosesIt() {
        notesByLine[3] = listOf(note(1))
        controller.hover(3, 40)
        scheduler.advance(GutterPreviewController.SHOW_DELAY_MS)

        controller.hover(null, 0)
        scheduler.advance(100)
        enter()
        scheduler.advance(5_000)
        assertEquals(1, open().size)

        exit()
        scheduler.advance(GutterPreviewController.CLOSE_DELAY_MS)
        assertTrue(open().isEmpty())
    }

    @Test
    fun comingBackToTheSameLineKeepsThePreview() {
        notesByLine[3] = listOf(note(1))
        controller.hover(3, 40)
        scheduler.advance(GutterPreviewController.SHOW_DELAY_MS)

        controller.hover(null, 0)
        scheduler.advance(100)
        controller.hover(3, 40)
        scheduler.advance(5_000)

        assertEquals(1, shown.size)
        assertEquals(1, open().size)
    }

    @Test
    fun movingToAnotherNoteLineReplacesThePreview() {
        notesByLine[3] = listOf(note(1))
        notesByLine[8] = listOf(note(2))
        controller.hover(3, 40)
        scheduler.advance(GutterPreviewController.SHOW_DELAY_MS)

        controller.hover(8, 120)
        scheduler.advance(GutterPreviewController.SHOW_DELAY_MS)

        assertEquals(listOf(listOf(1L), listOf(2L)), shown.map { it.first })
        assertEquals(listOf(listOf(2L)), open().map { it.first })
    }

    @Test
    fun sweepingAcrossLinesShowsOnlyTheLineTheMouseStopsOn() {
        for (line in 1..6) notesByLine[line] = listOf(note(line.toLong()))

        for (line in 1..6) {
            controller.hover(line, line * 16)
            scheduler.advance(50)
        }
        scheduler.advance(GutterPreviewController.SHOW_DELAY_MS)

        assertEquals(listOf(listOf(6L)), shown.map { it.first })
    }

    @Test
    fun enteringTheOpenPreviewCancelsAShowScheduledForAnotherLine() {
        notesByLine[3] = listOf(note(1))
        notesByLine[8] = listOf(note(2))
        controller.hover(3, 40)
        scheduler.advance(GutterPreviewController.SHOW_DELAY_MS)

        controller.hover(8, 120)
        scheduler.advance(100)
        enter()
        scheduler.advance(5_000)

        assertEquals(listOf(listOf(1L)), shown.map { it.first })
        assertEquals(1, open().size)
    }

    @Test
    fun movingFromANoteLineToALineWithoutNotesClosesTheOldPreview() {
        notesByLine[3] = listOf(note(1))
        controller.hover(3, 40)
        scheduler.advance(GutterPreviewController.SHOW_DELAY_MS)

        controller.hover(4, 56)
        scheduler.advance(5_000)

        assertEquals(1, shown.size)
        assertTrue(open().isEmpty())
    }

    @Test
    fun repeatedEventsOnTheSameLineDoNotRestartTheDelay() {
        notesByLine[3] = listOf(note(1))

        controller.hover(3, 40)
        repeat(10) {
            scheduler.advance(30)
            controller.hover(3, 40)
        }
        scheduler.advance(GutterPreviewController.SHOW_DELAY_MS)

        assertEquals(1, shown.size)
    }

    @Test
    fun disposeClosesTheOpenPreviewAndDropsPendingOnes() {
        notesByLine[3] = listOf(note(1))
        controller.hover(3, 40)
        scheduler.advance(GutterPreviewController.SHOW_DELAY_MS)
        controller.hover(3, 40)

        controller.dispose()
        scheduler.advance(5_000)

        assertFalse(shown.any { it.second.isOpen })
        assertEquals(1, shown.size)
    }
}
