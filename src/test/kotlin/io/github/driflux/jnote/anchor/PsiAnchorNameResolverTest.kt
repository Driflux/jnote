package io.github.driflux.jnote.anchor

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PsiAnchorNameResolverTest : BasePlatformTestCase() {
    private val resolver = PsiAnchorNameResolver()

    private fun resolveAt(file: PsiFile, marker: String): String? =
        resolver.resolve(file, file.text.indexOf(marker))

    fun testInnermostFunctionInNestedClasses() {
        val file = myFixture.configureByText("A.kt", "class A { class B { fun c() { marker() } } }")
        assertEquals("A.B.c", resolveAt(file, "marker"))
    }

    fun testOffsetOnClassHeaderGivesClassOnly() {
        val file = myFixture.configureByText("A.kt", "class Outer { class Inner { fun f() {} } }")
        assertEquals("Outer.Inner", resolveAt(file, "Inner"))
    }

    fun testCapsAtThreeLevelsFromInnermost() {
        val file = myFixture.configureByText(
            "A.kt",
            "class A { class B { class C { fun d() { marker() } } } }",
        )
        assertEquals("B.C.d", resolveAt(file, "marker"))
    }

    // 공통 PSI는 지역 변수와 필드를 컨테이너와 구분하지 못해 이름 있는 선언이면 모두 센다 (ADR 0005)
    fun testNamedLocalVariableCountsAsContainer() {
        val file = myFixture.configureByText("A.kt", "class A { fun b() { val local = 1 } }")
        assertEquals("A.b.local", resolveAt(file, "local"))
    }

    fun testTopLevelFunction() {
        val file = myFixture.configureByText("A.kt", "fun top() { marker() }")
        assertEquals("top", resolveAt(file, "marker"))
    }

    fun testNoNamedContainerReturnsNull() {
        val file = myFixture.configureByText("A.kt", "// only a comment")
        assertNull(resolveAt(file, "only"))
    }

    fun testPlainTextFileReturnsNull() {
        val file = myFixture.configureByText("notes.txt", "class A { fun b() {} }")
        assertNull(resolveAt(file, "fun"))
    }

    fun testEmptyFileReturnsNull() {
        val file = myFixture.configureByText("A.kt", "")
        assertNull(resolver.resolve(file, 0))
    }

    fun testOffsetPastEndIsClamped() {
        val file = myFixture.configureByText("A.kt", "class A { fun b() {} }")
        assertNotNull(resolver.resolve(file, 10_000))
    }
}
