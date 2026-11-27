package io.github.driflux.jnote.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AiInstructionsTest {
    private val spec = Files.readString(Path.of("docs/ai-format.md"))

    @Test
    fun theBundledSpecIsTheDocsFileOfThisBuild() {
        val bundled = checkNotNull(javaClass.getResourceAsStream("/ai/ai-format.md")).use { it.readBytes().toString(Charsets.UTF_8) }

        assertEquals(spec, bundled)
    }

    @Test
    fun instructionsAreTheWebAiPromptOfTheSpecFollowedByTheWholeSpec() {
        val text = AiInstructions.text()

        assertTrue(text, text.startsWith("Summarize what we found as JNote notes."))
        assertTrue(text.endsWith("\n\n" + spec))
        assertTrue("the quote markers are removed", text.substringBefore("\n\n" + spec).lines().none { it.startsWith(">") })
    }

    @Test
    fun aMultiLineQuoteIsJoinedWithoutItsMarkers() {
        val doc = "# Spec\n\n### For a web AI\n\n> First line\n> second line\n\n### Next\n> not this\n"

        assertEquals("First line\nsecond line\n\n" + doc, AiInstructions.build(doc))
    }

    @Test
    fun aSpecWithoutTheWebAiPromptIsCopiedAsIs() {
        assertEquals("# Spec only\n", AiInstructions.build("# Spec only\n"))
    }
}
