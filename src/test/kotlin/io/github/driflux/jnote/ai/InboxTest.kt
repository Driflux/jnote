package io.github.driflux.jnote.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

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

}
