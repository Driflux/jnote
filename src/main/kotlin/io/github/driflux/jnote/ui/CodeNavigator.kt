package io.github.driflux.jnote.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.driflux.jnote.model.Note
import java.util.concurrent.CompletableFuture

/** "코드로 이동". 파일 찾기는 풀 스레드에서, 열기는 EDT에서 한다. */
internal object CodeNavigator {
    /** 이동했으면 true. 파일이 없으면 알림을 띄우고 false. 이동할 곳이 없는 노트도 false. */
    fun goTo(project: Project, note: Note): CompletableFuture<Boolean> {
        val relPath = note.relPath ?: return CompletableFuture.completedFuture(false)
        val line = (note.startLine ?: 1) - 1
        val done = CompletableFuture<Boolean>()
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val file = CodeTargets.find(project, relPath)
                ApplicationManager.getApplication().invokeLater({
                    try {
                        if (file == null) {
                            ToolWindowNotifier.fileMissing(project, relPath)
                            done.complete(false)
                        } else {
                            OpenFileDescriptor(project, file, line, 0).navigate(true)
                            done.complete(true)
                        }
                    } catch (e: Throwable) {
                        done.completeExceptionally(e)
                    }
                }, project.disposed)
            } catch (e: Throwable) {
                done.completeExceptionally(e)
            }
        }
        return done
    }
}
