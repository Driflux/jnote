package io.github.driflux.jnote.db

import io.github.driflux.jnote.model.NewNote
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteListBenchmarkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val db by lazy { Database(tmp.root.toPath().resolve("jnote.db")) }
    private val repo by lazy { NoteRepository(db) }

    @After
    fun close() = db.close()

    @Test
    fun fullListVersusSummaryList() {
        assumeTrue(System.getenv("JNOTE_PERF") == "1")
        for (snapshotChars in listOf(2_000, 8_000, 65_536)) {
            val project = "p$snapshotChars"
            val snapshot = "x".repeat(snapshotChars)
            repeat(NOTES) {
                repo.create(NewNote.code(project, "src/F$it.kt", 1, 5, "title $it", body = "b".repeat(500), snapshot = snapshot)).get()
            }
            repeat(WARMUP) { repo.list(project, false).get(); repo.listSummaries(project, false).get() }
            val full = median { assertEquals(NOTES, repo.list(project, false).get().size) }
            val summary = median { assertEquals(NOTES, repo.listSummaries(project, false).get().size) }
            println("JNOTE-PERF notes=$NOTES snapshotChars=$snapshotChars fullMs=$full summaryMs=$summary")
        }
    }

    private fun median(block: () -> Unit): Long =
        List(RUNS) { val start = System.nanoTime(); block(); (System.nanoTime() - start) / 1_000_000 }.sorted()[RUNS / 2]

    private companion object {
        const val NOTES = 3_000
        const val WARMUP = 2
        const val RUNS = 7
    }
}
