package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagNameRulesTest {
    private val sql = Tag(1, "sql")
    private val todo = Tag(2, "todo")
    private val all = listOf(sql, todo)

    @Test fun blankIsRejected() {
        assertEquals(TagNameProblem.BLANK, TagNameRules.check("", sql, all))
        assertEquals(TagNameProblem.BLANK, TagNameRules.check("   ", sql, all))
    }

    @Test fun commaIsRejectedBecauseItSeparatesTagsInTheExportView() {
        assertEquals(TagNameProblem.COMMA, TagNameRules.check("a,b", sql, all))
    }

    @Test fun anotherTagsNameIsRejectedAfterTrimming() {
        assertEquals(TagNameProblem.EXISTS, TagNameRules.check(" todo ", sql, all))
    }

    @Test fun namesAreCaseSensitiveSoADifferentCaseIsAllowed() {
        assertNull(TagNameRules.check("TODO", sql, all))
    }

    @Test fun keepingItsOwnNameIsAllowed() {
        assertNull(TagNameRules.check("sql", sql, all))
        assertNull(TagNameRules.check("query", sql, all))
    }
}
