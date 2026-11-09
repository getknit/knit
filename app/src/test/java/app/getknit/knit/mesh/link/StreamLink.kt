package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.crypto.LinkSeal
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A [LinkSocket] over two streams, as a transport's is: [input] one cached buffered stream. For the handshake tests. */
internal class StreamLink(
    override val input: InputStream,
    override val output: OutputStream,
) : LinkSocket {
    constructor(bytes: ByteArray, output: OutputStream) : this(BufferedInputStream(ByteArrayInputStream(bytes)), output)

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }
}

/** A well-formed `SEALED` record of [n] plaintext bytes under a key nobody holds: right shape, never opens. */
internal fun sealedFrameOf(n: Int): ByteArray = LinkSeal.seal(ByteArray(32), 0, ByteArray(n))

/**
 * Runs both ends of a two-thread pipe test and waits on whichever finishes first. When one end throws or the bound
 * passes, [onFailure] runs (close the pipes, so the other end's blocked read ends too) and the test fails with the
 * thrower's exception, the other end's attached, rather than a bare timeout on the end that was waiting for it.
 */
internal fun <A, B> bothEnds(
    first: Callable<A>,
    second: Callable<B>,
    timeoutS: Long,
    onFailure: () -> Unit,
): Pair<A, B> {
    val pool = Executors.newFixedThreadPool(2)
    try {
        val done = ExecutorCompletionService<Any?>(pool)
        val a = done.submit { first.call() }
        val b = done.submit { second.call() }
        repeat(2) {
            val next = done.poll(timeoutS, TimeUnit.SECONDS)
            if (next == null) {
                onFailure()
                throw AssertionError("a pipe end did not finish in $timeoutS s (first done: ${a.isDone}, second done: ${b.isDone})")
            }
            try {
                next.get()
            } catch (e: ExecutionException) {
                onFailure()
                val other = if (next === a) b else a
                val failure = AssertionError("${if (next === a) "first" else "second"} pipe end threw", e.cause)
                runCatching { other.get(timeoutS, TimeUnit.SECONDS) }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
        @Suppress("UNCHECKED_CAST")
        return (a.get() as A) to (b.get() as B)
    } finally {
        pool.shutdownNow()
    }
}
