package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.spool.FakeSpool
import app.getknit.knit.mesh.spool.PublicSpool
import app.getknit.knit.mesh.spool.PublicSpools
import app.getknit.knit.mesh.spool.SpoolDialer
import app.getknit.knit.mesh.spool.SpoolSocket

/**
 * The house relays a lab node can live on (ADR 2026-10.8kfx): an in-process [FakeSpool] per `public-N` host, all open
 * in the directory every node starts from. A node given these (`MeshLab.node(publicRelays, home)`) holds the seeded
 * `public-1` in its relay list, a home, and the real home store, so its scope table is placed by the union rule and its
 * issuer runs.
 */
class LabPublicRelays(
    vararg numbers: Int,
) {
    val hosts: List<String> = numbers.map { "public-$it.spool.getknit.app" }
    private val byUrl: Map<String, FakeSpool> = hosts.associate { PublicSpools.urlOf(it) to FakeSpool() }

    /** What every node holds before a scenario changes it: every relay open, weight 1. */
    val directory: List<PublicSpool> = hosts.map { PublicSpool(it, PublicSpool.OPEN, 1) }

    /** The relay at public-[n]. */
    fun at(n: Int): FakeSpool = byUrl.getValue(PublicSpools.urlOf("public-$n.spool.getknit.app"))

    /** Dials each URL to its own relay. */
    val dialer: SpoolDialer =
        object : SpoolDialer {
            override suspend fun dial(url: String): SpoolSocket = byUrl.getValue(url).dial(url)
        }

    companion object {
        fun host(n: Int): String = "public-$n.spool.getknit.app"
    }
}
