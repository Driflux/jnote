package io.github.driflux.jnote.gutter

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.ui.NoteSelection
import java.awt.event.MouseEvent
import javax.swing.Icon

/** 한 줄에 노트가 여럿일 때 클릭한 자리에 띄우는 선택 팝업. 고른 노트를 Tool Window에서 선택한다. */
internal object NoteChooser {
    fun show(event: MouseEvent, project: Project, notes: List<CachedNote>) {
        JBPopupFactory.getInstance().createListPopup(Step(project, notes)).show(RelativePoint(event))
    }

    private class Step(private val project: Project, notes: List<CachedNote>) :
        BaseListPopupStep<CachedNote>(JNoteBundle.message("gutter.chooser.title"), notes) {
        override fun getTextFor(value: CachedNote): String = value.title

        override fun getIconFor(value: CachedNote): Icon =
            if (value.status == NoteStatus.APPROX) GutterIcons.APPROX else GutterIcons.NOTE

        override fun onChosen(selectedValue: CachedNote, finalChoice: Boolean): PopupStep<*>? =
            doFinalStep { NoteSelection.request(project, selectedValue.id) }
    }
}
