package io.github.driflux.jnote.ui

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tool Window 아이콘은 파일로만 검사한다. 헤드리스 Linux JVM은 SVG를 1x1로 로드해 그려 보는 테스트가 불안정하다. */
class ToolWindowIconTest {
    private fun read(path: String): String {
        val stream = ToolWindowIconTest::class.java.getResourceAsStream(path)
        assertNotNull("missing resource $path", stream)
        return stream!!.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun pluginXmlPointsToAnExistingIcon() {
        val xml = read("/META-INF/plugin.xml")
        assertTrue(xml.contains("icon=\"/icons/toolWindowNote.svg\""))
        assertTrue(read("/icons/toolWindowNote.svg").contains("<svg"))
    }

    @Test
    fun iconsAre13PixelAndDarkDiffers() {
        val light = read("/icons/toolWindowNote.svg")
        val dark = read("/icons/toolWindowNote_dark.svg")
        for (text in listOf(light, dark)) {
            assertTrue(text.contains("width=\"13\"") && text.contains("height=\"13\""))
        }
        assertNotEquals(light, dark)
    }
}
