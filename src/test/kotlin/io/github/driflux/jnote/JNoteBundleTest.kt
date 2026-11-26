package io.github.driflux.jnote

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.Locale
import java.util.ResourceBundle

class JNoteBundleTest : BasePlatformTestCase() {
    fun testMessageResolvesFromBundle() {
        assertFalse(JNoteBundle.message("plugin.greeting").isBlank())
    }

    // 플랫폼 번들은 테스트에서 로케일을 바꿀 수 없어 ResourceBundle에 로케일을 직접 준다
    fun testEnglishDefaultAndKoreanBundles() {
        val english = ResourceBundle.getBundle("messages.JNoteBundle", Locale.ROOT)
        val korean = ResourceBundle.getBundle("messages.JNoteBundle", Locale.KOREAN)
        assertEquals("Hello from JNote", english.getString("plugin.greeting"))
        assertEquals("JNote에서 안녕하세요", korean.getString("plugin.greeting"))
    }

    fun testBundlesHaveSameKeys() {
        val english = ResourceBundle.getBundle("messages.JNoteBundle", Locale.ROOT)
        val korean = ResourceBundle.getBundle("messages.JNoteBundle", Locale.KOREAN)
        assertEquals(english.keySet(), korean.keySet())
    }
}
