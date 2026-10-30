package app.getknit.knit.data.message

import app.getknit.knit.TextLimits
import app.getknit.knit.identity.NameKey

/*
 * Group display-name helpers. An unnamed group has no shared name on the wire; instead each device
 * renders a default from its own view of the members (so everyone sees names that make sense locally).
 * An explicit rename overrides that with a shared name. Pure/JVM-testable (no Android deps).
 */

/** Joins display names like "Alice, Bob & Carol" (commas, with a trailing ampersand). */
fun joinNames(names: List<String>): String =
    when (names.size) {
        0 -> ""
        1 -> names.first()
        else -> names.dropLast(1).joinToString(separator = ", ") + " & " + names.last()
    }

/**
 * The title to show for a group. A [storedName] (set via rename) wins, cleaned for one line ([groupNameForDisplay]),
 * unless the cleaned name is display-blank; then a default is generated from the other members' display names
 * ([memberIds] minus [selfId], resolved by [nameOf]) so each device shows names that make sense to it. A name of only
 * invisible characters or controls counts as blank here though the wire still carries it. [fallback] covers the
 * degenerate case of no resolvable others (e.g. a roster of only this device).
 */
fun groupTitle(
    storedName: String,
    memberIds: List<String>,
    selfId: String?,
    fallback: String,
    nameOf: (String) -> String,
): String {
    groupNameForDisplay(storedName)?.let { return it }
    val others = memberIds.filter { it != selfId }.map(nameOf)
    return joinNames(others).ifBlank { fallback }
}

/**
 * A group name as it is drawn — the title, and the rename notice's text: [TextLimits.cleanReceived] to
 * [TextLimits.GROUP_NAME], or null when what is left is display-blank ([NameKey.isBlankName] on the *cleaned* name,
 * since a name of only controls has a key but cleans to nothing), where the caller shows the member-derived title.
 * Cleaned here and never where it is stored, because the stored name is what this device signs into its own group
 * frames: cleaning it there would change what we author and spread the cleaned name last-writer-wins
 * (ADR 2026-10.2kfr).
 */
fun groupNameForDisplay(name: String): String? =
    TextLimits.cleanReceived(name, TextLimits.GROUP_NAME).takeUnless { NameKey.isBlankName(it) }
