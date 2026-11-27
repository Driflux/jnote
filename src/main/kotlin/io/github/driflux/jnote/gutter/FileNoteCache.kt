package io.github.driflux.jnote.gutter

import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

internal class FileNoteCache(
    private val loader: (String) -> CompletableFuture<List<CachedNote>>,
    private val callbackExecutor: Executor = AppExecutorUtil.getAppExecutorService(),
    private val maxFiles: Int = MAX_FILES,
) {
    private class Entry {
        var notes: List<CachedNote>? = null

        var version = -1
        var loading = false
    }

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private var version = 0
    private val inFlight = AtomicInteger()

    val isIdle: Boolean get() = inFlight.get() == 0

    fun lookup(relPath: String, onChanged: () -> Unit, onFailed: (Throwable) -> Unit): List<CachedNote>? {
        var startVersion = -1
        val notes: List<CachedNote>?
        val entry: Entry
        synchronized(lock) {
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

    fun peek(relPath: String): List<CachedNote>? = synchronized(lock) { entries[relPath]?.notes }

    fun contains(relPath: String): Boolean = synchronized(lock) { relPath in entries }

    fun invalidate() {
        synchronized(lock) { version++ }
    }

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
            if (entries[relPath] !== entry) return
            if (startVersion != version) {
                reloadVersion = version
            } else {
                entry.version = startVersion
                entry.loading = false
                if (error == null) {
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
