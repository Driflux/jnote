package io.github.driflux.jnote

import com.intellij.util.messages.Topic

/** 노트가 추가, 수정, 삭제되었음을 알리는 앱 레벨 토픽. 구독자는 목록을 다시 읽는다. */
fun interface NotesChangedListener {
    fun notesChanged()
}

object NotesChanged {
    @JvmField
    val TOPIC: Topic<NotesChangedListener> = Topic.create("JNote notes changed", NotesChangedListener::class.java)
}
