package io.github.driflux.jnote.gutter

import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.JPanel

class GutterIconsTest : BasePlatformTestCase() {
    private fun pixels(icon: Icon): IntArray {
        assertEquals(16, icon.iconWidth)
        assertEquals(16, icon.iconHeight)
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            icon.paintIcon(JPanel(), graphics, 0, 0)
        } finally {
            graphics.dispose()
        }
        return image.getRGB(0, 0, 16, 16, null, 0, 16)
    }

    private fun paintsSomething(pixels: IntArray) = pixels.any { it ushr 24 != 0 }

    fun testEachIconPaintsAndHasItsOwnDarkVariant() {
        for (icon in listOf(GutterIcons.NOTE, GutterIcons.APPROX)) {
            val light = pixels(icon)
            val dark = pixels(IconLoader.getDarkIcon(icon, true))

            assertTrue(paintsSomething(light))
            assertTrue(paintsSomething(dark))
            assertFalse("dark variant should differ from the light one", light.contentEquals(dark))
        }
    }

    fun testNormalAndApproximateIconsLookDifferent() {
        assertFalse(pixels(GutterIcons.NOTE).contentEquals(pixels(GutterIcons.APPROX)))
    }
}
