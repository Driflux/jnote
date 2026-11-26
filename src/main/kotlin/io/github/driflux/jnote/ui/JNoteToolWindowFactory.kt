package io.github.driflux.jnote.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.github.driflux.jnote.action.NewFreeNoteAction

/** 창을 처음 열 때만 내용을 만든다. 등록만으로는 DB도 패널도 건드리지 않는다. */
class JNoteToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JNotePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(ActionManager.getInstance().getAction(NewFreeNoteAction.ID)))
    }
}
