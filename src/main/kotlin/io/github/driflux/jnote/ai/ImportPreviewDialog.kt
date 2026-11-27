package io.github.driflux.jnote.ai

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.github.driflux.jnote.JNoteBundle
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** 가져올 노트를 보여 주고 적용 여부를 묻는다. 내용 문장은 [ImportMessages]가 만든다. EDT에서 부른다. */
internal class ImportPreviewDialog(project: Project, private val plan: ImportPlan, private val skipped: Int) : DialogWrapper(project) {
    init {
        title = JNoteBundle.message("dialog.importPreview.title")
        setOKButtonText(JNoteBundle.message("dialog.importPreview.apply"))
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(JBLabel(ImportMessages.summary(plan, skipped)), BorderLayout.NORTH)
        add(JBScrollPane(JBList(ImportMessages.rows(plan))).apply { preferredSize = JBUI.size(480, 240) }, BorderLayout.CENTER)
    }

    companion object {
        fun ask(project: Project, plan: ImportPlan, problems: List<ImportProblem>): Boolean =
            ImportPreviewDialog(project, plan, problems.size).showAndGet()
    }
}
