package app.getknit.knit.ui.verify

import app.getknit.knit.data.PeerRepository
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.VerifyPayload
import app.getknit.knit.ui.peer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Covers the standalone Verify-contact scan logic: a self-certifying code pins + verifies its peer, a
 * forged/malformed code is refused, and our own code is a no-op. Codes are built the way a real device's
 * QR is — [VerifyPayload.encode] of a bundle and the node id it self-certifies to
 * ([NodeId.fromPublicKeyBundle]).
 */
class VerifyContactViewModelTest {
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)

    // A self-consistent local identity: the node id derives from the bundle, so a "scan my own code" case
    // (encode(myId, myBundle)) passes the self-certifying check and hits the SELF branch.
    private val myBundle = "MY-BUNDLE"
    private val myId = NodeId.fromPublicKeyBundle(myBundle)

    // A peer's self-consistent identity.
    private val peerBundle = "PEER-BUNDLE"
    private val peerId = NodeId.fromPublicKeyBundle(peerBundle)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns myId
        every { identity.publicKeyBundle() } returns myBundle
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = VerifyContactViewModel(peers, identity)

    @Test
    fun scanningANewPeerPinsAndVerifiesItWithoutClobberingLwwOrder() =
        runTest {
            val vm = vm()
            coEvery { peers.find(peerId) } returns null

            vm.onScanned(VerifyPayload.encode(peerId, peerBundle))
            advanceUntilIdle()

            assertEquals(VerifyResult.VERIFIED, vm.scanResult.value)
            // Pinned + verified, with updatedAt left at 0 so a later real profile frame still wins the
            // last-writer-wins check in handleProfile and can fill in the name/avatar.
            coVerify {
                peers.upsert(
                    match { it.nodeId == peerId && it.pubKey == peerBundle && it.verified && it.updatedAt == 0L },
                )
            }
        }

    @Test
    fun scanningAKnownPeerWithTheSameKeyJustMarksVerified() =
        runTest {
            val vm = vm()
            coEvery { peers.find(peerId) } returns peer(peerId, pubKey = peerBundle)

            vm.onScanned(VerifyPayload.encode(peerId, peerBundle))
            advanceUntilIdle()

            assertEquals(VerifyResult.VERIFIED, vm.scanResult.value)
            coVerify { peers.setVerified(peerId, true) }
            coVerify(exactly = 0) { peers.upsert(any()) }
        }

    @Test
    fun scanningOurOwnCodeIsANoOp() =
        runTest {
            val vm = vm()

            vm.onScanned(VerifyPayload.encode(myId, myBundle))
            advanceUntilIdle()

            assertEquals(VerifyResult.SELF, vm.scanResult.value)
            coVerify(exactly = 0) { peers.upsert(any()) }
            coVerify(exactly = 0) { peers.setVerified(any(), any()) }
        }

    @Test
    fun aMalformedCodeIsInvalid() =
        runTest {
            val vm = vm()

            vm.onScanned("definitely-not-a-knit-code")
            advanceUntilIdle()

            assertEquals(VerifyResult.INVALID, vm.scanResult.value)
            coVerify(exactly = 0) { peers.upsert(any()) }
            coVerify(exactly = 0) { peers.setVerified(any(), any()) }
        }

    @Test
    fun aCodeWhoseKeyDoesNotDeriveToItsNodeIdIsInvalid() =
        runTest {
            val vm = vm()

            // Claims peerId but carries someone else's key — not self-certifying, so it's refused.
            vm.onScanned(VerifyPayload.encode(peerId, "SOMEONE-ELSES-KEY"))
            advanceUntilIdle()

            assertEquals(VerifyResult.INVALID, vm.scanResult.value)
            coVerify(exactly = 0) { peers.upsert(any()) }
            coVerify(exactly = 0) { peers.setVerified(any(), any()) }
        }
}
