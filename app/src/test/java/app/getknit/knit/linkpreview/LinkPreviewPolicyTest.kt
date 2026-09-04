package app.getknit.knit.linkpreview

import app.getknit.knit.mesh.crypto.ContactCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewPolicyTest {
    @Test
    fun anOrdinaryHttpsLinkNormalizesToItself() {
        assertEquals("https://example.com/path?q=1", LinkPreviewPolicy.normalize("https://example.com/path?q=1"))
        assertEquals("https://example.com/", LinkPreviewPolicy.normalize("https://example.com"))
        assertEquals("https://example.com/", LinkPreviewPolicy.normalize("https://example.com:443"))
    }

    @Test
    fun theFetchIsHttpsOnlySoATypedHttpLinkIsUpgraded() {
        assertEquals("https://example.com/a", LinkPreviewPolicy.normalize("http://example.com/a"))
        assertEquals("https://example.com/a", LinkPreviewPolicy.normalize("HTTP://Example.COM/a"))
    }

    @Test
    fun theFragmentIsDroppedAndTheHostLowerCasedButThePathIsKeptAsTyped() {
        assertEquals("https://example.com/Path/A?B=c", LinkPreviewPolicy.normalize("https://Example.com/Path/A?B=c#section"))
        assertEquals("https://example.com/", LinkPreviewPolicy.normalize("https://example.com/#only-a-fragment"))
    }

    @Test
    fun anInternationalHostIsKeptAsPunycode() {
        assertEquals("https://xn--bcher-kva.example/", LinkPreviewPolicy.normalize("https://bücher.example/"))
        assertEquals("https://xn--bcher-kva.example/", LinkPreviewPolicy.normalize("https://xn--bcher-kva.example/"))
    }

    @Test
    fun everyShapeThatCouldPointTheSocketSomewhereElseIsRefused() {
        listOf(
            "https://user:pw@example.com/" to "user-info",
            "https://example.com@evil.example/" to "user-info spoofing a host",
            "https://example.com:8443/" to "a non-standard port",
            "http://example.com:80/" to "an explicit port other than 443",
            "https://10.0.0.1/" to "an IPv4 literal",
            "https://127.0.0.1/" to "loopback",
            "https://[::1]/" to "an IPv6 literal",
            "https://localhost/" to "a single label",
            "https://intranet/" to "a single label",
            "https://printer.local/" to ".local",
            "https://router.home.arpa/" to ".home.arpa",
            "https://x.localhost/" to ".localhost",
            "https://db.internal/" to ".internal",
            "https://host.lan/" to ".lan",
            "https://abc.onion/" to ".onion",
            "ftp://example.com/" to "a non-http scheme",
            "javascript:alert(1)" to "a script scheme",
            "https://exam ple.com/" to "whitespace",
            "https://example.com/" + "a".repeat(2100) to "an over-long URL",
            "" to "nothing",
        ).forEach { (url, why) -> assertNull(why, LinkPreviewPolicy.normalize(url)) }
    }

    @Test
    fun aContactLinkIsACardOfItsOwnNotAPage() {
        val card = ContactCard.URL_PREFIX + "abc"
        assertTrue(ContactCard.looksLikeCard(card) || card.startsWith("https://getknit.app/c#"))
        assertNull(LinkPreviewPolicy.normalize(card))
        assertNull(LinkPreviewPolicy.firstEligible("meet me: $card"))
    }

    @Test
    fun theFirstEligibleLinkInABodyWinsAndTheBodyMayBeJustTheLink() {
        val body = "see https://10.0.0.1/admin and then https://example.com/one and https://example.com/two"
        assertEquals("https://example.com/one", LinkPreviewPolicy.firstEligible(body))
        assertEquals("https://example.com/", LinkPreviewPolicy.firstEligible("https://example.com/"))
        assertEquals("https://www.example.com/x", LinkPreviewPolicy.firstEligible("www.example.com/x"))
        assertNull(LinkPreviewPolicy.firstEligible("no links here"))
        assertNull(LinkPreviewPolicy.firstEligible("mail me at someone@example.com"))
    }

    @Test
    fun onlyTheFirstFewLinksAreTried() {
        val refused = (1..LinkPreviewPolicy.CANDIDATES).joinToString(" ") { "https://10.0.0.$it/" }
        assertNull(LinkPreviewPolicy.firstEligible("$refused https://example.com/late"))
    }

    @Test
    fun sameUrlIsTheReceiversSpoofCheck() {
        assertTrue(LinkPreviewPolicy.sameUrl("http://Example.com/a#x", "https://example.com/a"))
        assertTrue(LinkPreviewPolicy.sameUrl("https://example.com", "https://example.com/"))
        assertFalse(LinkPreviewPolicy.sameUrl("https://example.com/a", "https://example.com/b"))
        assertFalse(LinkPreviewPolicy.sameUrl("https://bank.example/", "https://evil.example/"))
        assertFalse(LinkPreviewPolicy.sameUrl("https://10.0.0.1/", "https://10.0.0.1/"))
    }

    @Test
    fun theDisplayHostIsDerivedFromTheLink() {
        assertEquals("example.com", LinkPreviewPolicy.displayHost("https://www.example.com/a"))
        assertEquals("news.example.org", LinkPreviewPolicy.displayHost("https://News.Example.org/"))
    }
}
