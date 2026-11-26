package io.github.driflux.jnote.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.model.Tag

/** 태그 이름 바꾸기와 삭제 확인 대화상자. 판단은 [TagNameRules]에 있고 여기는 띄우기만 한다. UI 스레드에서 부른다. */
internal object TagDialogs {
    /** 새 이름, 취소하면 null. 쓸 수 없는 이름은 입력란 아래 오류로 막는다. */
    fun askNewName(project: Project, tag: Tag, all: List<Tag>): String? {
        val validator = object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? =
                TagNameRules.check(inputString, tag, all)?.let { JNoteBundle.message(it.messageKey) }

            override fun checkInput(inputString: String): Boolean = TagNameRules.check(inputString, tag, all) == null

            override fun canClose(inputString: String): Boolean = checkInput(inputString)
        }
        return Messages.showInputDialog(
            project,
            JNoteBundle.message("toolWindow.tag.rename.prompt", tag.name),
            JNoteBundle.message("toolWindow.tag.rename.title"),
            null,
            tag.name,
            validator,
        )
    }

    fun confirmDelete(project: Project, tag: Tag): Boolean = Messages.showYesNoDialog(
        project,
        JNoteBundle.message("toolWindow.tag.delete.message", tag.name),
        JNoteBundle.message("toolWindow.tag.delete.title"),
        JNoteBundle.message("toolWindow.tag.delete.confirm"),
        Messages.getCancelButton(),
        Messages.getWarningIcon(),
    ) == Messages.YES
}
