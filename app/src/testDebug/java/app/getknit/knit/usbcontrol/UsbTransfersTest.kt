@file:Suppress("MagicNumber") // sizes and offsets are the test's subject

package app.getknit.knit.usbcontrol

import app.getknit.knit.mesh.sha256Hex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTransfersTest {
    private var ids = 0
    private val transfers = UsbTransfers(newId = { "u${++ids}" }, chunk = 4, maxUpload = 10)

    private fun refusal(block: suspend () -> Unit): String? =
        runCatching { kotlinx.coroutines.runBlocking { block() } }.exceptionOrNull()?.let { (it as UsbControlError).code }

    @Test
    fun aFileIsSlicedAtTheChunkAndKeptUnderItsKey() =
        runTest {
            val file = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
            var opens = 0
            val open: suspend () -> ByteArray = {
                opens++
                file
            }
            val slices = listOf(0, 4, 8).map { transfers.read("a", it, open) }
            assertEquals(listOf(4, 4, 1), slices.map { it.bytes.size })
            assertEquals(listOf(false, false, true), slices.map { it.done })
            assertEquals(sha256Hex(file), slices.last().sha256)
            assertEquals(1, opens)
            transfers.read("b", 0, open)
            assertEquals("another key opens again", 2, opens)
        }

    @Test
    fun theWipeZeroesTheOpenedBytes() =
        runTest {
            val file = byteArrayOf(1, 2, 3)
            transfers.read("a", 0) { file }
            transfers.wipe()
            assertArrayEquals(ByteArray(3), file)
        }

    @Test
    fun forgettingTheOpenedFileZeroesItToo() =
        runTest {
            val file = byteArrayOf(1, 2, 3)
            transfers.read("a", 0) { file }
            transfers.forgetOpened()
            assertArrayEquals(ByteArray(3), file)
        }

    @Test
    fun anUploadIsHeldUntilConsumedAndHandsOutACopy() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        val id = transfers.start(6, sha256Hex(bytes))
        assertEquals(4, transfers.add(id, 0, bytes.copyOfRange(0, 4)).received)
        val last = transfers.add(id, 4, bytes.copyOfRange(4, 6))
        assertTrue(last.done)
        val copy = transfers.complete(id)
        assertArrayEquals(bytes, copy)
        copy.fill(0)
        assertArrayEquals("the held bytes are not the copy", bytes, transfers.complete(id))
        transfers.consume(id)
        assertEquals(Codes.NOT_FOUND, refusal { transfers.complete(id) })
    }

    @Test
    fun anUploadOverItsCapOrAChunkOverTheChunkIsRefused() {
        assertEquals(Codes.TOO_LARGE, refusal { transfers.start(11, sha256Hex(ByteArray(11))) })
        val id = transfers.start(10, sha256Hex(ByteArray(10)))
        assertEquals(Codes.BAD_REQUEST, refusal { transfers.add(id, 0, ByteArray(5)) })
        assertEquals(Codes.BAD_REQUEST, refusal { transfers.add(id, 0, ByteArray(0)) })
    }

    @Test
    fun aNewUploadReplacesTheOldOne() {
        val first = transfers.start(1, sha256Hex(byteArrayOf(1)))
        transfers.start(1, sha256Hex(byteArrayOf(2)))
        assertEquals(Codes.NOT_FOUND, refusal { transfers.add(first, 0, byteArrayOf(1)) })
    }
}
