package io.github.driflux.jnote.gutter

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import io.github.driflux.jnote.JNoteBundle
import javax.swing.Icon

/** 마우스가 올라간 줄에 그려지는 "+" 아이콘. 클릭하면 [onClick]이 그 줄의 노트 추가를 연다. */
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

    // 줄마다 새로 만들고 한 번에 하나만 달려 있으므로 같은 인스턴스일 때만 같다
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}
