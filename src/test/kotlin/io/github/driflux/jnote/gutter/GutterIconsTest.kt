package io.github.driflux.jnote.gutter

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GutterIconsTest {
    private fun svg(path: String): String {
        val stream = GutterIcons::class.java.getResourceAsStream(path)
        assertNotNull("missing icon resource $path", stream)
        return stream!!.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun everyIconFileIsA16PixelSvg() {
        for (name in listOf("gutterNote", "gutterNote_dark", "gutterNoteApprox", "gutterNoteApprox_dark", "gutterAdd", "gutterAdd_dark")) {
            val text = svg("/icons/$name.svg")
            assertTrue("$name is not an svg", text.contains("<svg"))
            assertTrue("$name must be 16x16", text.contains("width=\"16\"") && text.contains("height=\"16\""))
        }
    }

    @Test
    fun darkVariantsDifferFromLightOnes() {
        assertNotEquals(svg("/icons/gutterNote.svg"), svg("/icons/gutterNote_dark.svg"))
        assertNotEquals(svg("/icons/gutterNoteApprox.svg"), svg("/icons/gutterNoteApprox_dark.svg"))
        assertNotEquals(svg("/icons/gutterAdd.svg"), svg("/icons/gutterAdd_dark.svg"))
    }

    @Test
    fun normalAndApproximateIconsDiffer() {
        assertNotEquals(svg("/icons/gutterNote.svg"), svg("/icons/gutterNoteApprox.svg"))
    }
}