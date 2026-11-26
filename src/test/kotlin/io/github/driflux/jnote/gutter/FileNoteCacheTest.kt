package io.github.driflux.jnote.gutter

import io.github.driflux.jnote.model.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class FileNoteCacheTest {
    private val loads = mutableListOf<Pair<String, CompletableFuture<List<CachedNote>>>>()
    private val changed = mutableListOf<String>()
    private val failed = mutableListOf<Throwable>()
    private val direct = Executor { it.run() }
    private val cache = FileNoteCache({ path -> CompletableFuture<List<CachedNote>>().also { loads += path to it } }, direct)

    private fun note(id: Long, title: String = "t$id") = CachedNote(id, "u$id", title, "", emptyList(), null, NoteStatus.OK, 1, 1, "")

    private fun lookup(path: String) = cache.lookup(path, onChanged = { changed += path }, onFailed = { failed += it })

    @Test
    fun firstLookupStartsOneLoadAndReturnsNothingYet() {
        assertNull(lookup("a"))
        assertNull(lookup("a"))
        assertEquals(1, loads.size)
        assertFalse(cache.isIdle)
        assertEquals(emptyList<String>(), changed)
    }

    @Test
    fun afterTheLoadTheNotesAreServedWithoutAnotherLoadAndTheChangeIsReported() {
        lookup("a")
        val notes = listOf(note(1))
        loads.single().second.complete(notes)

        assertSame(notes, lookup("a"))
        assertSame(notes, lookup("a"))
        assertEquals(1, loads.size)
        assertEquals(listOf("a"), changed)
        assertTrue(cache.isIdle)
    }

    @Test
    fun aFileWithoutNotesIsCachedAsEmptyWithoutAChangeAndNotLoadedAgain() {
        lookup("a")
        loads.single().second.complete(emptyList())

        assertEquals(emptyList<CachedNote>(), lookup("a"))
        assertEquals(emptyList<CachedNote>(), lookup("a"))
        assertEquals(1, loads.size)
        assertEquals(emptyList<String>(), changed)
    }

    @Test
    fun filesAreLoadedIndependently() {
        lookup("a")
        lookup("b")
        assertEquals(listOf("a", "b"), loads.map { it.first })
        loads[0].second.complete(listOf(note(1)))
        assertNull(lookup("b"))
        assertEquals(listOf(note(1)), lookup("a"))
        assertTrue(cache.contains("b"))
        assertFalse(cache.contains("c"))
    }

    @Test
    fun invalidateKeepsServingTheOldValueAndReloadsOnTheNextLookup() {
        lookup("a")
        loads[0].second.complete(listOf(note(1)))
        cache.invalidate()

        assertEquals(listOf(note(1)), lookup("a"))
        assertEquals(2, loads.size)
        // 읽는 동안 더 조회해도 다시 읽지 않는다
        assertEquals(listOf(note(1)), lookup("a"))
        assertEquals(2, loads.size)

        loads[1].second.complete(listOf(note(2)))
        assertEquals(listOf(note(2)), lookup("a"))
        assertEquals(listOf("a", "a"), changed)
    }

    @Test
    fun aReloadWithTheSameNotesIsNotReportedAsAChange() {
        lookup("a")
        loads[0].second.complete(listOf(note(1)))
        cache.invalidate()
        lookup("a")

        loads[1].second.complete(listOf(note(1)))

        assertEquals(listOf("a"), changed)
    }

    @Test
    fun clearDropsTheOldValueAndAnInFlightResultIsIgnored() {
        lookup("a")
        loads[0].second.complete(listOf(note(1)))
        cache.invalidate()
        lookup("a")
        cache.clear()

        assertNull(lookup("a"))
        val before = loads.size
        // clear 전에 시작한 읽기가 늦게 끝나도 새 항목을 덮거나 콜백을 부르지 않는다
        loads[1].second.complete(listOf(note(9)))
        assertEquals(before, loads.size)
        assertNull(lookup("a"))
        assertEquals(listOf("a"), changed)
    }

    @Test
    fun aResultThatWasInvalidatedWhileLoadingIsDroppedAndReloaded() {
        lookup("a")
        cache.invalidate()
        loads[0].second.complete(listOf(note(1)))

        assertEquals(2, loads.size)
        assertEquals(emptyList<String>(), changed)
        assertNull(lookup("a"))

        loads[1].second.complete(listOf(note(2)))
        assertEquals(listOf(note(2)), lookup("a"))
        assertEquals(listOf("a"), changed)
    }

    @Test
    fun aFailedLoadIsReportedOnceAndNotRetriedUntilInvalidated() {
        lookup("a")
        val error = IllegalStateException("db locked")
        loads[0].second.completeExceptionally(error)

        assertNull(lookup("a"))
        assertNull(lookup("a"))
        assertEquals(1, loads.size)
        assertEquals(listOf<Throwable>(error), failed)
        assertTrue(cache.isIdle)

        cache.invalidate()
        assertNull(lookup("a"))
        loads[1].second.complete(listOf(note(1)))
        assertEquals(listOf(note(1)), lookup("a"))
    }

    @Test
    fun aFailedReloadKeepsServingTheLastValue() {
        lookup("a")
        loads[0].second.complete(listOf(note(1)))
        cache.invalidate()
        lookup("a")

        loads[1].second.completeExceptionally(IllegalStateException("db locked"))

        assertEquals(listOf(note(1)), lookup("a"))
        assertEquals(1, failed.size)
    }

    @Test
    fun aLoaderThatThrowsIsReportedAsAFailedLoad() {
        val throwing = FileNoteCache({ error("boom") }, direct)
        assertNull(throwing.lookup("a", onChanged = { }, onFailed = { failed += it }))
        assertNull(throwing.lookup("a", onChanged = { }, onFailed = { failed += it }))
        assertEquals(listOf("boom"), failed.map { it.message })
    }

    @Test
    fun overTheFileLimitTheCacheStartsOver() {
        val small = FileNoteCache({ path -> CompletableFuture<List<CachedNote>>().also { loads += path to it } }, direct, maxFiles = 2)
        small.lookup("a", { }, { })
        small.lookup("b", { }, { })
        loads[0].second.complete(listOf(note(1)))
        small.lookup("c", { }, { })

        assertNull(small.lookup("a", { }, { }))
        assertEquals(4, loads.size)
    }
}
