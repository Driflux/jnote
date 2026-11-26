package io.github.driflux.jnote.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.driflux.jnote.db.DatabasePaths
import java.nio.file.Files
import java.nio.file.Path

class JNoteConfigurableTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private lateinit var storedBefore: String

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-settings").toRealPath()
        storedBefore = DbPathSettings.getInstance().state.dbPath
    }

    override fun tearDown() {
        try {
            DbPathSettings.getInstance().state.dbPath = storedBefore
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun fieldOf(configurable: JNoteConfigurable): TextFieldWithBrowseButton =
        UIUtil.findComponentOfType(configurable.createComponent(), TextFieldWithBrowseButton::class.java)!!

    fun testExtensionIsRegisteredUnderTools() {
        val ep = Configurable.APPLICATION_CONFIGURABLE.extensionList
            .single { it.id == "io.github.driflux.jnote.settings" }
        assertEquals("tools", ep.parentId)
        assertEquals(JNoteConfigurable::class.java.name, ep.instanceClass)
    }

    fun testFieldChecksOnlyTheFormOfThePathWhileTyping() {
        val configurable = JNoteConfigurable()
        val panel = configurable.createComponent() as com.intellij.openapi.ui.DialogPanel
        val field = fieldOf(configurable)
        fun errors(text: String): List<String> {
            field.text = text
            return (panel.validationsOnInput.values.flatten() + panel.validationsOnApply.values.flatten())
                .mapNotNull { it.validate()?.message }
        }
        val notAbsolute = io.github.driflux.jnote.JNoteBundle.message("settings.dbPath.invalid.notAbsolute")
        // 폴더인지는 파일 시스템을 봐야 알 수 있어 입력 중에는 가리지 않는다
        assertEquals(emptyList<String>(), errors(dir.toString()))
        assertEquals(listOf(notAbsolute, notAbsolute), errors("relative/jnote.db"))
        assertEquals(listOf(notAbsolute, notAbsolute), errors("/tmp/a\u0000b.db"))
        configurable.disposeUIResources()
    }

    fun testShowsDefaultPathWhenNothingIsStored() {
        DbPathSettings.getInstance().state.dbPath = ""
        val configurable = JNoteConfigurable()
        val field = fieldOf(configurable)
        configurable.reset()
        assertEquals(DatabasePaths.builtInDefault().toString(), field.text)
        assertFalse(configurable.isModified)
        configurable.disposeUIResources()
    }

    fun testShowsStoredPathAndAppliesNewOne() {
        val stored = dir.resolve("stored.db")
        DbPathSettings.getInstance().state.dbPath = stored.toString()
        val configurable = JNoteConfigurable()
        val field = fieldOf(configurable)
        configurable.reset()
        assertEquals(stored.toString(), field.text)
        assertFalse(configurable.isModified)

        val next = dir.resolve("next.db")
        field.text = next.toString()
        assertTrue(configurable.isModified)
        configurable.apply()
        assertEquals(next.toString(), DbPathSettings.getInstance().state.dbPath)
        assertFalse(configurable.isModified)
        configurable.disposeUIResources()
    }
}
