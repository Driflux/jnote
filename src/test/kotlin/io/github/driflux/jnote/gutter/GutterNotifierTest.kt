package io.github.driflux.jnote.gutter

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.Notifications
import com.intellij.notification.NotificationsConfiguration
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.settings.DbPathNotifier

class GutterNotifierTest : BasePlatformTestCase() {
    private val shown = mutableListOf<Notification>()
    private val config get() = NotificationsConfiguration.getNotificationsConfiguration()
    private val groups = listOf(GutterNotifier.POSITION_SAVE_GROUP_ID, DbPathNotifier.GROUP_ID)
    private lateinit var displayTypesBefore: Map<String, NotificationDisplayType>

    override fun setUp() {
        super.setUp()
        // 표시 유형은 앱 전체 설정이라 다른 테스트에 남지 않도록 원래 값을 기억해 두고 되돌린다
        displayTypesBefore = groups.associateWith { config.getDisplayType(it) }
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    shown += notification
                }
            },
        )
    }

    override fun tearDown() {
        try {
            displayTypesBefore.forEach { (id, type) -> config.setDisplayType(id, type) }
        } finally {
            super.tearDown()
        }
    }

    fun testPositionSaveGroupIsRegisteredWithItsOwnName() {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GutterNotifier.POSITION_SAVE_GROUP_ID)
        assertNotNull(group)
        assertEquals(NotificationDisplayType.BALLOON, group!!.displayType)
        // Settings | Appearance & Behavior | Notifications에 보이는 이름
        assertEquals(
            JNoteBundle.message("notification.group.positionSave.name"),
            NotificationGroup.getGroupTitle(GutterNotifier.POSITION_SAVE_GROUP_ID),
        )
    }

    fun testSaveFailureGoesToThePositionSaveGroup() {
        GutterNotifier.saveFailed(project, "src/A.kt", IllegalStateException("locked"))

        assertEquals(GutterNotifier.POSITION_SAVE_GROUP_ID, shown.single().groupId)
    }

    fun testDontShowAgainTurnsOffOnlyThePositionSaveGroup() {
        assertEquals(NotificationDisplayType.BALLOON, config.getDisplayType(GutterNotifier.POSITION_SAVE_GROUP_ID))
        val otherBefore = config.getDisplayType(DbPathNotifier.GROUP_ID)
        GutterNotifier.saveFailed(project, "src/A.kt", IllegalStateException("locked"))
        val notification = shown.single()
        val dontShow = notification.actions.single() as NotificationAction

        dontShow.actionPerformed(TestActionEvent.createTestEvent(dontShow), notification)

        assertEquals(NotificationDisplayType.NONE, config.getDisplayType(GutterNotifier.POSITION_SAVE_GROUP_ID))
        assertEquals(otherBefore, config.getDisplayType(DbPathNotifier.GROUP_ID))
        assertTrue(notification.isExpired)
    }

    fun testLoadFailureStaysInTheSharedGroupWithoutTheAction() {
        GutterNotifier.loadFailed(project, "src/A.kt", IllegalStateException("locked"))

        val notification = shown.single()
        assertEquals(DbPathNotifier.GROUP_ID, notification.groupId)
        assertTrue(notification.actions.isEmpty())
    }
}
