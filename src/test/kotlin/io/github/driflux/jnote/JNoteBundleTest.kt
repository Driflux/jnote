package io.github.driflux.jnote

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.InputStream
import java.nio.file.Files
import java.util.Locale
import java.util.Properties
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
        assertEquals("JNote에 오신 것을 환영합니다", korean.getString("plugin.greeting"))
    }

    fun testBundlesHaveSameKeys() {
        assertEquals(keysOf(resource(EN)), keysOf(resource(KO)))
    }

    fun testParityCheckCatchesKeyMissingFromKorean() {
        val korean = load(resource(KO))
        korean.remove(korean.stringPropertyNames().first())
        val copy = Files.createTempFile("JNoteBundle_ko", ".properties")
        try {
            Files.newBufferedWriter(copy).use { korean.store(it, null) }
            assertFalse(keysOf(resource(EN)) == Files.newInputStream(copy).let(::keysOf))
        } finally {
            Files.delete(copy)
        }
    }

    private fun resource(name: String): InputStream =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing $name" }

    private fun load(stream: InputStream): Properties =
        Properties().apply { stream.use { load(it.reader(Charsets.UTF_8)) } }

    private fun keysOf(stream: InputStream): Set<String> = load(stream).stringPropertyNames()

    private companion object {
        const val EN = "messages/JNoteBundle.properties"
        const val KO = "messages/JNoteBundle_ko.properties"
    }
}
