package app.getknit.knit.mesh.bluetooth.meshcore

import app.getknit.knit.mesh.lora.BondState
import app.getknit.knit.mesh.lora.GattResult
import app.getknit.knit.mesh.lora.meshcore.MeshcoreProto
import app.getknit.knit.mesh.lora.meshcore.NusDialResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MeshCore link's Android-free decisions (ADR 2026-11.gsqu, Stage 3c "GATT and pairing"). */
class NusLinkPolicyTest {
    @Test
    fun theMtuFloorIsTheFirmwaresFrameSize() {
        // SerialBLEInterface.cpp: BLEDevice::setMTU(MAX_FRAME_SIZE), MAX_FRAME_SIZE 176 (BaseSerialInterface.h).
        assertEquals(176, NusLinkPolicy.MIN_MTU)
        assertEquals(MeshcoreProto.MAX_FRAME, NusLinkPolicy.MIN_MTU)
    }

    @Test
    fun aWriteLongerThanMtuLessThreeIsRefusedNeverSplit() {
        assertTrue(NusLinkPolicy.writable(173, 176))
        assertFalse(NusLinkPolicy.writable(174, 176))
        assertTrue(NusLinkPolicy.writable(1, 176))
        assertTrue("a bigger MTU (an nRF52) takes a full frame", NusLinkPolicy.writable(176, 247))
    }

    @Test
    fun anUnbondedBoardIsNeverDialledInTheBackground() {
        assertTrue(NusLinkPolicy.backgroundAllowed(BondState.BONDED))
        assertFalse(NusLinkPolicy.backgroundAllowed(BondState.NONE))
        assertFalse(NusLinkPolicy.backgroundAllowed(BondState.BONDING))
        assertFalse(NusLinkPolicy.backgroundAllowed(BondState.UNKNOWN))
    }

    @Test
    fun aSubscribeThatTookIsNoEnd() {
        assertNull(NusLinkPolicy.subscribeEnd(GattResult.Ok(Unit), startedUnbonded = true, bondAfter = BondState.BONDED))
    }

    @Test
    fun anAuthRefusalAtTheSubscribeIsTerminalNeverABackoff() {
        for (status in listOf(5, 15, 137)) {
            assertEquals(
                NusDialResult.BondRefused(status),
                NusLinkPolicy.subscribeEnd(GattResult.Failed(status), startedUnbonded = true, bondAfter = BondState.NONE),
            )
            assertEquals(
                "on a bonded board too: the session reads it as a stale bond",
                NusDialResult.BondRefused(status),
                NusLinkPolicy.subscribeEnd(GattResult.Failed(status), startedUnbonded = false, bondAfter = BondState.BONDED),
            )
        }
    }

    @Test
    fun aCloseUnderAnUnbondedSubscribeIsAPairingThatNeverHappened() {
        assertEquals(
            NusDialResult.BondRefused(NusLinkPolicy.STATUS_CLOSED_UNBONDED),
            NusLinkPolicy.subscribeEnd(GattResult.Closed, startedUnbonded = true, bondAfter = BondState.NONE),
        )
        // Bonded by the time it closed, or bonded all along: an ordinary drop, backed off.
        assertEquals(
            NusDialResult.Failed(-1, "subscribe"),
            NusLinkPolicy.subscribeEnd(GattResult.Closed, startedUnbonded = true, bondAfter = BondState.BONDED),
        )
        assertEquals(
            NusDialResult.Failed(-1, "subscribe"),
            NusLinkPolicy.subscribeEnd(GattResult.Closed, startedUnbonded = false, bondAfter = BondState.NONE),
        )
    }

    @Test
    fun otherFailuresBackOff() {
        assertEquals(
            NusDialResult.Failed(133, "subscribe"),
            NusLinkPolicy.subscribeEnd(GattResult.Failed(133), startedUnbonded = false, bondAfter = BondState.BONDED),
        )
        assertEquals(
            NusDialResult.Failed(NusLinkPolicy.STATUS_TIMEOUT, "subscribe"),
            NusLinkPolicy.subscribeEnd(GattResult.Timeout, startedUnbonded = true, bondAfter = BondState.NONE),
        )
    }

    @Test
    fun ourStatusesNeverCollideWithTheStacksOrEachOther() {
        val ours =
            listOf(
                NusLinkPolicy.STATUS_STACK_THREW,
                NusLinkPolicy.STATUS_TOO_LONG,
                NusLinkPolicy.STATUS_OVERFLOW,
                NusLinkPolicy.STATUS_CLOSED_UNBONDED,
                NusLinkPolicy.STATUS_TIMEOUT,
                NusLinkPolicy.STATUS_UNBONDED,
            )
        assertEquals(ours.size, ours.toSet().size)
        assertTrue(ours.all { it < -1 })
    }
}
