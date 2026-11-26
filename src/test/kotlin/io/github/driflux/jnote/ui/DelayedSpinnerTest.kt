package io.github.driflux.jnote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelayedSpinnerTest {
    private class FakeScheduler : DelayScheduler {
        class Job(val dueAt: Long, val task: () -> Unit) {
            var cancelled = false
        }

        var now = 0L
        val jobs = mutableListOf<Job>()

        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val job = Job(now + delayMs, task)
            jobs += job
            return Cancellable { job.cancelled = true }
        }

        fun advance(ms: Long) {
            now += ms
            jobs.filter { !it.cancelled && it.dueAt <= now }.forEach { jobs.remove(it); it.task() }
        }

        /** 취소 요청을 무시하고 실행해, 취소가 늦은 경우를 흉내 낸다 */
        fun runEvenIfCancelled() = jobs.toList().forEach { jobs.remove(it); it.task() }
    }

    private val scheduler = FakeScheduler()
    private val changes = mutableListOf<Boolean>()
    private val spinner = DelayedSpinner(scheduler, { changes += it })

    @Test
    fun fastLoadNeverShowsTheSpinner() {
        spinner.start()
        scheduler.advance(299)
        spinner.stop()
        scheduler.advance(1_000)
        assertTrue(changes.isEmpty())
        assertFalse(spinner.visible)
    }

    @Test
    fun slowLoadShowsAfterTheDelayAndHidesOnStop() {
        spinner.start()
        scheduler.advance(299)
        assertFalse(spinner.visible)
        scheduler.advance(1)
        assertTrue(spinner.visible)
        spinner.stop()
        assertFalse(spinner.visible)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun overlappingStartsMeasureFromTheFirstOne() {
        spinner.start()
        scheduler.advance(200)
        spinner.start()
        scheduler.advance(100)
        assertTrue(spinner.visible)
        assertEquals(listOf(true), changes)
    }

    @Test
    fun startWhileVisibleDoesNotRestartTheTimer() {
        spinner.start()
        scheduler.advance(300)
        spinner.start()
        assertTrue(scheduler.jobs.isEmpty())
        assertTrue(spinner.visible)
    }

    @Test
    fun aCancelledTimerThatStillFiresCannotShowTheSpinner() {
        spinner.start()
        spinner.stop()
        scheduler.runEvenIfCancelled()
        assertFalse(spinner.visible)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun aStaleTimerCannotShowTheSpinnerForTheNextLoad() {
        spinner.start()
        spinner.stop()
        spinner.start()
        // 첫 번째 타이머가 늦게 실행돼도 두 번째 읽기의 시간으로 센다
        val stale = scheduler.jobs.first()
        stale.task()
        assertFalse(spinner.visible)
        scheduler.advance(300)
        assertTrue(spinner.visible)
    }

    @Test
    fun canBeUsedAgainAfterStop() {
        spinner.start()
        scheduler.advance(300)
        spinner.stop()
        spinner.start()
        scheduler.advance(300)
        assertEquals(listOf(true, false, true), changes)
    }
}
