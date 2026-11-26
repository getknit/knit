package app.getknit.knit.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** A direct link's socket work stays off the main thread, in the order it was asked for (the BYE before the close). */
class OffMainIoTest {
    /** An executor that only queues, so a test runs what it holds when it chooses. */
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    @Test
    fun `off the main thread a send runs where it was asked and answers for itself`() {
        val queue = Queue()
        val io = OffMainIo({ false }, queue)
        val ran = mutableListOf<String>()

        assertFalse(
            io.send {
                ran += "send"
                false
            },
        )
        io.close { ran += "close" }

        assertEquals(listOf("send", "close"), ran)
        assertTrue(queue.tasks.isEmpty())
    }

    @Test
    fun `on the main thread nothing runs where it was asked, and the BYE goes out before the close`() {
        val queue = Queue()
        val io = OffMainIo({ true }, queue)
        val ran = mutableListOf<String>()

        // A close as LinkSession does it: the BYE, then the port.
        assertTrue(
            io.send {
                ran += "bye"
                false
            },
        )
        io.close { ran += "close" }

        assertTrue("nothing ran on the caller's thread", ran.isEmpty())
        queue.drain()
        assertEquals(listOf("bye", "close"), ran)
    }

    @Test
    fun `the shared executor runs main-thread work on its own thread, in order`() {
        val io = OffMainIo({ true }, OffMainIo.shared)
        val threads = mutableListOf<Thread>()
        val ran = mutableListOf<String>()
        val done = CountDownLatch(1)

        io.send {
            synchronized(ran) {
                ran += "bye"
                threads += Thread.currentThread()
            }
            true
        }
        io.close {
            synchronized(ran) {
                ran += "close"
                threads += Thread.currentThread()
            }
            done.countDown()
        }

        assertTrue("ran=${synchronized(ran) { ran.toList() }}", done.await(10, TimeUnit.SECONDS))
        synchronized(ran) {
            assertEquals(listOf("bye", "close"), ran)
            assertTrue(threads.all { it.isDaemon && it.name == "knit-link-offmain" })
        }
    }
}
