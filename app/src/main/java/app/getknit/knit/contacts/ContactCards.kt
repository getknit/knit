package app.getknit.knit.contacts

import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.crypto.ContactCard
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.spool.PublicSpools
import app.getknit.knit.mesh.spool.ScopeSpools
import app.getknit.knit.mesh.spool.SpoolUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * Mints this device's **contact card** (docs/CONTACT_CARD.md): the identity bundle, the display name,
 * and — only while the Internet plane is on — the relays this device uses, so a far-away contact can see
 * where to meet. Relay hints never carry a bearer token (`?k=`): a card is forwardable, and a private
 * spool's token would ride with it. Android-free (the signer is injected) so it drives under plain JVM.
 *
 * The [home] relay leads the list (ADR 2026-10.8kfx): a new contact's pair scope may meet there first.
 */
class ContactCards(
    private val identity: Identity,
    private val settings: SettingsStore,
    private val signRaw: (ByteArray) -> ByteArray,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val home: Flow<String?> = flowOf(null),
) {
    /** A freshly minted card in its two link forms. */
    class Minted(
        val compact: String,
        val url: String,
        val schemeUrl: String,
    )

    suspend fun mint(): Minted {
        val bundle = checkNotNull(PublicKeyBundle.decode(identity.publicKeyBundle())) { "own bundle must decode" }
        // `sp` is "relay URLs the owner uses" (docs/CONTACT_CARD.md §2), so it reads the active set:
        // a relay the plane is off for, or one the user parked, is not one we would ever read a frame
        // from, and publishing it would point a new contact at an address we ignore.
        // The home first, while this phone uses the public relays at all; the rest sorted.
        val active = settings.activeSpoolUrls.first()
        val lead = home.first()?.takeIf { ScopeSpools.publicOn(active) }?.let(PublicSpools::urlOf)
        val spools =
            (listOfNotNull(lead) + active.filterNot { SpoolUrl.redact(it) != it }.sorted())
                .distinct()
                .take(ContactCard.MAX_SPOOLS)
        val compact =
            ContactCard.encode(
                bundle = bundle,
                name = settings.displayName.first(),
                spools = spools,
                issuedAt = clock(),
                sign = signRaw,
            )
        return Minted(compact = compact, url = ContactCard.url(compact), schemeUrl = ContactCard.schemeUrl(compact))
    }
}
