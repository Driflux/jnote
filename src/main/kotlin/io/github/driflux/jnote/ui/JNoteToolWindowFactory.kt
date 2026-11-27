package io.github.driflux.jnote.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import io.github.driflux.jnote.action.NewFreeNoteAction
import io.github.driflux.jnote.ai.InboxCheck

/**
 * 창을 처음 열 때만 내용을 만든다. 등록만으로는 DB도 패널도 건드리지 않는다.
 * 창이 활성화될 때마다(처음 열 때 포함) inbox를 한 번 확인한다. 구독은 패널과 함께 사라진다.
 * 인자는 테스트에서 inbox 확인을 바꿔 끼우기 위한 것이다.
 */
class JNoteToolWindowFactory(
    private val checkInbox: (Project) -> Unit = { InboxCheck.getInstance().request(it) },
) : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JNotePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(ActionManager.getInstance().getAction(NewFreeNoteAction.ID)))
        project.messageBus.connect(panel).subscribe(ToolWindowManagerListener.TOPIC, activationListener(project, toolWindow.id))
        checkInbox(project)
    }

    internal fun activationListener(project: Project, id: String) = object : ToolWindowManagerListener {
        override fun stateChanged(
            toolWindowManager: ToolWindowManager,
            toolWindow: ToolWindow,
            changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
        ) {
            if (toolWindow.id == id && changeType == ToolWindowManagerListener.ToolWindowManagerEventType.ActivateToolWindow) checkInbox(project)
        }
    }
}
