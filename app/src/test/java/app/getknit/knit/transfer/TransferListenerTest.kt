package app.getknit.knit.transfer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The host's rendezvous: one listener per group address, the first client with a good proof wins. */
class TransferListenerTest {
    private val key = ByteArray(TransferStream.KEY_BYTES) { it.toByte() }
    private val v4: InetAddress = InetAddress.getByName("127.0.0.1")
    private val v6: InetAddress = InetAddress.getByName("::1")

    /**
     * One listener wins and the rest are closed under the others, whatever each of them was doing. A listener whose
     * thread had not yet reached its first accept when the winner closed it used to throw on its `soTimeout`, and that
     * failed the whole rendezvous with the receiver already connected — the "Socket is closed" send failure a loaded
     * runner produced in `TransferManagerTest`. The clock holds whichever listener asks it first until the other one
     * has asked too, and holds that one until the first has won and the listeners are closed, so both always start and
     * the late one always meets a closed socket. Holding only the second was not enough: the winner could cancel the
     * other listener's coroutine before it was dispatched, and the unfixed listener then passed 3 to 7 runs in 30 on
     * one core.
     */
    @Test
    fun aListenerClosedBeforeItsFirstAcceptLeavesTheWinnerStanding() =
        runBlocking<Unit> {
            assumeTrue(FakeDirectWifi.ipv6LoopbackUsable())
            val first = AtomicReference<Thread>()
            val second = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val clock = {
                val me = Thread.currentThread()
                if (first.compareAndSet(null, me)) {
                    check(second.await(10, TimeUnit.SECONDS)) { "the other listener never started" }
                } else if (first.get() !== me) {
                    second.countDown()
                    closed.await(10, TimeUnit.SECONDS)
                }
                System.currentTimeMillis()
            }
            val group = HostedGroup(listOf(GroupAddress(v4, prefixLength = 8), GroupAddress(v6, prefixLength = 128)), 5180)
            val port = FakeDirectWifi.freeLoopbackPort()
            val listener = TransferListener(group, port, Dispatchers.IO, clock, windowMs = 10_000, soTimeoutMs = 2_000) {}
            listener.bind(bufferBytes = 64 * 1024)
            // A client in each backlog: whichever listener asks the clock first finds one waiting.
            val clients =
                listOf(v4, v6).map { address ->
                    Socket().apply {
                        connect(InetSocketAddress(address, port), 1_000)
                        // Set here, not in its reader: a reader first dispatched after `finally` meets a closed socket.
                        soTimeout = 5_000
                        getOutputStream().apply {
                            write(TransferStream.clientProof(key, "t1"))
                            flush()
                        }
                    }
                }
            try {
                val accepted = async(Dispatchers.IO) { listener.accept(key, "t1") }
                // Closing a listener resets the client still in its backlog: that is the sign the winner has closed it.
                val reset = CompletableDeferred<Unit>()
                clients.forEach { client ->
                    launch(Dispatchers.IO) {
                        runCatching { client.getInputStream().read() }
                        reset.complete(Unit)
                    }
                }
                withTimeout(5_000) { reset.await() }
                closed.countDown()
                val socket = withTimeout(5_000) { accepted.await() }
                assertNotNull("the client that proved itself is handed over", socket)
                socket?.close()
            } finally {
                closed.countDown()
                clients.forEach { runCatching { it.close() } }
                listener.close()
            }
        }
}
