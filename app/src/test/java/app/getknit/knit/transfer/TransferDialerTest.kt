package app.getknit.knit.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/** The receiver's hold on the link after its verdict (`TransferDialer.answer`): long enough to deliver it, and bounded. */
class TransferDialerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun dialer(lingerMs: Long) =
        TransferDialer(
            FakeDirectWifi(SideLog("bob")),
            TransferTimings(verdictLingerMs = lingerMs),
            System::currentTimeMillis,
        ) {}

    /** A host that reads the verdict and then never closes: it keeps writing, one byte at a time or a flood. */
    private fun hostThatNeverCloses(
        server: ServerSocket,
        verdict: IntArray,
        gapMs: Long,
        chunk: Int,
    ) = thread(name = "host") {
        runCatching {
            server.accept().use { s ->
                verdict[0] = s.getInputStream().read()
                val bytes = ByteArray(chunk)
                while (true) {
                    s.getOutputStream().write(bytes)
                    if (gapMs > 0) Thread.sleep(gapMs)
                }
            }
        }
    }

    private fun runAgainst(
        gapMs: Long,
        chunk: Int,
        lingerMs: Long,
    ): Long {
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(loopback, 0))
            val verdict = intArrayOf(-2)
            val host = hostThatNeverCloses(server, verdict, gapMs, chunk)
            val took =
                Socket(loopback, server.localPort).use { socket ->
                    val started = System.nanoTime()
                    dialer(lingerMs).answer(socket, ok = true)
                    (System.nanoTime() - started) / 1_000_000
                }
            host.join(5_000)
            assertEquals("the host had its verdict", TransferStream.VERDICT_OK.toInt(), verdict[0])
            return took
        }
    }

    // A regressed bound hangs rather than fails: the timeout turns that into a red test that names itself.
    @Test(timeout = 10_000)
    fun aHostThatTricklesIsLetGoAtTheLingerNotPerRead() {
        // A byte just inside every read's timeout once held the link for a whole linger per read.
        val took = runAgainst(gapMs = 150, chunk = 1, lingerMs = 600)
        assertTrue("held $took ms", took in 500..1_500)
    }

    // A regressed bound hangs rather than fails: the timeout turns that into a red test that names itself.
    @Test(timeout = 10_000)
    fun aHostThatFloodsIsLetGoAtTheByteCapBeforeTheLinger() {
        val took = runAgainst(gapMs = 0, chunk = 4_096, lingerMs = 30_000)
        assertTrue("held $took ms", took < 10_000)
    }

    // A regressed bound hangs rather than fails: the timeout turns that into a red test that names itself.
    @Test(timeout = 10_000)
    fun aHostThatResetsEndsTheHoldQuietly() {
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(loopback, 0))
            val host =
                thread {
                    server.accept().apply {
                        getInputStream().read()
                        setSoLinger(true, 0)
                        close()
                    }
                }
            Socket(loopback, server.localPort).use { socket ->
                try {
                    dialer(lingerMs = 5_000).answer(socket, ok = true)
                } catch (e: SocketException) {
                    throw AssertionError("a reset escaped the hold", e)
                }
            }
            host.join(5_000)
        }
    }
}
