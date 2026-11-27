package io.github.driflux.jnote.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

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


    private fun names(dir: Path) = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test fun claimMovesTheFileOutOfTheInboxAndOnlyOnce() {
        put("a.json", "text")
        val seen = inbox.scan().single()

        val claimed = inbox.claim(seen)!!

        assertEquals("a.json", claimed.name)
        assertEquals("text", Files.readString(claimed.path))
        assertEquals(emptyList<InboxFile>(), inbox.scan())
        assertNull("a second window finds it taken", Inbox(inbox.dir).claim(seen))
    }

    @Test fun archiveAndReleaseNeverOverwrite() {
        put("a.json", "first")
        inbox.archive(inbox.claim(inbox.scan().single())!!)
        put("a.json", "second")
        inbox.archive(inbox.claim(inbox.scan().single())!!)
        put("a.json", "third")
        val third = inbox.claim(inbox.scan().single())!!
        put("a.json", "newer, written meanwhile")

        inbox.release(third)

        val done = inbox.dir.resolve(Inbox.DONE)
        assertEquals(listOf("a-1.json", "a.json"), names(done))
        assertEquals("first", Files.readString(done.resolve("a.json")))
        assertEquals("second", Files.readString(done.resolve("a-1.json")))
        assertEquals("newer, written meanwhile", Files.readString(inbox.dir.resolve("a.json")))
        assertEquals("third", Files.readString(inbox.dir.resolve("a-1.json")))
        assertEquals(emptyList<String>(), names(inbox.dir.resolve(Inbox.PROCESSING)))
    }

    @Test fun concurrentArchivesOfTheSameNameKeepEveryFile() {
        val claims = (1..40).map { i ->
            put("same.json", "content $i")
            inbox.claim(inbox.scan().single())!!
        }
        val pool = Executors.newFixedThreadPool(8)
        try {
            claims.map { claimed -> pool.submit { Inbox(inbox.dir).archive(claimed) } }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        val done = inbox.dir.resolve(Inbox.DONE)
        assertEquals(40, names(done).size)
        assertEquals((1..40).map { "content $it" }.toSet(), Files.list(done).use { s -> s.map { Files.readString(it) }.toList() }.toSet())
    }
}
