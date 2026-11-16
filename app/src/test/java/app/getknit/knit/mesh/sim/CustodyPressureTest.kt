package app.getknit.knit.mesh.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustodyPressureTest {
    private val pressure = CustodyPressure(cap = 3)

    @Test
    fun aRowGoneSinceTheLastSampleCountsAsWhatItIsNow() {
        pressure.sample(
            atS = 60,
            phase = "walk",
            now = 60_000,
            stores =
                listOf(
                    listOf(row("pending", RowKind.DM_UNDELIVERED), row("arrived", RowKind.DM_UNDELIVERED), row("post", RowKind.ROOM)),
                    listOf(row("pending", RowKind.DM_UNDELIVERED), row("carried", RowKind.DM_UNDELIVERED)),
                    listOf(row("carried", RowKind.DM_UNDELIVERED)),
                ),
            undelivered = mapOf("pending" to 0, "arrived" to 0, "carried" to 2),
        )
        // Between the samples: "arrived" reached its recipient and left node 0; "pending" left its sender's own store
        // (node 0); "carried" left node 1, a carrier, and stays in its sender's (node 2).
        val second =
            pressure.sample(
                atS = 120,
                phase = "walk",
                now = 120_000,
                stores =
                    listOf(
                        emptyList(),
                        listOf(row("pending", RowKind.DM_UNDELIVERED)),
                        listOf(row("carried", RowKind.DM_UNDELIVERED)),
                    ),
                undelivered = mapOf("pending" to 0, "carried" to 2),
            )

        assertEquals(2, second.gone.getValue(RowKind.DM_UNDELIVERED))
        assertEquals(1, second.gone.getValue(RowKind.DM_DELIVERED))
        assertEquals(1, second.gone.getValue(RowKind.ROOM))
        assertEquals(
            "custody evicted copies of at least 2 DMs still undelivered at the next sample (2 copies), 1 from their " +
                "sender's own store; 1 more copies left DMs that arrived before the next sample; 0 DMs custody had held " +
                "were held by no store at a sample, 0 of them never arrived; 4 DMs untallied (no frame id); a sample took " +
                "at most 9ms",
            pressure.lines(neverArrived = emptySet(), untallied = 4, slowestSampleMs = 9).last(),
        )
    }

    @Test
    fun aDmIsUnheldOnlyOnceCustodyHadIt() {
        val stores =
            listOf(listOf(row("a", RowKind.SEALED_OTHER, sentAt = 40_000), row("b", RowKind.PROFILE), row("lost", RowKind.DM_UNDELIVERED)))
        val first =
            pressure.sample(
                atS = 60,
                phase = "gather",
                now = 100_000,
                stores = stores,
                undelivered =
                    mapOf(
                        "lost" to 0,
                        "parked" to 0,
                    ),
            )
        pressure.sample(
            atS = 120,
            phase = "gather",
            now = 160_000,
            stores = listOf(emptyList()),
            undelivered =
                mapOf(
                    "lost" to 0,
                    "parked" to 0,
                ),
        )

        assertEquals(0, first.gone.values.sum())
        assertEquals(60L, first.oldestMedianS)
        assertEquals(
            "  +60s gather: mean 3 rows, 1/1 at the cap; oldest row median 60s, min 60s; dm-pending 33% dm-delivered 0% " +
                "sealed-other 33% room 0% profile 33% other 0%; gone none",
            pressure.lines(neverArrived = setOf("lost"), untallied = 0, slowestSampleMs = 0)[1],
        )
        // "parked" was never in any store, so its absence is no loss.
        assertTrue(
            pressure
                .lines(neverArrived = setOf("lost"), untallied = 0, slowestSampleMs = 0)
                .last()
                .contains("1 DMs custody had held were held by no store at a sample, 1 of them never arrived"),
        )
    }

    @Test
    fun aStoreOneRowShortOfTheCapIsNotAtIt() {
        val sample =
            pressure.sample(
                atS = 60,
                phase = "walk",
                now = 60_000,
                stores = listOf(listOf(row("a", RowKind.ROOM), row("b", RowKind.ROOM))),
                undelivered = emptyMap(),
            )

        assertEquals(0, sample.atCap)
    }

    private fun row(
        id: String,
        kind: RowKind,
        sentAt: Long = 50_000,
    ) = HeldRow(id, kind, sentAt)
}
