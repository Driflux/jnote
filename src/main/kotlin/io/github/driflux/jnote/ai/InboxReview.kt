package io.github.driflux.jnote.ai

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.driflux.jnote.JNoteBundle
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * 알림의 Review. 파일마다 선점(claim)한 뒤 읽어 가져오기 흐름에 넘기고, 흐름이 끝나면 처리된 파일은 done으로,
 * 나머지(거절, 취소, 읽기 실패, 잘못된 문서)는 inbox로 되돌린다. 파일 이동은 백그라운드에서 한다.
 * 인자는 테스트에서 흐름 시작, 실행 스레드, 다시 확인을 바꿔 끼우기 위한 것이다.
 */
class InboxReview(
    private val inbox: Inbox = Inbox(),
    private val start: (Project, List<ImportSource>, (ImportOutcome) -> Unit) -> Unit = { project, sources, done ->
        ImportFlow(project).start(sources, done)
    },
    private val background: Executor = AppExecutorUtil.getAppExecutorService(),
    private val recheck: (Project) -> Unit = { InboxCheck.getInstance().request(it) },
) {
    fun review(project: Project, files: List<InboxFile>) {
        val claims = files.map { AtomicReference<ClaimedFile?>() }
        val sources = files.mapIndexed { i, file ->
            ImportSource(file.path.fileName.toString()) {
                // 읽기는 흐름의 백그라운드 작업 안에서 일어나므로 선점도 거기서 한다
                val claimed = inbox.claim(file) ?: error(JNoteBundle.message("import.problem.inboxTaken"))
                claims[i].set(claimed)
                Files.readString(claimed.path)
            }
        }
        start(project, sources) { outcome ->
            background.execute {
                claims.forEachIndexed { i, ref ->
                    val claimed = ref.get() ?: return@forEachIndexed
                    try {
                        val processed = outcome is ImportOutcome.Finished && i in outcome.decodedSources
                        if (processed) inbox.archive(claimed) else inbox.release(claimed)
                    } catch (e: Exception) {
                        thisLogger().warn("cannot move ${claimed.path}", e)
                    }
                }
                recheck(project)
            }
        }
    }
}
