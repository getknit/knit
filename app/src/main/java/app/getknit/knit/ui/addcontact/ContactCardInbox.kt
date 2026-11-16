package app.getknit.knit.ui.addcontact

import app.getknit.knit.mesh.crypto.ContactCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * A process-scoped, single-shot handoff for a contact link arriving from outside Compose — a tapped
 * `https://getknit.app/c#…` / `knit://c/…` link, or a shared text that contains one. Mirrors
 * [app.getknit.knit.ui.RouteInbox]: `MainActivity` [offer]s the raw text, `KnitApp` observes [pending]
 * and opens the Add-contact screen, which [consume]s it. Unlike the share/route inboxes, a card that
 * lands before onboarding is **kept** — a fresh install opened from a friend's link is the primary way a
 * card arrives, and it waits for the permission gate rather than being dropped.
 */
class ContactCardInbox {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** Stage link text to import. Text that isn't card-shaped is ignored. */
    fun offer(text: String) {
        if (ContactCard.looksLikeCard(text)) _pending.value = text
    }

    /** Take the staged text (if any) and clear it, so only the first reader imports it. */
    fun consume(): String? = _pending.getAndUpdate { null }

    fun clear() {
        _pending.value = null
    }
}

/**
 * The contact link an incoming intent carries, or null. A `VIEW` of one of the card link forms carries it
 * as its data; a `SEND` of plain text carries it when the shared text is (or contains) a card — that is the
 * Android-idiomatic route on 12+, where an unverified https link opens in the browser rather than the app. A
 * bare run shared as text must decode as a card ([ContactCard.isSharedCard]), or a long key or digest would
 * open Add contact instead of the chat picker.
 */
fun contactLinkFrom(
    action: String?,
    dataString: String?,
    sharedText: String?,
): String? =
    when (action) {
        "android.intent.action.VIEW" -> dataString?.takeIf { ContactCard.looksLikeCard(it) }
        "android.intent.action.SEND" -> sharedText?.takeIf { ContactCard.isSharedCard(it) }
        else -> null
    }
