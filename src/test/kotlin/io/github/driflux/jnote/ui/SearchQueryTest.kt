package io.github.driflux.jnote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    @Test
    fun shorterThanTwoCharactersIsNotSearchable() {
        assertFalse(SearchQuery.isSearchable(""))
        assertFalse(SearchQuery.isSearchable("a"))
        assertFalse(SearchQuery.isSearchable("취"))
    }

    @Test
    fun twoCharactersAreSearchable() {
        assertTrue(SearchQuery.isSearchable("ab"))
        assertTrue(SearchQuery.isSearchable("취소"))
    }

    @Test
    fun surroundingWhitespaceDoesNotCount() {
        assertFalse(SearchQuery.isSearchable("  a  "))
        assertEquals("ab", SearchQuery.normalize("  ab "))
    }

    @Test
    fun aSurrogatePairCountsAsOneCharacter() {
        assertFalse(SearchQuery.isSearchable("😀"))
    }
}
