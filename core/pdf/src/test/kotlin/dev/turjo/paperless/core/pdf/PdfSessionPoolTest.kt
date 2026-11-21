package dev.turjo.paperless.core.pdf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class PdfSessionPoolTest {
    private class Handle(val name: String)
    private val live = mutableListOf<Handle>()
    private val closed = mutableListOf<String>()
    private val pool = PdfSessionPool(2, { name: String ->
        check(live.size < 2)
        Handle(name).also(live::add)
    }, { handle ->
        check(live.remove(handle))
        closed.add(handle.name)
    })

    @Test
    fun reusesHandlesAndEvictsLeastRecentlyUsed() = runBlocking {
        val first = pool.use("a") { it }
        pool.use("b") { }
        assertSame(first, pool.use("a") { it })
        pool.use("c") { }
        assertEquals(listOf("b"), closed)
        assertEquals(listOf("a", "c"), live.map { it.name })
    }

    @Test
    fun skipsBusyHandles() = runBlocking {
        pool.use("a") {
            pool.use("b") { }
            pool.use("c") { }
            assertEquals(listOf("b"), closed)
        }
    }

    @Test
    fun waitsForCapacityAndDefersClosingReleasedHandle() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val running = launch {
            pool.use("a") {
                pool.use("b") {
                    entered.complete(Unit)
                    finish.await()
                }
            }
        }
        entered.await()
        val waiting = launch { pool.use("c") { } }
        yield()
        assertFalse(waiting.isCompleted)
        pool.releaseWhere { it == "b" }
        assertEquals(emptyList<String>(), closed)
        finish.complete(Unit)
        running.join()
        waiting.join()
        assertEquals(listOf("b"), closed)
    }
}
