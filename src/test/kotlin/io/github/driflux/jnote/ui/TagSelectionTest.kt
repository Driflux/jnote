package io.github.driflux.jnote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagSelectionTest {
    @Test fun parseSplitsOnCommasTrimsAndDropsBlanksAndDuplicates() {
        assertEquals(listOf("a", "b c", "d"), TagSelection.parse(" a, b c ,, d,a "))
        assertEquals(emptyList<String>(), TagSelection.parse("  ,  "))
    }

    @Test fun addKeepsOrderAndReportsWhetherAnythingChanged() {
        val s = TagSelection(listOf("x"))
        assertTrue(s.add("y, z"))
        assertFalse(s.add("x"))
        assertEquals(listOf("x", "y", "z"), s.names)
    }

    @Test fun namesAreCaseSensitive() {
        val s = TagSelection()
        s.add("SQL, sql")
        assertEquals(listOf("SQL", "sql"), s.names)
    }

    @Test fun toggleAddsThenRemoves() {
        val s = TagSelection()
        s.toggle("a")
        assertTrue(s.contains("a"))
        s.toggle("a")
        assertFalse(s.contains("a"))
    }

    @Test fun initialValuesAreNormalized() {
        assertEquals(listOf("a", "b"), TagSelection(listOf(" a", "b,a")).names)
    }
}
