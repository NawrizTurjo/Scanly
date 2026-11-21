package dev.turjo.paperless.core.pdf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class PdfSessionPool<K, V>(
    private val capacity: Int,
    private val create: suspend (K) -> V,
    private val close: suspend (V) -> Unit,
) {
    private class Entry<V>(val value: V, var users: Int = 1, var released: Boolean = false)

    init {
        require(capacity > 0)
    }

    private val lock = Mutex()
    private val entries = LinkedHashMap<K, Entry<V>>(capacity, 0.75f, true)
    private var changed = CompletableDeferred<Unit>()

    suspend fun <T> use(key: K, block: suspend (V) -> T): T {
        while (true) {
            var entry: Entry<V>? = null
            try {
                val wait = lock.withLock {
                    val existing = entries[key]
                    if (existing != null) {
                        if (existing.released) return@withLock changed
                        existing.users++
                        entry = existing
                    } else {
                        if (entries.size == capacity) {
                            val idle = entries.entries.firstOrNull { it.value.users == 0 }
                                ?: return@withLock changed
                            remove(idle.key)
                        }
                        // Record ownership before cancellation can discard the result of opening.
                        withContext(NonCancellable) {
                            val opened = Entry(create(key))
                            entries[key] = opened
                            entry = opened
                        }
                    }
                    null
                }
                if (wait != null) {
                    wait.await()
                    continue
                }
                return block(checkNotNull(entry).value)
            } finally {
                entry?.let { used ->
                    withContext(NonCancellable) {
                        lock.withLock {
                            used.users--
                            if (used.users == 0) {
                                if (used.released) remove(key) else signalChange()
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun releaseWhere(predicate: (K) -> Boolean) = withContext(NonCancellable) {
        lock.withLock {
            for ((key, entry) in entries.toList()) {
                if (!predicate(key)) continue
                entry.released = true
                if (entry.users == 0) remove(key)
            }
        }
    }

    private suspend fun remove(key: K) {
        val entry = checkNotNull(entries.remove(key))
        try {
            withContext(NonCancellable) { close(entry.value) }
        } finally {
            signalChange()
        }
    }

    private fun signalChange() {
        changed.complete(Unit)
        changed = CompletableDeferred()
    }
}
