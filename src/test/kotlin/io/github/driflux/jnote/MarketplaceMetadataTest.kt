package io.github.driflux.jnote

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** Marketplace에 올릴 설명, 변경 사항, 아이콘, 라이선스가 갖춰졌는지 확인한다. */
class MarketplaceMetadataTest {
    private val pluginXml = Path.of("src/main/resources/META-INF/plugin.xml")

    private fun element(tag: String): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pluginXml.toFile())
        return doc.getElementsByTagName(tag).item(0).textContent
    }

    @Test
    fun descriptionStartsInEnglishAndAlsoHasKorean() {
        val description = element("description")
        val firstLetters = description.replace(Regex("<[^>]*>"), "").trim()

        assertTrue(description.length >= 40)
        assertTrue(firstLetters.startsWith("JNote is a lightweight"))
        assertTrue(description.any { it in '가'..'힣' })
    }

    @Test
    fun descriptionStatesLocalOnlyAndNoTelemetryInBothLanguages() {
        val description = element("description")

        assertTrue(description.contains("Local only"))
        assertTrue(description.contains("no telemetry"))
        assertTrue(description.contains("로컬 전용"))
        assertTrue(description.contains("텔레메트리가 없습니다"))
    }

    @Test
    fun descriptionTellsHowToInstallTheSkillWithTheMarketplaceNames() {
        val marketplace = JsonParser.parseString(Files.readString(Path.of(".claude-plugin/marketplace.json"))).asJsonObject
        val plugin = marketplace.getAsJsonArray("plugins").single().asJsonObject.get("name").asString
        val name = marketplace.get("name").asString
        val owner = "Driflux/jnote"
        val description = element("description")

        assertTrue(description.contains("/plugin marketplace add $owner"))
        assertTrue(description.contains("/plugin install $plugin@$name"))
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
