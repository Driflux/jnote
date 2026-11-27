package io.github.driflux.jnote

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class MarketplaceMetadataTest {
    private val pluginXml = Path.of("src/main/resources/META-INF/plugin.xml")

    private fun element(tag: String): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pluginXml.toFile())
        return doc.getElementsByTagName(tag).item(0).textContent
    }

    private fun visible(html: String) = html.replace(Regex("<[^>]*>"), "").trim()

    private fun blocks(): Pair<String, String> {
        val parts = element("description").split("<hr/>")
        assertEquals(2, parts.size)
        return parts[0] to parts[1]
    }

    private fun hasHangul(text: String) = text.any { it in '가'..'힣' }

    @Test
    fun descriptionStartsInEnglishAndThenHasKorean() {
        val (english, korean) = blocks()

        assertTrue(visible(english).take(40).none { it.code > 127 })
        assertTrue(visible(english).startsWith("JNote is a lightweight"))
        assertFalse(hasHangul(english))
        assertTrue(hasHangul(korean))
    }

    @Test
    fun bothLanguagesStateLocalOnlyAndNoTelemetry() {
        val (english, korean) = blocks()

        assertTrue(english.contains("Local only"))
        assertTrue(english.contains("no telemetry"))
        assertTrue(korean.contains("로컬 전용"))
        assertTrue(korean.contains("텔레메트리가 없습니다"))
    }

    @Test
    fun bothLanguagesTellHowToInstallTheSkillWithTheMarketplaceNames() {
        val marketplace = JsonParser.parseString(Files.readString(Path.of(".claude-plugin/marketplace.json"))).asJsonObject
        val plugin = marketplace.getAsJsonArray("plugins").single().asJsonObject.get("name").asString
        val name = marketplace.get("name").asString
        val owner = "Driflux/jnote"

        for (block in blocks().toList()) {
            assertTrue(block.contains("/plugin marketplace add $owner"))
            assertTrue(block.contains("/plugin install $plugin@$name"))
        }
    }

    @Test
    fun changeNotesAreNotEmptyAndThePluginHasAVendorUrl() {
        assertTrue(element("change-notes").isNotBlank())
        val vendor = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pluginXml.toFile())
            .getElementsByTagName("vendor").item(0)
        assertTrue(vendor.attributes.getNamedItem("url").nodeValue.startsWith("https://"))
    }

    @Test
    fun lightAndDarkIconsAre40pxSvgs() {
        for (file in listOf("pluginIcon.svg", "pluginIcon_dark.svg")) {
            val svg = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(pluginXml.resolveSibling(file).toFile()).documentElement
            assertEquals(file, "40", svg.getAttribute("width"))
            assertEquals(file, "40", svg.getAttribute("height"))
        }
    }

    @Test
    fun licenseIsMit() {
        assertTrue(Files.readString(Path.of("LICENSE")).startsWith("MIT License"))
    }
}
