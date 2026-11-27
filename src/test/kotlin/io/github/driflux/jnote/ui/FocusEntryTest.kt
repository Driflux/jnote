package io.github.driflux.jnote.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JButton
import javax.swing.JPanel

class FocusEntryTest {
    private val panel = JPanel()
    private val inner = JButton().also { panel.add(it) }
    private val other = JButton().also { panel.add(it) }
    private val outside = JButton()
    private val entry = FocusEntry(panel)

    @Test fun fromOutsideToInsideIsAnEntry() = assertTrue(entry.entered(outside, inner))

    @Test fun fromNothingToInsideIsAnEntry() = assertTrue(entry.entered(null, inner))

    @Test fun betweenTwoInsideComponentsIsNotAnEntry() = assertFalse(entry.entered(inner, other))

    @Test fun leavingIsNotAnEntry() = assertFalse(entry.entered(inner, outside))

    @Test fun outsideToOutsideIsNotAnEntry() = assertFalse(entry.entered(outside, JButton()))

    @Test fun losingFocusEntirelyIsNotAnEntry() = assertFalse(entry.entered(inner, null))

    @Test
    fun anInsideMoveReportedThroughNothingIsNotAnEntry() {
        entry.entered(outside, inner)
        entry.entered(inner, null)
        assertFalse(entry.entered(null, other))
    }

    @Test
    fun comingInFromOutsideThroughNothingIsAnEntry() {
        entry.entered(inner, outside)
        entry.entered(outside, null)
        assertTrue(entry.entered(null, inner))
    }
}
