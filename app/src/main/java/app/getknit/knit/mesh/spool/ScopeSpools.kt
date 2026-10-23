package app.getknit.knit.mesh.spool

/**
 * The relays a conversation's scope is synced at, and when this phone must tell its peers where it lives (ADR
 * 2026-10.8kfx, knit-spool-coordinator ADR 2026-10.txjf). Pure: the one home of the union rule, so `ScopeRegistry`,
 * the issuer and the tests cannot disagree.
 */
object ScopeSpools {
    /**
     * A DM or pair scope's relays: the conversation's [config] relays this phone may dial, `public-1` while the
     * conversation has no config or the peer has declared no home ([peerDeclared]), this phone's [home], and its own
     * added relays ([own]: the active list minus every directory relay). A relay the person parked is never dialled.
     */
    fun forDm(
        config: List<String>?,
        peerDeclared: Boolean,
        home: String?,
        own: Set<String>,
        parked: Set<String>,
    ): Set<String> =
        buildSet {
            config?.let { addAll(dialable(it, own)) }
            if (config == null || !peerDeclared) add(PublicSpools.LEGACY_URL)
            home?.let { add(PublicSpools.urlOf(it)) }
            addAll(own)
        } - parked

    /**
     * A group scope's relays: the group's [config] relays this phone may dial, `public-1` while the group has no
     * config or any other member has declared no home, and this phone's own added relays. A group's relay belongs to
     * the group, not to any member, so this phone's home is not in it: a member homed elsewhere holds a connection to
     * the group's relay as it holds one to any other.
     */
    fun forGroup(
        config: List<String>?,
        allDeclared: Boolean,
        own: Set<String>,
        parked: Set<String>,
    ): Set<String> =
        buildSet {
            config?.let { addAll(dialable(it, own)) }
            if (config == null || !allDeclared) add(PublicSpools.LEGACY_URL)
            addAll(own)
        } - parked

    /**
     * This phone's own added relays out of the active list: every directory relay comes back only through the home,
     * the legacy rule or a conversation's config, so a seeded `public-1` does not pin every scope to it.
     */
    fun own(active: Set<String>): Set<String> = active.filterNot(PublicSpools::isDirectoryUrl).toSet()

    /**
     * Whether this phone must issue a DM's config: when its [home] is not where the peer looks for it. With no config
     * the peer looks at `public-1` (the legacy rule), so a phone homed there issues nothing.
     */
    fun mustIssueDm(
        config: List<String>?,
        home: String,
    ): Boolean = PublicSpools.urlOf(home) !in (config ?: listOf(PublicSpools.LEGACY_URL))

    /**
     * The relays a DM config names after this phone moved its home from [previous] (null for none) to [home]: the
     * conversation's relays as they stand, with the old home swapped for the new one, so the peer's home stays named.
     */
    fun dmSpoolsAfter(
        config: List<String>?,
        previous: String?,
        home: String,
    ): List<String> {
        val dropped = previous?.let(PublicSpools::urlOf)
        val kept = (config ?: listOf(PublicSpools.LEGACY_URL)).filter { it != dropped }
        return (listOf(PublicSpools.urlOf(home)) + kept).distinct()
    }

    /**
     * Whether a group's relays need a new home: one of them is a directory relay that is gone or `draining`. With no
     * config the group lives on `public-1` by the legacy rule.
     */
    fun groupMustMove(
        config: List<String>?,
        directory: List<PublicSpool>,
    ): Boolean =
        (config ?: listOf(PublicSpools.LEGACY_URL))
            .mapNotNull { url -> SpoolUrl.hostname(url)?.takeIf { PublicSpools.isDirectoryUrl(url) } }
            .any { PublicSpools.mustRehome(it, directory) }

    // A config may name any relay, but this phone dials only a directory relay or one it already added itself: a peer
    // cannot point it at a relay of the peer's choosing.
    private fun dialable(
        spools: List<String>,
        own: Set<String>,
    ): List<String> = spools.map(SpoolUrl::canonical).filter { PublicSpools.isDirectoryUrl(it) || it in own }
}
