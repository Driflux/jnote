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

    @Test fun fromOutsideToInsideIsAnEntry() = assertTrue(FocusEntry.entered(panel, outside, inner))

    @Test fun fromNothingToInsideIsAnEntry() = assertTrue(FocusEntry.entered(panel, null, inner))

    @Test fun betweenTwoInsideComponentsIsNotAnEntry() = assertFalse(FocusEntry.entered(panel, inner, other))

    @Test fun leavingIsNotAnEntry() = assertFalse(FocusEntry.entered(panel, inner, outside))

    @Test fun outsideToOutsideIsNotAnEntry() = assertFalse(FocusEntry.entered(panel, outside, JButton()))

    @Test fun losingFocusEntirelyIsNotAnEntry() = assertFalse(FocusEntry.entered(panel, inner, null))
}
