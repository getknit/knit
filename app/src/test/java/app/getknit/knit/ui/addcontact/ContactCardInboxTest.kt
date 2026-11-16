package app.getknit.knit.ui.addcontact

import app.getknit.knit.mesh.crypto.ContactCard
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors `RouteInboxTest`: the single-shot handoff of a contact link from an intent to the Add-contact screen. */
class ContactCardInboxTest {
    private val link = ContactCard.url("A".repeat(260))

    @Test
    fun consumeReturnsOfferedLinkThenEmpties() {
        val inbox = ContactCardInbox()
        inbox.offer(link)
        assertEquals(link, inbox.pending.value)
        assertEquals(link, inbox.consume())
        assertNull(inbox.pending.value)
        assertNull(inbox.consume())
    }

    @Test
    fun textThatIsNotACardIsIgnored() {
        val inbox = ContactCardInbox()
        inbox.offer("hello there")
        inbox.offer("")
        assertNull(inbox.pending.value)
    }

    @Test
    fun onlyAViewOfACardLinkOrASendCarryingOneIsAContactLink() {
        assertEquals(link, contactLinkFrom("android.intent.action.VIEW", link, null))
        assertEquals("knit://c/${"B".repeat(260)}", contactLinkFrom("android.intent.action.VIEW", "knit://c/${"B".repeat(260)}", null))
        assertNull(contactLinkFrom("android.intent.action.VIEW", "https://getknit.app/", null))
        assertEquals("Add me: $link", contactLinkFrom("android.intent.action.SEND", null, "Add me: $link"))
        assertNull(contactLinkFrom("android.intent.action.SEND", null, "just a message"))
        // A short bare token shared as text is a message, never a card — it must not hijack the share flow.
        assertNull(contactLinkFrom("android.intent.action.SEND", null, "aaaaabbbbbcccccdddddeeeeef"))
        assertNull(contactLinkFrom("android.intent.action.MAIN", link, link))
    }

    @Test
    fun aLongSharedTokenThatIsNotACardStaysAMessage() {
        // #303: a key, a digest or a hyphenated string is in the card's alphabet; shared as text it must reach
        // the chat picker, whatever its length, unless it decodes as a card.
        val send = "android.intent.action.SEND"
        assertNull(contactLinkFrom(send, null, "knit-cap-test-".repeat(180).take(2_500)))
        assertNull(contactLinkFrom(send, null, "A".repeat(300)))
        assertNull(contactLinkFrom(send, null, "0123456789abcdef".repeat(4) + "-" + "q".repeat(250)))
    }

    @Test
    fun aRealBareCardSharedAsTextIsStillAContactLink() {
        TinkInit.ensure()
        val signing = Ed25519Sign.KeyPair.newKeyPairFromSeed(ByteArray(32) { it.toByte() })
        val bundle = PublicKeyBundle.fromRaw(signing.publicKey, X25519.publicFromPrivate(ByteArray(32) { (it + 1).toByte() }))!!
        val signer = Ed25519Sign(signing.privateKey)
        val bare = ContactCard.encode(bundle, name = "Ann", spools = emptyList(), issuedAt = 1L) { signer.sign(it) }
        assertEquals(bare, contactLinkFrom("android.intent.action.SEND", null, bare))
        assertEquals("  $bare\n", contactLinkFrom("android.intent.action.SEND", null, "  $bare\n"))
        // A damaged link still reaches Add contact, which names what is wrong with it.
        val damaged = ContactCard.url(bare.dropLast(10))
        assertEquals(damaged, contactLinkFrom("android.intent.action.SEND", null, damaged))
    }
}
