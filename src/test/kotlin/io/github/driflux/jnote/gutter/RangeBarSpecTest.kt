package io.github.driflux.jnote.gutter

import io.github.driflux.jnote.model.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RangeBarSpecTest {
    private fun note(id: Long, start: Int, end: Int, status: NoteStatus = NoteStatus.OK) =
        CachedNote(id, "uid$id", "t$id", "", emptyList(), null, status, start, end, "")

    @Test
    fun onlyMultiLineNotesGetABar() {
        val bars = RangeBarSpec.of(listOf(note(1, 3, 3), note(2, 5, 9)))

        assertEquals(listOf(RangeBar(2, 5, 9)), bars)
    }

    @Test
    fun approximateNotesGetABarAndLostOnesDoNot() {
        val bars = RangeBarSpec.of(
            listOf(
                note(1, 1, 4, NoteStatus.APPROX),
                note(2, 1, 4, NoteStatus.ORPHAN),
                note(3, 1, 4, NoteStatus.NEEDS_RELOCATE),
            ),
        )

        assertEquals(listOf(RangeBar(1, 1, 4)), bars)
    }

    @Test
    fun overlappingNotesEachKeepTheirOwnBarInIdOrder() {
        val bars = RangeBarSpec.of(listOf(note(7, 2, 8), note(4, 3, 5)))

        assertEquals(listOf(RangeBar(4, 3, 5), RangeBar(7, 2, 8)), bars)
    }
}
