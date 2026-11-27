package io.github.driflux.jnote.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ImportPreparationTest {
    private fun doc(vararg notes: String) = """{"format_version":1,"notes":[${notes.joinToString(",")}]}"""

    @Test fun collectsNotesAndEveryKindOfProblemPerDocument() {
        val prepared = ImportPreparation.prepare(
            listOf(
                ImportSource("a.json") { doc("""{"uid":"1","kind":"FREE","title":"one"}""", """{"kind":"FREE"}""") },
                ImportSource("b.json") { "not json" },
                ImportSource("c.json") { throw IOException("gone") },
                ImportSource("d.json") { doc("""{"uid":"2","kind":"FREE","title":"two"}""") },
            ),
        )

        assertEquals(listOf("one", "two"), prepared.notes.map { it.title })
        assertEquals(
            listOf(
                ImportProblem.Skipped("a.json", SkippedItem(1, null, "title", FieldProblem.MISSING)),
                ImportProblem.Rejected("b.json", DocumentError.MALFORMED_JSON),
                ImportProblem.Unreadable("c.json", "gone"),
            ),
            prepared.problems,
        )
    }

    @Test fun sameUidInTwoDocumentsSkipsBoth() {
        val prepared = ImportPreparation.prepare(
            listOf(
                ImportSource("a.json") { doc("""{"uid":"x","kind":"FREE","title":"from a"}""", """{"kind":"FREE","title":"no uid"}""") },
                ImportSource("b.json") { doc("""{"uid":"x","kind":"FREE","title":"from b"}""", """{"kind":"FREE","title":"no uid"}""") },
            ),
        )

        assertEquals(listOf("no uid", "no uid"), prepared.notes.map { it.title })
        assertEquals(listOf(ImportProblem.DuplicateAcrossDocuments("a.json, b.json", "x")), prepared.problems)
    }
}
