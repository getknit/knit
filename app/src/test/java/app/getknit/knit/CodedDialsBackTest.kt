package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.AdvertOrder
import app.getknit.knit.mesh.bluetooth.CodedPhyPolicy
import app.getknit.knit.mesh.bluetooth.ColdAction
import app.getknit.knit.mesh.bluetooth.ColdSighting
import app.getknit.knit.mesh.bluetooth.LinkPhy
import app.getknit.knit.mesh.bluetooth.PhyTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CodedPhyPolicy.dialsBack] against [CodedPhyPolicy.drives] (ADR 2026-11.xp6d): who dials back or hunts after a drop is
 * a dial role, judged on advertised forms; who steps a live link's PHY stays on the proven ids.
 */
class CodedDialsBackTest {
    private val tuning = PhyTuning()

    @Test
    fun whoDialsBackReadsFormsWhileWhoStepsThePhyReadsTheProvenIds() {
        // ADR 2026-11.xp6d: the step role stays on the real ids; the dial-back role is iDial on the last known form.
        val clearB = AdvertOrder.Mine.clear("b")
        assertTrue("the step role: the larger real id", CodedPhyPolicy.drives("b", "a"))
        assertTrue("a clear form: the classic order", CodedPhyPolicy.dialsBack(clearB, AdvertOrder.Form.clear("a"), "b", "a"))
        assertFalse(CodedPhyPolicy.dialsBack(AdvertOrder.Mine.clear("a"), AdvertOrder.Form.clear("b"), "a", "b"))
        assertTrue("no form known: the classic order on the real ids", CodedPhyPolicy.dialsBack(clearB, null, "b", "a"))
        val blindedA = AdvertOrder.Form("zzzzaaaaaaaaaaaaaaaaaaaaaa", blinded = true)
        assertFalse("a clear phone never dials a blinded advert back", CodedPhyPolicy.dialsBack(clearB, blindedA, "b", "a"))
        assertTrue("though it still steps the PHY of the live link", CodedPhyPolicy.drives("b", "a"))
        // So at a drop at range against a blinding peer, the clear side advertises fast rather than hunting.
        val dialsBack = CodedPhyPolicy.dialsBack(clearB, blindedA, "b", "a")
        assertTrue(CodedPhyPolicy.fastAdvertAfterDrop("b", "a", LinkPhy.CODED, -99, tuning, dialsBack))
        assertFalse(CodedPhyPolicy.huntAfterDrop("b", "a", LinkPhy.CODED, -99, tuning, dialsBack))
        assertEquals(
            ColdAction.FAST_ADVERT,
            CodedPhyPolicy.coldHunt(
                "b",
                ColdSighting("a", -60.0, false, false, false, true),
                true,
                false,
                tuning.copy(coldHunt = true),
                dialsBack = dialsBack,
            ),
        )
    }

    @Test
    fun aDropWithPresenceExpiredStillPicksTheSideTheLinksFormSaysDials() {
        // bluetooth's part-1 review: at a drop at range presence has usually lost the peer; the link recorded its form.
        val clearB = AdvertOrder.Mine.clear("b")
        val blindedA = AdvertOrder.Form("zzzzaaaaaaaaaaaaaaaaaaaaaa", blinded = true)
        assertFalse(
            "the link's form says the blinding peer dials, presence gone",
            CodedPhyPolicy.dialsBackAfterDrop(clearB, linkForm = blindedA, heard = null, localNodeId = "b", peerNodeId = "a"),
        )
        assertTrue(
            "without it, the real ids would have the clear side hunt for nothing",
            CodedPhyPolicy.dialsBackAfterDrop(clearB, linkForm = null, heard = null, localNodeId = "b", peerNodeId = "a"),
        )
        assertFalse(
            "presence is the second source",
            CodedPhyPolicy.dialsBackAfterDrop(clearB, linkForm = null, heard = blindedA, localNodeId = "b", peerNodeId = "a"),
        )
        assertTrue(
            "the link's form wins over a stale presence one",
            CodedPhyPolicy.dialsBackAfterDrop(
                clearB,
                linkForm = AdvertOrder.Form.clear("a"),
                heard = blindedA,
                localNodeId = "b",
                peerNodeId = "a",
            ),
        )
    }
}
