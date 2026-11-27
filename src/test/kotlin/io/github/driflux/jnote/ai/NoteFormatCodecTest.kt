package io.github.driflux.jnote.ai

import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class NoteFormatCodecTest {
    private val codeNote = FormatNote(
        uid = "11111111-2222-3333-4444-555555555555",
        title = "Retry policy",
        body = "Backs off \"exponentially\"\n탭\t과 줄바꿈",
        tags = listOf("infra", "retry"),
        anchor = FormatAnchor(
            project = "github.com/org/repo",
            relPath = "src/main/Retry.kt",
            startLine = 10,
            endLine = 14,
            className = "Retry",
            snapshot = "fun retry() {\n    // </script> \u2028\n}",
        ),
        aiSource = "Claude",
        sourceUrl = "https://claude.ai/chat/1",
        prompt = "why retry?",
    )
    private val pathNote = codeNote.copy(
        uid = "path-note",
        anchor = codeNote.anchor!!.copy(project = "/Users/me/work/scratch", className = null, snapshot = null),
        aiSource = null,
        sourceUrl = null,
        prompt = null,
    )
    private val freeNote = FormatNote(uid = "free-note", title = "SELECT count(*) FROM note", body = "", tags = emptyList())

    private fun encode(vararg notes: FormatNote): String = StringWriter().also { out ->
        NoteFormatCodec.encode(notes.map { ExportedNote(it, NoteStatus.APPROX, 1_700_000_000_000, 1_700_000_100_000) }, out)
    }.toString()

    private fun decoded(text: String): DecodeResult.Decoded {
        val result = NoteFormatCodec.decode(text)
        assertTrue("expected Decoded but was $result", result is DecodeResult.Decoded)
        return result as DecodeResult.Decoded
    }

    private fun rejected(text: String): DocumentError =
        (NoteFormatCodec.decode(text) as DecodeResult.Rejected).error

    private fun document(vararg notes: String) = """{"format_version": 1, "notes": [${notes.joinToString(",")}]}"""

    private fun skippedOnly(note: String): SkippedItem {
        val result = decoded(document(note))
        assertEquals(emptyList<FormatNote>(), result.notes)
        return result.skipped.single()
    }

    @Test fun roundTripKeepsEveryImportedField() {
        val result = decoded(encode(codeNote, pathNote, freeNote))
        assertEquals(listOf(codeNote, pathNote, freeNote), result.notes)
        assertEquals(emptyList<SkippedItem>(), result.skipped)
    }

    @Test fun urlKeyIsWrittenAsHttpsRepoUrlAndPathKeyAsProjectPath() {
        val text = encode(codeNote, pathNote)
        assertTrue(text, "\"repo_url\": \"https://github.com/org/repo\"" in text)
        assertTrue(text, "\"project_path\": \"/Users/me/work/scratch\"" in text)
    }

    @Test fun keyThatDoesNotSurviveAsUrlIsWrittenAsProjectPath() {
        val nameKey = codeNote.copy(anchor = codeNote.anchor!!.copy(project = "MyProject"))
        assertTrue("\"project_path\": \"MyProject\"" in encode(nameKey))
        assertEquals(listOf(nameKey), decoded(encode(nameKey)).notes)
    }

    @Test fun exportWritesVersionKindAndExportOnlyFieldsAndOmitsNulls() {
        val text = encode(freeNote)
        assertTrue(text, text.contains("\"format_version\": 1"))
        assertTrue(text, text.contains("\"kind\": \"FREE\""))
        assertTrue(text, text.contains("\"status\": \"APPROX\""))
        assertTrue(text, text.contains("\"created_at\": \"2023-11-14T22:13:20Z\""))
        assertFalse(text, text.contains("anchor"))
        assertFalse(text, text.contains("ai_source"))
    }

    @Test fun rawGitUrlsAreNormalizedIntoTheProjectKey() {
        val notes = decoded(
            document(
                """{"kind":"CODE","title":"t","anchor":{"repo_url":"git@github.com:org/repo.git","path":"a.kt","start_line":1,"end_line":1}}""",
                """{"kind":"CODE","title":"t","anchor":{"repo_url":"ssh://git@GitHub.com:2222/org/repo","path":"a.kt","start_line":1,"end_line":1}}""",
            ),
        ).notes
        assertEquals(listOf("github.com/org/repo", "github.com/org/repo"), notes.map { it.anchor!!.project })
    }

    @Test fun minimalAiWrittenNotesUseDefaults() {
        val note = decoded(document("""{"kind":"FREE","title":"just a title"}""")).notes.single()
        assertEquals(FormatNote(uid = null, title = "just a title"), note)
        assertEquals(NoteKind.FREE, note.kind)
    }

    @Test fun unknownAndExportOnlyFieldsAreIgnored() {
        val note = decoded(
            """{"format_version":1,"future":true,"notes":[{"kind":"FREE","title":"t","status":"bogus","created_at":5,"extra":{"a":1}}]}""",
        ).notes.single()
        assertEquals(FormatNote(uid = null, title = "t"), note)
    }

    @Test fun explicitNullMeansAbsent() {
        val note = decoded(
            document("""{"uid":null,"kind":"FREE","title":"t","body":null,"tags":null,"anchor":null,"ai_source":null}"""),
        ).notes.single()
        assertEquals(FormatNote(uid = null, title = "t"), note)
    }

    @Test fun tagsAreTrimmedAndDeduplicated() {
        val note = decoded(document("""{"kind":"FREE","title":"t","tags":[" a ","b","a"]}""")).notes.single()
        assertEquals(listOf("a", "b"), note.tags)
    }

    @Test fun invalidItemsAreSkippedWithTheirReasonAndValidOnesKept() {
        val result = decoded(
            document(
                """{"uid":"bad","kind":"CODE","title":"no anchor"}""",
                """{"uid":"good","kind":"FREE","title":"ok"}""",
            ),
        )
        assertEquals(listOf("good"), result.notes.map { it.uid })
        assertEquals(listOf(SkippedItem(0, "bad", "anchor", FieldProblem.MISSING)), result.skipped)
    }

    @Test fun itemProblems() {
        val anchor = """"path":"a.kt","start_line":1,"end_line":2"""
        val cases = mapOf(
            "42" to SkippedItem(0, null, "note", FieldProblem.WRONG_TYPE),
            """{"title":"t"}""" to SkippedItem(0, null, "kind", FieldProblem.MISSING),
            """{"kind":"code","title":"t"}""" to SkippedItem(0, null, "kind", FieldProblem.INVALID),
            """{"kind":"FREE"}""" to SkippedItem(0, null, "title", FieldProblem.MISSING),
            """{"kind":"FREE","title":"  "}""" to SkippedItem(0, null, "title", FieldProblem.INVALID),
            """{"kind":"FREE","title":7}""" to SkippedItem(0, null, "title", FieldProblem.WRONG_TYPE),
            """{"uid":"","kind":"FREE","title":"t"}""" to SkippedItem(0, "", "uid", FieldProblem.INVALID),
            """{"uid":5,"kind":"FREE","title":"t"}""" to SkippedItem(0, null, "uid", FieldProblem.WRONG_TYPE),
            """{"kind":"FREE","title":"t","tags":"a"}""" to SkippedItem(0, null, "tags", FieldProblem.WRONG_TYPE),
            """{"kind":"FREE","title":"t","tags":["a,b"]}""" to SkippedItem(0, null, "tags", FieldProblem.INVALID),
            """{"kind":"FREE","title":"t","tags":[" "]}""" to SkippedItem(0, null, "tags", FieldProblem.INVALID),
            """{"kind":"FREE","title":"t","anchor":{}}""" to SkippedItem(0, null, "anchor", FieldProblem.INVALID),
            """{"kind":"CODE","title":"t","anchor":[]}""" to SkippedItem(0, null, "anchor", FieldProblem.WRONG_TYPE),
            """{"kind":"CODE","title":"t","anchor":{$anchor}}""" to SkippedItem(0, null, "anchor.repo_url", FieldProblem.MISSING),
            """{"kind":"CODE","title":"t","anchor":{"repo_url":"https://h/o/r","project_path":"/p",$anchor}}"""
                to SkippedItem(0, null, "anchor.project_path", FieldProblem.INVALID),
            """{"kind":"CODE","title":"t","anchor":{"repo_url":"/local/path",$anchor}}"""
                to SkippedItem(0, null, "anchor.repo_url", FieldProblem.INVALID),
            """{"kind":"CODE","title":"t","anchor":{"project_path":" ",$anchor}}"""
                to SkippedItem(0, null, "anchor.project_path", FieldProblem.INVALID),
            """{"kind":"CODE","title":"t","anchor":{"repo_url":"https://h/o/r","start_line":1,"end_line":1}}"""
                to SkippedItem(0, null, "anchor.path", FieldProblem.MISSING),
            """{"kind":"CODE","title":"t","anchor":{"repo_url":"https://h/o/r","path":"../a.kt","start_line":1,"end_line":1}}"""
                to SkippedItem(0, null, "anchor.path", FieldProblem.INVALID),
            """{"kind":"CODE","title":"t","anchor":{"repo_url":"https://h/o/r","path":"/tmp/a.kt","start_line":1,"end_line":1}}"""
                to SkippedItem(0, null, "anchor.path", FieldProblem.INVALID),
            """{"kind":"CODE","title":"t","anchor":{"repo_url":"https://h/o/r","path":"a","end_line":1}}"""
                to SkippedItem(0, null, "anchor.start_line", FieldProblem.MISSING),
            """{"kind":"CODE","title":"t","anchor":{"repo_url":"https://h/o/r","path":"a","start_line":"1","end_line":1}}"""
                to SkippedItem(0, null, "anchor.start_line", FieldProblem.WRONG_TYPE),
            """{"kind":"CODE","title":"t","anchor":{"repo_url":"https://h/o/r","path":"a","start_line":1.0,"end_line":1}}"""
                to SkippedItem(0, null, "anchor.start_line", FieldProblem.WRONG_TYPE),
            """{"kind":"CODE","title":"t","anchor":{"repo_url":"https://h/o/r","path":"a","start_line":0,"end_line":1}}"""
                to SkippedItem(0, null, "anchor.start_line", FieldProblem.INVALID),
            """{"kind":"CODE","title":"t","anchor":{"repo_url":"https://h/o/r","path":"a","start_line":3,"end_line":2}}"""
                to SkippedItem(0, null, "anchor.end_line", FieldProblem.INVALID),
        )
        for ((note, expected) in cases) assertEquals(note, expected, skippedOnly(note))
    }

    @Test fun duplicateUidsAreAllSkippedInDocumentOrder() {
        val result = decoded(
            document(
                """{"uid":"a","kind":"FREE","title":"first"}""",
                """{"kind":"FREE","title":"bad"}""".replace("\"title\":\"bad\"", "\"title\":1"),
                """{"uid":"b","kind":"FREE","title":"kept"}""",
                """{"uid":"a","kind":"FREE","title":"second"}""",
                """{"kind":"FREE","title":"no uid"}""",
                """{"kind":"FREE","title":"no uid either"}""",
            ),
        )
        assertEquals(listOf("kept", "no uid", "no uid either"), result.notes.map { it.title })
        assertEquals(
            listOf(
                SkippedItem(0, "a", "uid", FieldProblem.DUPLICATE),
                SkippedItem(1, null, "title", FieldProblem.WRONG_TYPE),
                SkippedItem(3, "a", "uid", FieldProblem.DUPLICATE),
            ),
            result.skipped,
        )
    }

    @Test fun duplicateUidCountsInvalidCopiesToo() {
        val result = decoded(document("""{"uid":"x","kind":"FREE","title":"valid"}""", """{"uid":"x","kind":"FREE"}"""))
        assertEquals(emptyList<FormatNote>(), result.notes)
        assertEquals(
            listOf(SkippedItem(0, "x", "uid", FieldProblem.DUPLICATE), SkippedItem(1, "x", "title", FieldProblem.MISSING)),
            result.skipped,
        )
    }

    @Test fun documentProblems() {
        assertEquals(DocumentError.MALFORMED_JSON, rejected(""))
        assertEquals(DocumentError.MALFORMED_JSON, rejected("{\"format_version\": 1, \"notes\": ["))
        assertEquals(DocumentError.MALFORMED_JSON, rejected("{} {}"))
        assertEquals(DocumentError.MALFORMED_JSON, rejected("{format_version: 1, notes: []}"))
        assertEquals(DocumentError.MALFORMED_JSON, rejected("// comment\n{\"format_version\": 1, \"notes\": []}"))
        assertEquals(DocumentError.NOT_AN_OBJECT, rejected("[]"))
        assertEquals(DocumentError.MISSING_FORMAT_VERSION, rejected("{\"notes\": []}"))
        assertEquals(DocumentError.UNSUPPORTED_FORMAT_VERSION, rejected("{\"format_version\": 2, \"notes\": []}"))
        assertEquals(DocumentError.UNSUPPORTED_FORMAT_VERSION, rejected("{\"format_version\": \"1\", \"notes\": []}"))
        assertEquals(DocumentError.MISSING_NOTES, rejected("{\"format_version\": 1}"))
        assertEquals(DocumentError.MISSING_NOTES, rejected("{\"format_version\": 1, \"notes\": {}}"))
    }

    @Test fun emptyNotesIsAValidDocument() {
        assertEquals(DecodeResult.Decoded(emptyList(), emptyList()), NoteFormatCodec.decode(encode()))
    }
}
