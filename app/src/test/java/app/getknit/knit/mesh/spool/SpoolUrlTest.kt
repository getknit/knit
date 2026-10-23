package app.getknit.knit.mesh.spool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure text rules every door that stores or dials a relay agrees on. [SpoolUrl.sourceUrl]'s own
 * cases live in [SpoolSoftwareTest]; what is pinned here is the scheme rule underneath them.
 */
class SpoolUrlTest {
    @Test
    fun `a scheme is read case-insensitively, as the socket reads it`() {
        // RFC 3986 §3.1 — and OkHttp normalises the scheme before it dials, so refusing `WSS://` here
        // would refuse a URL the transport would have carried. It failed closed, but the relay editor
        // could only answer "no", with nothing to tell a capitalisation slip from an unsupported scheme.
        assertTrue(SpoolUrl.isAcceptable("WSS://relay.example.org/spool/v1", allowCleartext = false))
        assertTrue(SpoolUrl.isAcceptable("Wss://relay.example.org/spool/v1", allowCleartext = false))
        assertTrue(SpoolUrl.isAcceptable("WS://10.0.0.5:8080/spool/v1", allowCleartext = true))
    }

    @Test
    fun `case does not widen what a release build may dial`() {
        // The ADR 019 rule is about the scheme, not its spelling: a release APK must be no more
        // pointable at `WS://` than at `ws://`.
        assertFalse(SpoolUrl.isAcceptable("WS://10.0.0.5:8080/spool/v1", allowCleartext = false))
        assertFalse(SpoolUrl.isAcceptable("HTTPS://relay.example.org/spool/v1", allowCleartext = true))
        assertFalse(SpoolUrl.isAcceptable("relay.example.org/spool/v1", allowCleartext = true))
    }

    @Test
    fun `an uppercase scheme still reads as TLS when the build document is fetched`() {
        // The trap in accepting `WSS://` at all: `sourceUrl` picks https-or-http off the same scheme, so
        // a check that missed the uppercase form would silently GET a private relay's /source in the
        // clear from a release build — the one thing ADR 019 exists to stop.
        assertEquals(
            "https://relay.example.org/source",
            SpoolUrl.sourceUrl("WSS://relay.example.org/spool/v1?k=secret-token", allowCleartext = false),
        )
        assertNull(SpoolUrl.sourceUrl("WS://10.0.0.5:8080/spool/v1", allowCleartext = false))
    }

    @Test
    fun `the stored form lowercases the scheme and nothing else`() {
        // Relays are matched by exact string, so one host must have one spelling. Everything right of the
        // scheme is left alone: a path is case-sensitive, and a bearer token is case-sensitive key material.
        assertEquals(
            "wss://Relay.Example.ORG/Spool/v1?k=SeCrEt",
            SpoolUrl.canonical("WSS://Relay.Example.ORG/Spool/v1?k=SeCrEt"),
        )
        assertEquals("ws://10.0.0.5:8080/spool/v1", SpoolUrl.canonical("WS://10.0.0.5:8080/spool/v1"))
        // Already canonical, and a URL with neither of our schemes, both pass through untouched.
        assertEquals("wss://relay.example.org/spool/v1", SpoolUrl.canonical("wss://relay.example.org/spool/v1"))
        assertEquals("HTTPS://relay.example.org", SpoolUrl.canonical("HTTPS://relay.example.org"))
    }

    @Test
    fun `the renamed house relay folds to its current name keeping the port, path and token`() {
        // knit-spool-coordinator ADR 2026-10.ftp9: lax is public-1, so an old card or invite cannot bring lax back.
        assertEquals("wss://public-1.spool.getknit.app/spool/v1", SpoolUrl.canonical("wss://lax.spool.getknit.app/spool/v1"))
        assertEquals(
            "wss://public-1.spool.getknit.app:8443/spool/v1?k=SeCrEt",
            SpoolUrl.canonical("WSS://LAX.spool.getknit.app:8443/spool/v1?k=SeCrEt"),
        )
        assertEquals("wss://public-1.spool.getknit.app?k=t", SpoolUrl.canonical("wss://lax.spool.getknit.app?k=t"))
        assertEquals("wss://u@public-1.spool.getknit.app/spool/v1", SpoolUrl.canonical("wss://u@lax.spool.getknit.app/spool/v1"))
        // Already current: untouched, so the fold is idempotent.
        assertEquals("wss://public-1.spool.getknit.app/spool/v1", SpoolUrl.canonical("wss://public-1.spool.getknit.app/spool/v1"))
    }

    @Test
    fun `only the exact former host is renamed, never the lab under it`() {
        // The lab relay and its TURN sit under lax's name and are different machines.
        assertEquals("wss://lab.lax.spool.getknit.app/spool/v1", SpoolUrl.canonical("wss://lab.lax.spool.getknit.app/spool/v1"))
        assertEquals("wss://stun.lab.lax.spool.getknit.app/x", SpoolUrl.canonical("wss://stun.lab.lax.spool.getknit.app/x"))
        assertEquals("wss://lax.spool.getknit.app.evil/spool/v1", SpoolUrl.canonical("wss://lax.spool.getknit.app.evil/spool/v1"))
        assertEquals("wss://relay.example.org/lax.spool.getknit.app", SpoolUrl.canonical("wss://relay.example.org/lax.spool.getknit.app"))
        assertEquals("lab.lax.spool.getknit.app", SpoolUrl.currentHost("lab.lax.spool.getknit.app"))
        assertEquals("public-1.spool.getknit.app", SpoolUrl.currentHost("lax.spool.getknit.app"))
        assertEquals("lax.spool.getknit.app", SpoolUrl.formerHost("public-1.spool.getknit.app"))
        assertEquals("relay.example.org", SpoolUrl.formerHost("relay.example.org"))
    }

    @Test
    fun `the host row and the redaction do not care about case`() {
        // Both split on "://" rather than on a scheme constant, so they were never case-sensitive — pinned
        // because the token must not reach a screenshot whatever the scheme looks like (spec §7.1).
        assertEquals("relay.example.org", SpoolUrl.host("WSS://relay.example.org/spool/v1?k=secret-token"))
        assertEquals(
            "WSS://relay.example.org/spool/v1",
            SpoolUrl.redact("WSS://relay.example.org/spool/v1?k=secret-token"),
        )
    }
}
