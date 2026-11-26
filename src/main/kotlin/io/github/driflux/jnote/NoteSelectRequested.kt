package io.github.driflux.jnote

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/** 노트를 Tool Window에서 골라 달라는 요청을 받는 쪽. 열려 있는 패널이 구독한다. */
fun interface NoteSelectRequestedListener {
    fun noteSelectRequested(project: Project, noteId: Long)
}

/** 거터 아이콘 클릭처럼 편집기 쪽에서 Tool Window의 선택을 바꾸고 싶을 때 쓰는 앱 레벨 토픽. */
object NoteSelectRequested {
    @JvmField
    val TOPIC: Topic<NoteSelectRequestedListener> =
        Topic.create("JNote note select requested", NoteSelectRequestedListener::class.java)
}
