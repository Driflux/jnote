package io.github.driflux.jnote.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.intellij.testFramework.PlatformTestUtil
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
            DatabaseService.getInstance().switchTo(DatabasePaths.default())
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun fieldOf(configurable: JNoteConfigurable): TextFieldWithBrowseButton =
        UIUtil.findComponentOfType(configurable.createComponent(), TextFieldWithBrowseButton::class.java)!!

    private fun awaitLoaded(field: TextFieldWithBrowseButton) =
        PlatformTestUtil.waitWithEventsDispatching("stored path was not loaded", { field.isEnabled }, 20)

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
        awaitLoaded(field)
        assertEquals(DatabasePaths.builtInDefault().toString(), field.text)
        assertFalse(configurable.isModified)
        configurable.disposeUIResources()
    }

    fun testShowsStoredPathAndAppliesNewOne() {
        val stored = dir.resolve("stored.db")
        DbPathSettings.getInstance().store(stored)
        DatabaseService.getInstance().switchTo(stored)
        val configurable = JNoteConfigurable()
        val field = fieldOf(configurable)
        configurable.reset()
        awaitLoaded(field)
        assertEquals(stored.toString(), field.text)
        assertFalse(configurable.isModified)

        val next = dir.resolve("next.db")
        field.text = next.toString()
        assertTrue(configurable.isModified)
        configurable.apply()
        // 복사와 전환은 백그라운드 작업이라 끝날 때까지 이벤트를 처리하며 기다린다
        PlatformTestUtil.waitWithEventsDispatching(
            "DB path was not switched",
            { DbPathSettings.getInstance().state.dbPath == next.toString() },
            20,
        )
        assertEquals(next, DatabaseService.getInstance().currentPath())
        assertFalse(configurable.isModified)
        configurable.disposeUIResources()
    }

    fun testReadsTheSettingOffTheEdtAndIsNotModifiedUntilItArrives() {
        val stored = dir.resolve("stored.db")
        DbPathSettings.getInstance().store(stored)
        val release = CountDownLatch(1)
        var loadedOnEdt: Boolean? = null
        val configurable = JNoteConfigurable()
        configurable.loadSettings = {
            loadedOnEdt = ApplicationManager.getApplication().isDispatchThread
            release.await(10, TimeUnit.SECONDS)
        }
        val field = fieldOf(configurable)
        configurable.reset()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertFalse(field.isEnabled)
        assertEquals("", field.text)
        assertFalse(configurable.isModified)

        release.countDown()
        awaitLoaded(field)

        assertEquals(false, loadedOnEdt)
        assertEquals(stored.toString(), field.text)
        assertFalse(configurable.isModified)
        field.text = dir.resolve("next.db").toString()
        assertTrue(configurable.isModified)
        configurable.disposeUIResources()
    }

    fun testApplyBeforeTheStoredPathArrivesChangesNothing() {
        val storedNow = DbPathSettings.getInstance().state.dbPath
        val release = CountDownLatch(1)
        val configurable = JNoteConfigurable()
        configurable.loadSettings = { release.await(10, TimeUnit.SECONDS) }
        fieldOf(configurable)
        configurable.reset()

        configurable.apply()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(storedNow, DbPathSettings.getInstance().state.dbPath)
        configurable.disposeUIResources()
        release.countDown()
    }

    fun testDisposingBeforeTheStoredPathArrivesDropsTheLoad() {
        val release = CountDownLatch(1)
        val read = CountDownLatch(1)
        val configurable = JNoteConfigurable()
        configurable.loadSettings = {
            release.await(10, TimeUnit.SECONDS)
            read.countDown()
        }
        val field = fieldOf(configurable)
        configurable.disposeUIResources()

        release.countDown()
        assertTrue(read.await(10, TimeUnit.SECONDS))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertFalse(field.isEnabled)
        assertEquals("", field.text)
    }
}
