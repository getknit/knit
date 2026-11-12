@file:Suppress("MagicNumber") // byte counts and counters are the test's subject

package app.getknit.knit.usbcontrol

import app.getknit.knit.mesh.crypto.LinkSeal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSealTest {
    private val psk = ByteArray(32) { 9 }
    private val handle = ByteArray(16) { 5 }

    private class Pair(
        val host: UsbSeal.Keys,
        val phone: UsbSeal.Keys,
    )

    private fun session(phonePsk: ByteArray = psk): Pair {
        val h = LinkSeal.Ephemeral.fixed(ByteArray(32) { 0x40 })
        val p = LinkSeal.Ephemeral.fixed(ByteArray(32) { 0x60 })
        val eH = h.pub
        val eP = p.pub
        val host = UsbSeal.sessionKeys(UsbSeal.Role.HOST, psk, UsbSeal.sharedSecret(h, eP), eH, eP, handle)
        val phone = UsbSeal.sessionKeys(UsbSeal.Role.PHONE, phonePsk, UsbSeal.sharedSecret(p, eH), eH, eP, handle)
        return Pair(host, phone)
    }

    private fun refused(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("expected a refusal, got $thrown", thrown is UsbSealException)
    }

    @Test
    fun eachEndsSendKeyIsTheOthersReceiveKeyAndTheDirectionsDiffer() {
        val s = session()
        assertArrayEquals(s.host.send, s.phone.recv)
        assertArrayEquals(s.phone.send, s.host.recv)
        assertTrue(!(s.host.send contentEquals s.host.recv))
    }

    @Test
    fun framesOpenInOrderAtTheirCounters() {
        val s = session()
        val sealer = UsbSeal.Sealer(s.host.send)
        val opener = UsbSeal.Opener(s.phone.recv)
        for (i in 0 until 3) {
            assertEquals(i.toLong(), sealer.counter)
            assertEquals("frame $i", opener.open(sealer.seal("frame $i".encodeToByteArray())).decodeToString())
        }
        assertEquals(3L, opener.counter)
    }

    @Test
    fun aFlippedBitADroppedFrameAReplayOrTheWrongDirectionNeverOpens() {
        val s = session()
        val sealer = UsbSeal.Sealer(s.host.send)
        val f0 = sealer.seal("zero".encodeToByteArray())
        val f1 = sealer.seal("one".encodeToByteArray())
        refused { UsbSeal.Opener(s.phone.recv).open(f0.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }) }
        refused { UsbSeal.Opener(s.phone.recv).open(f1) } // frame 0 dropped
        refused { UsbSeal.Opener(s.phone.send).open(f0) } // the phone's own direction
        refused { UsbSeal.Opener(s.phone.recv).open(f0.copyOf().also { it[3] = (it[3] + 1).toByte() }) } // the length changed
        val opener = UsbSeal.Opener(s.phone.recv)
        opener.open(f0)
        refused { opener.open(f0) } // replayed
    }

    @Test
    fun theWrongPskOpensNothing() {
        val s = session(phonePsk = ByteArray(32) { 8 })
        refused { UsbSeal.Opener(s.phone.recv).open(UsbSeal.Sealer(s.host.send).seal("hi".encodeToByteArray())) }
    }

    @Test
    fun aLowOrderKeyShareIsRefused() {
        for (u in listOf(ByteArray(32), ByteArray(32).also { it[0] = 1 })) {
            refused { UsbSeal.sharedSecret(LinkSeal.Ephemeral.fixed(ByteArray(32) { 0x40 }), u) }
        }
        refused { UsbSeal.sharedSecret(LinkSeal.Ephemeral.fixed(ByteArray(32) { 0x40 }), ByteArray(31)) }
    }

    @Test
    fun aClearFrameIsNotOpened() {
        refused { UsbSeal.Opener(ByteArray(32)).open(UsbControlFrames.encode("""{"op":"open","pad":"xxxxxxxxxxxxxxxx"}""")) }
    }
}
