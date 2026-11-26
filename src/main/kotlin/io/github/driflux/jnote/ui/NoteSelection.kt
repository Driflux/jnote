package io.github.driflux.jnote.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import io.github.driflux.jnote.NoteSelectRequested
import java.util.concurrent.atomic.AtomicReference

/**
 * 아직 처리되지 않은 선택 요청 하나. Tool Window를 처음 여는 요청은 패널이 만들어지기 전에 발행되어
 * 구독자가 없으므로, 새 패널이 첫 읽기를 마친 뒤 여기서 읽어 선택한다. 선택에 성공한 쪽이 지우고,
 * 목록에 없어 못 고른 요청은 다음 요청이 덮어쓸 때까지 남아 목록이 바뀌면 다시 시도된다.
 */
@Service(Service.Level.PROJECT)
internal class PendingNoteSelection {
    private val id = AtomicReference<Long?>()

    fun set(noteId: Long) = id.set(noteId)

    fun peek(): Long? = id.get()

    /** 같은 요청일 때만 지운다. 그 사이 새 요청이 들어왔다면 그대로 둔다. */
    fun clear(noteId: Long) {
        id.compareAndSet(noteId, null)
    }

    fun take(): Long? = id.getAndSet(null)
}

internal object NoteSelection {
    private const val TOOL_WINDOW_ID = "JNote"

    /**
     * Tool Window를 열고 [noteId] 노트를 선택해 달라고 요청한다. EDT에서 부른다.
     * 요청을 먼저 맡겨 두고 창을 연 뒤 알리므로, 패널이 이미 있든 이제 만들어지든 선택된다.
     */
    fun request(project: Project, noteId: Long, openToolWindow: (Project) -> Unit = ::open) {
        project.service<PendingNoteSelection>().set(noteId)
        openToolWindow(project)
        ApplicationManager.getApplication().messageBus.syncPublisher(NoteSelectRequested.TOPIC).noteSelectRequested(project, noteId)
    }

    private fun open(project: Project) {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
    }
}
