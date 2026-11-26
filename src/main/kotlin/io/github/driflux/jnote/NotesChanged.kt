package io.github.driflux.jnote

import com.intellij.util.messages.Topic

fun interface NotesChangedListener {
    fun notesChanged()
}

object NotesChanged {
    @JvmField
    val TOPIC: Topic<NotesChangedListener> = Topic.create("JNote notes changed", NotesChangedListener::class.java)
}
