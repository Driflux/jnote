package io.github.driflux.jnote.ai

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import io.github.driflux.jnote.ui.JNoteToolWindowFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor

class InboxCheckTest : BasePlatformTestCase() {
    private lateinit var root: Path
    private lateinit var inbox: Inbox
    private val shown = mutableListOf<Pair<List<InboxFile>, Notification>>()
    private val queued = ArrayDeque<Runnable>()
    private var scans = 0

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("jnote-inbox-check")
        inbox = Inbox(root.resolve("inbox"))
    }

    override fun tearDown() {
        try {
            root.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun put(name: String, text: String = "{}") {
        Files.writeString(Files.createDirectories(inbox.dir).resolve(name), text)
    }

    private fun check(background: Executor = Executor { it.run() }) = InboxCheck(
        { scans++; inbox },
        background,
        Executor { it.run() },
        { _, files -> Notification("JNote", "t", "c", NotificationType.INFORMATION).also { shown += files to it } },
    )

    fun testNotifiesOncePerSetOfFiles() {
        val check = check()
        check.request(project)
        assertTrue("empty inbox shows nothing", shown.isEmpty())

        put("a.json")
        check.request(project)
        check.request(project)
        assertEquals(1, shown.size)

        put("b.json")
        check.request(project)
        assertEquals(2, shown.size)
        assertTrue("the older notification is replaced", shown[0].second.isExpired)
        assertEquals(listOf("a.json", "b.json"), shown[1].first.map { it.path.fileName.toString() })
    }

    fun testDismissedNotificationComesBackOnTheNextFocus() {
        val check = check()
        put("a.json")
        check.request(project)
        shown.single().second.expire()

        check.request(project)

        assertEquals(2, shown.size)
    }

    fun testEmptiedInboxExpiresTheNotification() {
        val check = check()
        put("a.json")
        check.request(project)
        Files.delete(inbox.dir.resolve("a.json"))

        check.request(project)

        assertTrue(shown.single().second.isExpired)
    }

    fun testRequestsDuringAScanCoalesceIntoOneRescan() {
        val check = check(Executor { queued.addLast(it) })
        put("a.json")

        check.request(project)
        check.request(project)
        check.request(project)
        while (queued.isNotEmpty()) queued.removeFirst().run()

        assertEquals("one scan plus one coalesced rescan", 2, scans)
        assertEquals(1, shown.size)
    }

    fun testFailingNotificationDoesNotBlockLaterChecks() {
        var fail = true
        val check = InboxCheck({ inbox }, Executor { it.run() }, Executor { it.run() }) { _, files ->
            if (fail) error("boom")
            Notification("JNote", "t", "c", NotificationType.INFORMATION).also { shown += files to it }
        }
        put("a.json")
        try {
            check.request(project)
        } catch (_: IllegalStateException) {
        }
        fail = false

        check.request(project)

        assertEquals(1, shown.size)
    }

    fun testEachProjectGetsItsOwnNotification() {
        val check = check()
        put("a.json")
        val other = com.intellij.testFramework.PlatformTestUtil.loadAndOpenProject(Files.createDirectories(root.resolve("other")), testRootDisposable)

        check.request(project)
        check.request(other)
        check.request(project)

        assertEquals(2, shown.size)
    }

    fun testFactoryChecksTheInboxWhenItsWindowIsActivated() {
        val checked = mutableListOf<String>()
        val factory = JNoteToolWindowFactory { checked += "check" }
        val window = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        val listener = factory.activationListener(project, window.id)
        val manager = ToolWindowManager.getInstance(project)

        listener.stateChanged(manager, window, ToolWindowManagerEventType.ActivateToolWindow)
        listener.stateChanged(manager, window, ToolWindowManagerEventType.HideToolWindow)

        assertEquals(listOf("check"), checked)
    }
}
