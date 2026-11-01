package app.getknit.knit.mesh.link

import org.junit.Assume.assumeTrue
import java.lang.management.ManagementFactory

/**
 * A map header whose 8-byte count is 2^63 + 2^22: it reads as a negative `Long`, and `toInt()` turns it into 4,194,304.
 * Followed by one key/value pair, so a decoder that sizes a list from the count allocates for millions of entries
 * before it finds the input ends.
 */
internal val MAP_COUNT_PAST_LONG_MAX: ByteArray =
    byteArrayOf(0xBB.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0x40, 0, 0, 0x01, 0x01)

/** Bytes the current thread allocated while [block] ran; the test is skipped on a JVM that cannot count them. */
internal fun allocatedBy(block: () -> Unit): Long {
    val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    assumeTrue(threads.isThreadAllocatedMemorySupported && threads.isThreadAllocatedMemoryEnabled)
    val before = threads.currentThreadAllocatedBytes
    block()
    return threads.currentThreadAllocatedBytes - before
}
