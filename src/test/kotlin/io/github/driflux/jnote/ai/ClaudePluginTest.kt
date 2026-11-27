package io.github.driflux.jnote.ai

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ClaudePluginTest {
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
