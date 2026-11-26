package io.github.driflux.jnote.gutter

import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * 파일별 노트 캐시. 처음 조회하면 [loader]를 한 번 돌리고, 끝날 때까지는 아직 모른다는 뜻의 null을 돌려준다.
 * 이후 조회는 해시 조회뿐이라 노트가 없는 파일도 비용이 거의 없다.
 *
 * [invalidate]는 항목을 지우지 않고 낡은 것으로만 표시한다. 다음 조회는 낡은 값을 돌려주면서 다시 읽고,
 * 값이 달라졌을 때만 onChanged를 부른다. 읽는 도중 무효화되면 그 결과는 버리고 다시 읽는다.
 * 실패한 읽기는 다음 무효화 전까지 다시 시도하지 않는다. 하이라이팅마다 실패를 되풀이하지 않기 위해서다.
 */
internal class FileNoteCache(
    private val loader: (String) -> CompletableFuture<List<CachedNote>>,
    private val callbackExecutor: Executor = AppExecutorUtil.getAppExecutorService(),
    private val maxFiles: Int = MAX_FILES,
) {
    private class Entry {
        // 마지막으로 읽은 값. 한 번도 읽지 못했으면 null
        var notes: List<CachedNote>? = null

        // 마지막으로 끝난 읽기(실패 포함)의 버전
        var version = -1
        var loading = false
    }

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private var version = 0
    private val inFlight = AtomicInteger()

    /** 진행 중인 읽기가 없다. 테스트가 늦은 읽기를 기다리는 데 쓴다. */
    val isIdle: Boolean get() = inFlight.get() == 0

    /**
     * 캐시된 노트를 돌려준다. 읽어야 하면 읽기를 시작하고, 끝나면 [callbackExecutor]에서
     * 값이 달라졌을 때 [onChanged]를, 실패했을 때 [onFailed]를 부른다. 읽는 중에 온 조회의 콜백은 버려진다.
     */
    fun lookup(relPath: String, onChanged: () -> Unit, onFailed: (Throwable) -> Unit): List<CachedNote>? {
        var startVersion = -1
        val notes: List<CachedNote>?
        val entry: Entry
        synchronized(lock) {
            // 열어 본 파일이 끝없이 쌓이지 않도록 한계를 넘으면 처음부터 다시 채운다
            if (entries.size >= maxFiles && relPath !in entries) entries.clear()
            entry = entries.getOrPut(relPath) { Entry() }
            notes = entry.notes
            if (!entry.loading && entry.version != version) {
                entry.loading = true
                startVersion = version
            }
        }
        if (startVersion >= 0) load(relPath, entry, startVersion, onChanged, onFailed)
        return notes
    }

    fun contains(relPath: String): Boolean = synchronized(lock) { relPath in entries }

    fun invalidate() {
        synchronized(lock) { version++ }
    }

    /** 항목을 모두 버린다. 다른 DB로 바뀌었을 때처럼 낡은 값조차 보여 주면 안 될 때 쓴다. */
    fun clear() {
        synchronized(lock) {
            version++
            entries.clear()
        }
    }

    private fun load(relPath: String, entry: Entry, startVersion: Int, onChanged: () -> Unit, onFailed: (Throwable) -> Unit) {
        inFlight.incrementAndGet()
        val future = try {
            loader(relPath)
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }
        future.whenCompleteAsync({ loaded, error ->
            try {
                finish(relPath, entry, startVersion, loaded, error, onChanged, onFailed)
            } finally {
                inFlight.decrementAndGet()
            }
        }, callbackExecutor)
    }

    private fun finish(
        relPath: String,
        entry: Entry,
        startVersion: Int,
        loaded: List<CachedNote>?,
        error: Throwable?,
        onChanged: () -> Unit,
        onFailed: (Throwable) -> Unit,
    ) {
        var reloadVersion = -1
        var changed = false
        synchronized(lock) {
            // 한계에 걸리거나 clear로 버려진 항목이면 이 읽기를 기다리는 곳이 없다
            if (entries[relPath] !== entry) return
            if (startVersion != version) {
                // 읽는 사이 무효화되었다. 이 결과는 이미 낡았으니 버리고 지금 버전으로 다시 읽는다
                reloadVersion = version
            } else {
                entry.version = startVersion
                entry.loading = false
                if (error == null) {
                    // 처음 읽은 빈 목록은 그릴 것이 없으므로 바뀐 것으로 치지 않는다
                    changed = entry.notes.orEmpty() != loaded
                    entry.notes = loaded
                }
            }
        }
        when {
            reloadVersion >= 0 -> load(relPath, entry, reloadVersion, onChanged, onFailed)
            error != null -> onFailed(error)
            changed -> onChanged()
        }
    }

    private companion object {
        const val MAX_FILES = 1000
    }
}
