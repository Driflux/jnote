package io.github.driflux.jnote.gutter

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import io.github.driflux.jnote.JNoteBundle
import javax.swing.Icon

internal class AddNoteGutterRenderer(private val onClick: () -> Unit) : GutterIconRenderer(), DumbAware {
    override fun getIcon(): Icon = GutterIcons.ADD

    override fun getTooltipText(): String = JNoteBundle.message("gutter.add.tooltip")

    override fun getAccessibleName(): String = JNoteBundle.message("gutter.add.tooltip")

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): AnAction = object : AnAction(), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) = onClick()
    }

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}