package io.github.driflux.jnote.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class InboxTest {
    private lateinit var root: Path
    private lateinit var inbox: Inbox

    @Before fun setUp() {
        root = Files.createTempDirectory("jnote-inbox")
        inbox = Inbox(root.resolve("inbox"))
    }

    @After fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun put(name: String, text: String = "{}"): Path =
        Files.createDirectories(inbox.dir).resolve(name).also { Files.writeString(it, text) }

    private fun names(dir: Path) = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test fun missingFolderIsEmpty() = assertEquals(emptyList<InboxFile>(), inbox.scan())

    @Test fun scanListsOnlyJsonFilesDirectlyInsideByName() {
        put("b.json")
        put("a.json", "longer text")
        put("notes.txt")
        put("draft.json.tmp")
        Files.createDirectories(inbox.dir.resolve("done")).resolve("old.json").also { Files.writeString(it, "{}") }
        Files.createDirectories(inbox.dir.resolve("folder.json"))

        val files = inbox.scan()

        assertEquals(listOf("a.json", "b.json"), files.map { it.path.fileName.toString() })
        assertEquals(11L, files[0].size)
    }

    @Test fun moveToDoneKeepsExistingNamesAndUsesTheNextFreeOne() {
        put("a.json", "first")
        val first = inbox.scan().single()
        assertTrue(inbox.moveToDone(first))
        put("a.json", "second")

        assertTrue(inbox.moveToDone(inbox.scan().single()))

        val done = inbox.dir.resolve(Inbox.DONE)
        assertEquals(listOf("a-1.json", "a.json"), names(done))
        assertEquals("first", Files.readString(done.resolve("a.json")))
        assertEquals("second", Files.readString(done.resolve("a-1.json")))
        assertEquals(emptyList<InboxFile>(), inbox.scan())
    }

    @Test fun fileGoneBecauseAnotherWindowMovedItIsNotAnError() {
        put("a.json")
        val seen = inbox.scan().single()
        assertTrue("the other window moves it first", Inbox(inbox.dir).moveToDone(seen))

        assertFalse(inbox.moveToDone(seen))
        assertEquals(listOf("a.json"), names(inbox.dir.resolve(Inbox.DONE)))
    }

    @Test fun fileChangedSinceItWasReadStays() {
        val path = put("a.json", "old")
        val seen = inbox.scan().single()
        Files.writeString(path, "new and longer")
        Files.setLastModifiedTime(path, FileTime.fromMillis(seen.modified + 5_000))

        assertFalse(inbox.moveToDone(seen))
        assertEquals("new and longer", Files.readString(path))
    }
}
