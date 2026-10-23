package app.getknit.knit.mesh.spool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public relay rules (ADR 2026-10.8kfx, knit-spool-coordinator ADR 2026-10.txjf): which hosts are directory relays,
 * how the directory reads, and which home a node id picks. The pick's vectors were computed by a Python script of ours
 * and checked by the coordinator's own Node implementation, written from ADR 2026-10.txjf's text alone; they are the
 * ones `docs/SPOOL_PROTOCOL.md` §5.1 pins, and the two change together.
 */
class PublicSpoolsTest {
    private fun spool(
        n: Int,
        state: String = PublicSpool.OPEN,
        weight: Int = 1,
    ) = PublicSpool("public-$n.spool.getknit.app", state, weight)

    private val three = listOf(spool(1), spool(2, weight = 3), spool(3, PublicSpool.DRAINING, weight = 5))
    private val threeOpen = listOf(spool(1), spool(2, weight = 3), spool(3, weight = 5))

    @Test
    fun onlyAPublicNHostUnderTheHouseDomainIsADirectoryHost() {
        assertTrue(PublicSpools.isDirectoryHost("public-1.spool.getknit.app"))
        assertTrue(PublicSpools.isDirectoryHost("public-42.spool.getknit.app"))
        listOf(
            "public-0.spool.getknit.app",
            "public-01.spool.getknit.app",
            "lax.spool.getknit.app",
            "lab.lax.spool.getknit.app",
            "public-1.spool.getknit.app.evil",
            "PUBLIC-1.spool.getknit.app",
            "x.public-1.spool.getknit.app",
        ).forEach { assertFalse(it, PublicSpools.isDirectoryHost(it)) }
        assertTrue(PublicSpools.isDirectoryUrl("wss://public-1.spool.getknit.app/spool/v1"))
        assertFalse(PublicSpools.isDirectoryUrl("wss://public-1.spool.getknit.app/spool/v1?k=t"))
        assertFalse(PublicSpools.isDirectoryUrl("wss://public-1.spool.getknit.app:8443/spool/v1"))
    }

    @Test
    fun theDirectoryKeepsWellFormedEntriesAndRefusesAnUnknownSchema() {
        val text =
            """{"v":1,"extra":true,"spools":[
              {"host":"public-1.spool.getknit.app","state":"open","weight":1},
              {"host":"public-2.spool.getknit.app","state":"draining","weight":64},
              {"host":"public-1.spool.getknit.app","state":"draining","weight":9},
              {"host":"evil.example","state":"open","weight":1},
              {"host":"public-3.spool.getknit.app","state":"open","weight":0},
              {"host":"public-4.spool.getknit.app","state":"open","weight":65},
              {"host":"public-5.spool.getknit.app","state":"sleeping","weight":2}]}"""
        assertEquals(
            listOf(spool(1), spool(2, PublicSpool.DRAINING, 64), spool(5, "sleeping", 2)),
            PublicSpools.parse(text),
        )
        assertNull(PublicSpools.parse("""{"v":2,"spools":[]}"""))
        assertNull(PublicSpools.parse("not json"))
        assertEquals(three, PublicSpools.parse(PublicSpools.format(three)))
    }

    @Test
    fun theSlotDigestIsTheSpecsConstruction() {
        assertEquals(
            "3112683c9f974d5f07e42d628dd25d69c67f71016b14f8319e4a344ca730259e",
            hex(PublicSpools.slotDigest("aaaaaaaaaaaaaaaaaaaaaaaaaa", "public-1.spool.getknit.app", 0)),
        )
        assertEquals(
            "579c0df9b68b4cb0c7b39ecf35c72db998e9a66eeb06cd2512fa1d60c2d96c51",
            hex(PublicSpools.slotDigest("abcdefghijklmnopqrstuvwxyz", "public-2.spool.getknit.app", 2)),
        )
    }

    @Test
    fun theHomeVectors() {
        val p1 = "public-1.spool.getknit.app"
        val p2 = "public-2.spool.getknit.app"
        val p3 = "public-3.spool.getknit.app"
        // Every node picks the one relay the shipped copy names.
        assertEquals(p1, PublicSpools.pickHome("aaaaaaaaaaaaaaaaaaaaaaaaaa", PublicSpools.BAKED))
        // (node, with public-3 draining, with public-3 open)
        listOf(
            Triple("aaaaaaaaaaaaaaaaaaaaaaaaaa", p2, p3),
            Triple("bbbbbbbbbbbbbhhhhhhhhhhhhh", p2, p2),
            Triple("mmmmmmmmmmmmmuuuuuuuuuuuuu", p1, p1),
            Triple("uuuuuuuuuuuuummmmmmmmmmmmm", p1, p3),
        ).forEach { (node, draining, open) ->
            assertEquals(node, draining, PublicSpools.pickHome(node, three))
            assertEquals(node, open, PublicSpools.pickHome(node, threeOpen))
        }
        assertNull(PublicSpools.pickHome("aaaaaaaaaaaaaaaaaaaaaaaaaa", listOf(spool(1, PublicSpool.DRAINING))))
        // An entry out of range is skipped, not clamped: only public-1 is left to pick.
        assertEquals(
            "public-1.spool.getknit.app",
            PublicSpools.pickHome("aaaaaaaaaaaaaaaaaaaaaaaaaa", listOf(spool(1), spool(2, weight = 0), spool(3, weight = 65))),
        )
    }

    @Test
    fun aHostIsPickedInProportionToItsWeight() {
        // 4 000 node ids over weights 1 and 3: public-2 takes three quarters, give or take a few points.
        val entries = listOf(spool(1), spool(2, weight = 3))
        val picks = (0 until 4_000).count { PublicSpools.pickHome("node$it", entries) == "public-2.spool.getknit.app" }
        assertTrue("$picks", picks in 2_800..3_200)
    }

    @Test
    fun aPhoneRehomesOnlyWhenItsHomeIsGoneOrDraining() {
        assertFalse(PublicSpools.mustRehome("public-1.spool.getknit.app", three))
        assertTrue(PublicSpools.mustRehome("public-3.spool.getknit.app", three))
        assertTrue(PublicSpools.mustRehome("public-9.spool.getknit.app", three))
        assertFalse(PublicSpools.mustRehome("public-5.spool.getknit.app", listOf(spool(5, "sleeping"))))
    }
}
