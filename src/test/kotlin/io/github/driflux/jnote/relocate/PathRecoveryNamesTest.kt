package io.github.driflux.jnote.relocate

import org.junit.Assert.assertEquals
import org.junit.Test

class PathRecoveryNamesTest {
    @Test
    fun fileNameComesFirstThenTheOutermostClassNameWithTheSameExtension() {
        assertEquals(listOf("Helpers.kt", "Widget.kt"), PathRecovery.candidateNames("util/Helpers.kt", "Widget.render"))
    }

    @Test
    fun aClassNameEqualToTheFileNameIsNotRepeated() {
        assertEquals(listOf("Widget.kt"), PathRecovery.candidateNames("ui/Widget.kt", "Widget.Inner.draw"))
    }

    @Test
    fun withoutAClassNameOnlyTheFileNameIsSearched() {
        assertEquals(listOf("Helpers.kt"), PathRecovery.candidateNames("util/Helpers.kt", null))
        assertEquals(listOf("Helpers.kt"), PathRecovery.candidateNames("util/Helpers.kt", ""))
    }

    @Test
    fun aFileWithoutExtensionGivesABareClassName() {
        assertEquals(listOf("Makefile", "Build"), PathRecovery.candidateNames("Makefile", "Build"))
    }
}
