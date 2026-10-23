package app.getknit.knit.mesh.spool

/**
 * The relays a conversation's scope is synced at, and when this phone must tell its peers where it lives (ADR
 * 2026-10.8kfx, knit-spool-coordinator ADR 2026-10.txjf). Pure: the one home of the union rule, so `ScopeRegistry`,
 * the issuer and the tests cannot disagree.
 *
 * Every rule here takes [public]: whether this phone uses the public relays at all, which is whether its active list
 * holds a directory relay ([publicOn]). A phone whose person parked or removed `public-1` dials only its own relays,
 * whatever a config names, exactly as before homes.
 */
object ScopeSpools {
    /**
     * A DM or pair scope's relays: the conversation's [config] relays this phone may dial, `public-1` while the
     * conversation has no config or the peer has declared no home ([peerDeclared]), this phone's [home], and its own
     * added relays ([own]: the active list minus every directory relay). A relay the person parked is never dialled.
     * [listed] is the directory's hosts: a directory relay it no longer names is not dialled for a config.
     */
    fun forDm(
        config: List<String>?,
        peerDeclared: Boolean,
        home: String?,
        own: Set<String>,
        parked: Set<String>,
        listed: Set<String>,
        public: Boolean = true,
    ): Set<String> {
        if (!public) return own - parked
        return buildSet {
            config?.let { addAll(dialable(it, own, listed)) }
            if (config == null || !peerDeclared) add(PublicSpools.LEGACY_URL)
            home?.let { add(PublicSpools.urlOf(it)) }
            addAll(own)
        } - parked
    }

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
        listed: Set<String>,
        public: Boolean = true,
    ): Set<String> {
        if (!public) return own - parked
        return buildSet {
            config?.let { addAll(dialable(it, own, listed)) }
            if (config == null || !allDeclared) add(PublicSpools.LEGACY_URL)
            addAll(own)
        } - parked
    }

    /**
     * The relays this phone holds a connection to whatever its scopes name: its own added relays and, while it uses
     * the public relays, its home (`public-1` until one is settled). A seeded `public-1` in the active list is not one
     * of them otherwise: it comes back through the home or a scope that needs it.
     */
    fun base(
        active: Set<String>,
        home: String?,
    ): Set<String> {
        val own = own(active)
        return if (publicOn(active)) own + PublicSpools.urlOf(home ?: PublicSpools.LEGACY_HOST) else own
    }

    /**
     * This phone's own added relays out of the active list: every directory relay comes back only through the home,
     * the legacy rule or a conversation's config, so a seeded `public-1` does not pin every scope to it.
     */
    fun own(active: Set<String>): Set<String> = active.filterNot(PublicSpools::isDirectoryUrl).toSet()

    /** Whether this phone uses the public relays: its active list holds a directory relay (the seeded `public-1`). */
    fun publicOn(active: Set<String>): Boolean = active.any(PublicSpools::isDirectoryUrl)

    /**
     * Whether this phone must issue a DM's config: when its [home] is not where the peer looks for it. With no config
     * the peer looks at `public-1` (the legacy rule), so a phone homed there issues nothing.
     */
    fun mustIssueDm(
        config: List<String>?,
        home: String,
    ): Boolean = PublicSpools.urlOf(home) !in (config ?: listOf(PublicSpools.LEGACY_URL))

    /**
     * The relays a DM config of this phone's names: its [home] first, then the peer's. The peer's home is the first
     * relay of the newest config the peer issued itself ([peerOwn]), since every issuer names its own home first; a
     * peer that never issued one, or whose home the directory no longer lists ([listed]), is looked for at `public-1`,
     * where a build that predates homes lives. Two relays at most, so a move never leaves an old home named.
     */
    fun dmSpools(
        peerOwn: List<String>?,
        home: String,
        listed: Set<String>,
    ): List<String> = listOf(PublicSpools.urlOf(home), peerHome(peerOwn, listed)).distinct()

    /** The peer's home as [dmSpools] reads it: the first relay of its own newest config, else `public-1`. */
    fun peerHome(
        peerOwn: List<String>?,
        listed: Set<String>,
    ): String =
        peerOwn
            ?.firstOrNull()
            ?.let(SpoolUrl::canonical)
            ?.takeIf { PublicSpools.isDirectoryUrl(it) && stillListed(it, listed) }
            ?: PublicSpools.LEGACY_URL

    /**
     * Whether a DM's [config] still names a directory relay that is gone or `draining` and is neither this phone's
     * [home] nor the peer's ([peerHome]): an old home of this phone's after a move onto a relay the config already
     * named. Re-issued as [dmSpools], which names only the two homes, so this never fires twice for one move.
     */
    fun dmNamesStale(
        config: List<String>?,
        home: String,
        peerHome: String,
        directory: List<PublicSpool>,
    ): Boolean =
        config.orEmpty().any { url ->
            val host = SpoolUrl.hostname(url)
            PublicSpools.isDirectoryUrl(url) &&
                host != null &&
                url != PublicSpools.urlOf(home) &&
                url != peerHome &&
                PublicSpools.mustRehome(host, directory)
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

    /**
     * The relays a group moves to when [groupMustMove] says so: this phone's [home], unless the home is itself gone or
     * draining (a directory with nothing open), when there is nowhere better and the group stays. Null for no move.
     */
    fun groupMoveTo(
        config: List<String>?,
        home: String,
        directory: List<PublicSpool>,
    ): List<String>? {
        if (!groupMustMove(config, directory) || PublicSpools.mustRehome(home, directory)) return null
        return listOf(PublicSpools.urlOf(home)).takeIf { it != config }
    }

    // A config may name any relay, but this phone dials only a directory relay the directory still lists or one it
    // already added itself: a peer cannot point it at a relay of the peer's choosing.
    private fun dialable(
        spools: List<String>,
        own: Set<String>,
        listed: Set<String>,
    ): List<String> = spools.map(SpoolUrl::canonical).filter { (PublicSpools.isDirectoryUrl(it) && stillListed(it, listed)) || it in own }

    private fun stillListed(
        url: String,
        listed: Set<String>,
    ): Boolean = !PublicSpools.isDirectoryUrl(url) || SpoolUrl.hostname(url) in listed
}

/**
 * One derivation's view of where each scope lives (ADR 2026-10.8kfx): the winning config per conversation, the nodes
 * that have declared, this phone's home, its active and parked relays, and the directory. Read once per scope-table
 * derivation, so every scope in one table is placed against the same inputs.
 */
class ScopePlacement(
    private val configs: Map<String, ScopeConfig>,
    private val declared: Set<String>,
    private val home: String?,
    private val active: Set<String>,
    private val parked: Set<String>,
    directory: List<PublicSpool>,
) {
    private val own = ScopeSpools.own(active)
    private val public = ScopeSpools.publicOn(active)
    private val listed = directory.mapTo(HashSet()) { it.host }

    /** A DM or pair scope with [peerId]. */
    fun forDm(peerId: String): Set<String> =
        ScopeSpools.forDm(configs[peerId]?.spools, peerId in declared, home, own, parked, listed, public)

    /** A group scope: [members] are its effective members, this phone among them or not. */
    fun forGroup(
        groupId: String,
        members: Set<String>,
        self: String,
    ): Set<String> = ScopeSpools.forGroup(configs[groupId]?.spools, declared.containsAll(members - self), own, parked, listed, public)
}
