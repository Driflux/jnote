package io.github.driflux.jnote.ai

import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.NoteStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class NoteCopyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val db by lazy { Database(tmp.root.toPath().resolve("jnote.db")) }
    private val notes by lazy { NoteRepository(db) }
    private val tags by lazy { TagRepository(db) }

    @After
    fun close() = db.close()

    @Test
    fun readKeepsTheSelectionOrderAndSkipsNotesThatAreGone() {
        val a = notes.create(NewNote.free("a")).get()
        val b = notes.create(NewNote.free("b")).get()
        val gone = notes.create(NewNote.free("gone")).get()
        notes.delete(gone.id).get()

        val read = NoteCopy.read(db, listOf(b.uid, gone.uid, a.uid)).get()

        assertEquals(listOf("b", "a"), read.map { it.note.title })
    }

    @Test
    fun readCarriesEveryFieldTheFormatHasWithTagsByName() {
        val code = notes.create(
            NewNote.code("github.com/acme/shop", "src/Retry.kt", 3, 4, "retry", body = "why", className = "Retry", snapshot = "a\nb")
                .copy(aiSource = "Claude", sourceUrl = "https://chat", prompt = "q"),
        ).get()
        tags.replaceTags(code.id, listOf("retry", "payments")).get()

        val read = NoteCopy.read(db, listOf(code.uid)).get().single()

        assertEquals(
            FormatNote(
                uid = code.uid,
                title = "retry",
                body = "why",
                tags = listOf("payments", "retry"),
                anchor = FormatAnchor("github.com/acme/shop", "src/Retry.kt", 3, 4, "Retry", "a\nb"),
                aiSource = "Claude",
                sourceUrl = "https://chat",
                prompt = "q",
            ),
            read.note,
        )
        assertEquals(NoteStatus.OK, read.status)
        assertEquals(code.createdAt, read.createdAt)
    }

    @Test
    fun jsonIsAFormatV1DocumentThatImportsBackUnchanged() {
        val free = notes.create(NewNote.free("query", body = "SELECT 1")).get()
        val code = notes.create(NewNote.code("github.com/acme/shop", "src/A.kt", 1, 1, "a", snapshot = "x")).get()
        tags.replaceTags(free.id, listOf("sql")).get()
        val read = NoteCopy.read(db, listOf(free.uid, code.uid)).get()

        val decoded = NoteFormatCodec.decode(NoteCopy.json(read)) as DecodeResult.Decoded

        assertEquals(emptyList<SkippedItem>(), decoded.skipped)
        assertEquals(read.map { it.note }, decoded.notes)
    }

    @Test
    fun markdownHasTitleLocationSnippetAndBodyPerNote() {
        val code = FormatNote(
            uid = "u1",
            title = "Why retry backs off",
            body = "Rate limits.",
            anchor = FormatAnchor("github.com/acme/shop", "src/main/Retry.kt", 42, 43, "Retry", "fun a() {\n}\n"),
        )
        val free = FormatNote(uid = "u2", title = "Stuck orders", body = "SELECT 1;")

        val expected = """
            |## Why retry backs off
            |
            |`src/main/Retry.kt:L42-43`
            |
            |```kt
            |fun a() {
            |}
            |```
            |
            |Rate limits.
            |
            |## Stuck orders
            |
            |SELECT 1;
        """.trimMargin()
        assertEquals(expected, NoteCopy.markdown(listOf(code, free)))
    }

    @Test
    fun markdownLeavesOutEmptyPartsAndUsesTheSingleLineForm() {
        val note = FormatNote(uid = "u", title = "t", anchor = FormatAnchor("p", "Makefile", 7, 7))

        assertEquals("## t\n\n`Makefile:L7`", NoteCopy.markdown(listOf(note)))
    }

    @Test
    fun aSnippetWithBackticksGetsALongerFence() {
        val note = FormatNote(uid = "u", title = "t", anchor = FormatAnchor("p", "README.md", 1, 3, snapshot = "```kotlin\nx\n```"))

        val block = NoteCopy.markdown(listOf(note)).substringAfter("`README.md:L1-3`\n\n")

        assertEquals("````md\n```kotlin\nx\n```\n````", block)
    }
}
