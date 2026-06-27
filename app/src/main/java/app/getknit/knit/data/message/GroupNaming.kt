package app.getknit.knit.data.message

/**
 * Group display-name helpers. An unnamed group has no shared name on the wire; instead each device
 * renders a default from its own view of the members (so everyone sees names that make sense locally).
 * An explicit rename overrides that with a shared name. Pure/JVM-testable (no Android deps).
 */

/** Joins display names like "Alice, Bob & Carol" (commas, with a trailing ampersand). */
fun joinNames(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names.first()
    else -> names.dropLast(1).joinToString(separator = ", ") + " & " + names.last()
}

/**
 * The title to show for a group. A non-blank [storedName] (set via rename) wins; otherwise a default is
 * generated from the other members' display names ([memberIds] minus [selfId], resolved by [nameOf]) so
 * each device shows names that make sense to it. [fallback] covers the degenerate case of no resolvable
 * others (e.g. a roster of only this device).
 */
fun groupTitle(
    storedName: String,
    memberIds: List<String>,
    selfId: String?,
    fallback: String,
    nameOf: (String) -> String,
): String {
    if (storedName.isNotBlank()) return storedName
    val others = memberIds.filter { it != selfId }.map(nameOf)
    return joinNames(others).ifBlank { fallback }
}
