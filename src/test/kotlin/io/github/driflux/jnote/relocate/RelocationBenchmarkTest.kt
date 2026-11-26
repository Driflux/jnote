package io.github.driflux.jnote.relocate

import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.AnchorUpdate
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readLines

/**
 * 시간은 기계마다 달라 단정하지 않고 출력만 하며, JNOTE_PERF=1일 때만 돈다.
 * 실행: JNOTE_PERF=1 ./gradlew test --tests '*RelocationBenchmarkTest*' -i | grep JNOTE-PERF
 */
class RelocationBenchmarkTest {
    private enum class Change { MOVED, EDITED, DELETED }

    private class Case(val document: List<String>, val notes: List<Note>, val expected: List<AnchorUpdate?>)

    private val snippetSizes = listOf(3, 5, 8, 12, 20)

    private fun displaced(source: List<String>, change: (Int) -> Change): Case {
        val step = source.size / NOTES
        val notes = ArrayList<Note>()
        val expected = ArrayList<AnchorUpdate?>()
        val document = ArrayList<String>(source.size + INSERTED)
        repeat(INSERTED) { document += "// inserted line $it to push every note down" }
        var next = 0
        for (i in 0 until NOTES) {
            val start = i * step + step / 2
            val size = snippetSizes[i % snippetSizes.size]
            val snippet = source.subList(start, start + size)
            while (next < start) document += source[next++]
            val uid = "n$i"
            notes += note(uid, start + 1, size, snippet.joinToString("\n"))
            // 찾은 범위는 앞뒤 빈 줄을 뺀 첫 줄부터 마지막 줄까지다(ADR 0012)
            val first = document.size + 1 + snippet.indexOfFirst { it.isNotBlank() }
            val last = document.size + 1 + snippet.indexOfLast { it.isNotBlank() }
            when (change(i)) {
                Change.MOVED -> {
                    document += snippet
                    expected += AnchorUpdate(uid, REL_PATH, first, last, NoteStatus.OK)
                }
                Change.EDITED -> {
                    document += edit(snippet)
                    expected += AnchorUpdate(uid, REL_PATH, first, last, NoteStatus.APPROX)
                }
                // 지운 코드는 비슷한 곳이 남아 있을 수 있어 결과를 정하지 않는다
                Change.DELETED -> expected += null
            }
            next = start + size
        }
        while (next < source.size) document += source[next++]
        return Case(document, notes, expected)
    }

    private fun edit(snippet: List<String>): List<String> {
        val longest = snippet.indices.maxBy { snippet[it].length }
        val chars = snippet[longest].toCharArray()
        var seen = 0
        for (k in chars.indices) if (!chars[k].isWhitespace() && seen++ % 5 == 4) chars[k] = 'Z'
        return snippet.toMutableList().also { it[longest] = String(chars) }
    }

    private fun note(uid: String, start: Int, size: Int, snapshot: String) = Note(
        id = 0, uid = uid, kind = NoteKind.CODE, project = "p", relPath = REL_PATH, className = null,
        startLine = start, endLine = start + size - 1, snapshot = snapshot, status = NoteStatus.OK, title = uid,
        body = "", aiSource = null, sourceUrl = null, prompt = null, createdAt = 0, updatedAt = 0,
    )

    private fun repositoryCode(lines: Int): List<String> {
        val all = Files.walk(Path.of("src")).use { paths ->
            paths.filter { it.extension == "kt" }.sorted().toList().flatMap { it.readLines() }
        }
        check(all.size >= lines) { "repository has only ${all.size} lines" }
        return all.subList(0, lines)
    }

    /** ADR 0012의 최악 쪽 입력: 모든 줄이 서로 비슷한 생성 코드 */
    private fun templated(lines: Int) = List(lines) { "    val value$it = compute($it, ${it * 7}) // generated line" }

    private fun mixed(i: Int) = when (i % 4) {
        0 -> Change.DELETED
        1 -> Change.EDITED
        else -> Change.MOVED
    }

    private fun run(case: Case, similarity: Boolean = true): List<AnchorUpdate> =
        FileRelocation.relocate(case.notes, case.document, similarity)!!

    private fun measure(rounds: Int, block: () -> Unit): String {
        repeat(3) { block() }
        val times = (1..rounds).map {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1e6
        }.sorted()
        return "median %.1f ms, min %.1f, max %.1f (%d rounds)".format(times[rounds / 2], times.first(), times.last(), rounds)
    }

    private fun outcomes(updates: List<AnchorUpdate>) =
        NoteStatus.entries.joinToString(" ") { status -> "$status=${updates.count { it.status == status }}" }

    private fun check(case: Case, updates: List<AnchorUpdate>): Int {
        assertEquals(case.notes.size, updates.size)
        return case.expected.indices.count { case.expected[it] == null || case.expected[it] == updates[it] }
    }

    @Test
    fun relocateTwentyDisplacedNotes() {
        assumeTrue(System.getenv("JNOTE_PERF") == "1")
        for ((label, source) in listOf("templated" to templated(5_000), "repository" to repositoryCode(5_000))) {
            for ((scenario, change) in listOf<Pair<String, (Int) -> Change>>(
                "moved" to { Change.MOVED },
                "edited" to { Change.EDITED },
                "deleted" to { Change.DELETED },
                "mixed" to ::mixed,
            )) {
                val case = displaced(source, change)
                val updates = run(case)
                val correct = check(case, updates)
                println(
                    "JNOTE-PERF $label ${case.document.size} lines, 20 notes $scenario: " + measure(9) { run(case) } +
                        "; ${outcomes(updates)}; expected places $correct/$NOTES",
                )
            }
            val case = displaced(source, ::mixed)
            println("JNOTE-PERF $label mixed exact only: " + measure(9) { run(case, similarity = false) })
            println("JNOTE-PERF $label normalize document 20 times: " + measure(9) { repeat(NOTES) { Normalizer.normalize(case.document) } })
        }
    }

    @Test
    fun relocateTwentyNotesInLargerFiles() {
        assumeTrue(System.getenv("JNOTE_PERF") == "1")
        // 저장소 코드는 이 크기가 안 되어 되풀이하면 지운 코드의 사본이 남으므로, 최악 쪽인 생성 코드만 잰다
        for (lines in listOf(20_000, 50_000)) {
            for ((scenario, change) in listOf<Pair<String, (Int) -> Change>>("deleted" to { Change.DELETED }, "mixed" to ::mixed)) {
                val case = displaced(templated(lines), change)
                val updates = run(case)
                println(
                    "JNOTE-PERF templated ${case.document.size} lines, 20 notes $scenario: " + measure(5) { run(case) } +
                        "; ${outcomes(updates)}; expected places ${check(case, updates)}/$NOTES",
                )
            }
        }
    }

    @Test
    fun databaseShareOfOneRun() {
        assumeTrue(System.getenv("JNOTE_PERF") == "1")
        val dir = Files.createTempDirectory("jnote-relocation-bench")
        try {
            Database(dir.resolve("jnote.db")).use { db ->
                val repo = NoteRepository(db)
                val case = displaced(templated(5_000), ::mixed)
                for (note in case.notes) {
                    repo.create(NewNote.code("p", REL_PATH, note.startLine!!, note.endLine!!, note.title, snapshot = note.snapshot)).get()
                }
                val stored = repo.listByPath("p", REL_PATH).get()
                val moved = FileRelocation.relocate(stored, case.document)!!
                val back = stored.map { AnchorUpdate(it.uid, REL_PATH, it.startLine!!, it.endLine!!, it.status) }
                println("JNOTE-PERF db listByPath 20 notes: " + measure(9) { repo.listByPath("p", REL_PATH).get() })
                // 바뀌는 쓰기를 재려고 옮긴 값과 원래 값을 번갈아 쓴다. 한 번에 두 트랜잭션이다
                println(
                    "JNOTE-PERF db updateAnchors 20 changed notes, two transactions: " + measure(9) {
                        assertEquals(NOTES, repo.updateAnchors(moved).get())
                        assertEquals(NOTES, repo.updateAnchors(back).get())
                    },
                )
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val NOTES = 20
        const val INSERTED = 50
        const val REL_PATH = "src/Big.kt"
    }
}
