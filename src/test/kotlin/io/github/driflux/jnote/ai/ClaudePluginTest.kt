package io.github.driflux.jnote.ai

import com.google.gson.JsonParser
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.NewNote
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ClaudePluginTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val root = Path.of("claude-plugin")
    private fun normalized(text: String) = text.replace("\r\n", "\n")

    private val skill = normalized(Files.readString(root.resolve("skills/jnote/SKILL.md")))

    @Test
    fun theSpecCopyIsTheMarkerLineFollowedByDocsAiFormat() {
        val copy = normalized(Files.readString(root.resolve("skills/jnote/ai-format.md")))
        val original = normalized(Files.readString(Path.of("docs/ai-format.md")))

        assertTrue(copy.lineSequence().first().contains("Copy of docs/ai-format.md. Do not edit"))
        assertEquals("run ./gradlew syncSkillSpec", original, copy.substringAfter('\n'))
    }

    @Test
    fun theManifestNamesThePluginJnote() {
        val manifest = JsonParser.parseString(Files.readString(root.resolve(".claude-plugin/plugin.json"))).asJsonObject

        assertEquals("jnote", manifest.get("name").asString)
    }

    @Test
    fun theSkillIsNamedJnoteAndPointsAtTheBundledSpec() {
        val frontmatter = skill.substringAfter("---\n").substringBefore("\n---")

        assertTrue(frontmatter, frontmatter.lines().contains("name: jnote"))
        assertTrue(frontmatter.lines().any { it.startsWith("description: ") && it.length > "description: ".length })
        assertTrue(skill.contains("\${CLAUDE_PLUGIN_ROOT}/skills/jnote/ai-format.md"))
    }

    private fun sqlExamples(): List<String> =
        Regex("```sql\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(skill)
            .map { it.groupValues[1].lines().joinToString("\n") { line -> line.trim() }.trim() }
            .toList()

    @Test
    fun everyRecallQueryReadsOnlyTheViewAndFindsTheMatchingNote() {
        val queries = sqlExamples()
        assertEquals("search, file, and uid queries", 3, queries.size)
        val db = Database(tmp.root.toPath().resolve("jnote.db"))
        try {
            val notes = NoteRepository(db)
            val retry = notes.create(
                NewNote.code("github.com/acme/shop", "src/main/kotlin/shop/payment/Retry.kt", 42, 45, "Why retry backs off", body = "rate limits"),
            ).get()
            TagRepository(db).replaceTags(retry.id, listOf("payments")).get()
            notes.create(NewNote.free("unrelated", body = "nothing here")).get()
            notes.create(NewNote.code("github.com/other/shop", "src/main/kotlin/shop/payment/Retry.kt", 1, 1, "other project", body = "same path elsewhere")).get()
            notes.create(NewNote.code("github.com/other/shop", "src/retry/Config.kt", 3, 4, "Path only", body = "nothing")).get()
            notes.restore(retry.copy(id = retry.id + 100, uid = "0b6f7f0e-8a43-4d7e-9a39-2f0c5d1f6a10", title = "Pinned retry note"), emptyList()).get()

            for (query in queries) {
                assertTrue(query, Regex("\\bFROM\\s+v_notes_export\\b").containsMatchIn(query))
                assertTrue(query, query.trimStart().startsWith("SELECT"))
                val titles = db.submit { c -> c.createStatement().use { s -> s.executeQuery(query).use { rs -> generateSequence { if (rs.next()) rs.getString("title") else null }.toList() } } }.get()
                assertTrue("$query\n$titles", titles.isNotEmpty())
                assertTrue("$query\n$titles", "unrelated" !in titles)
            }
            val searchTitles = db.submit { c -> c.createStatement().use { s -> s.executeQuery(queries[0]).use { rs -> generateSequence { if (rs.next()) rs.getString("title") else null }.toList() } } }.get()
            val fileTitles = db.submit { c -> c.createStatement().use { s -> s.executeQuery(queries[1]).use { rs -> generateSequence { if (rs.next()) rs.getString("title") else null }.toList() } } }.get()
            assertTrue("the same path in another project: $fileTitles", "other project" !in fileTitles)
            assertTrue("a note matching only by rel_path: $searchTitles", "Path only" in searchTitles)
        } finally {
            db.close()
        }
    }

    @Test
    fun recallFallsBackToTheFolderForLocalOriginsAndTreatsNotesAsUntrusted() {
        val recall = skill.substringAfter("## Recall")

        assertTrue(recall.contains("local path"))
        assertTrue(recall.contains("untrusted"))
    }

    @Test
    fun theCaptureReadsTheRawOriginUrlLikeTheIde() {
        assertTrue(skill.contains("config --get remote.origin.url"))
        assertTrue(!skill.contains("<project root> remote get-url origin"))
    }

    @Test
    fun thePublishStepNeedsARealJsonValidatorAndNeverPassesWithoutOne() {
        assertTrue(!skill.contains("! command -v python3"))
        assertTrue(skill.contains("jq empty"))
    }

    @Test
    fun theOrganizeExampleUpdatesByUidAndKeepsTheStoredProjectKey() {
        val organize = skill.substringAfter("## Organize")
        val example = Regex("```json\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).find(organize)!!.groupValues[1]

        val notes = (NoteFormatCodec.decode(example) as DecodeResult.Decoded).notes

        assertTrue(notes.isNotEmpty())
        for (note in notes) {
            assertTrue("an update names its uid", note.uid != null)
            assertTrue("an update uses project_path, not repo_url", "\"repo_url\"" !in example)
            assertEquals("github.com/acme/shop", note.anchor?.project)
        }
    }

    @Test
    fun theOrganizeFlowWarnsAboutStaleFilesAndAsksToVerifyTheMergeBeforeDeleting() {
        val organize = skill.substringAfter("## Organize").substringBefore("### Example")

        assertTrue(organize.contains("discard the pending file"))
        assertTrue(organize.contains("before deleting"))
    }

    @Test
    fun everyJsonExampleInTheSkillDecodesWithoutSkippedItems() {
        val examples = Regex("```json\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(skill).map { it.groupValues[1] }.toList()
        assertTrue("the skill shows at least one example", examples.isNotEmpty())
        for (example in examples) {
            val result = NoteFormatCodec.decode(example)
            assertTrue("$result\n$example", result is DecodeResult.Decoded)
            result as DecodeResult.Decoded
            assertEquals(example, emptyList<SkippedItem>(), result.skipped)
            for (anchor in result.notes.mapNotNull { it.anchor }) {
                val lines = anchor.snapshot?.lines()?.size ?: continue
                assertEquals("snapshot must cover the anchored lines", anchor.endLine - anchor.startLine + 1, lines)
            }
        }
    }
}
