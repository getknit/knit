package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.emoji.RecentReactions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * User/device settings backed by a Preferences DataStore (replaces the legacy
 * SharedPreferences). Holds the profile/mesh toggles and per-conversation read state. (The node id is
 * no longer persisted here — it is derived from the E2E keypair; see [app.getknit.knit.identity.Identity].)
 */
class SettingsStore(
    private val dataStore: DataStore<Preferences>,
) : InboundSettings,
    ModelLoadJournal,
    NanAttachJournal {
    override val displayName: Flow<String> = dataStore.data.map { it[KEY_NAME] ?: "" }
    val status: Flow<String> = dataStore.data.map { it[KEY_STATUS] ?: "" }

    /** Bumped whenever the avatar image changes, so profile re-broadcasts can be triggered. */
    val avatarUpdatedAt: Flow<Long> = dataStore.data.map { it[KEY_AVATAR_UPDATED_AT] ?: 0L }

    /**
     * Monotonic version of this device's own profile — the LWW key receivers order against, carried in
     * `ProfileContent.version`. Must be **stable across app restarts** (persisted here, not a launch
     * timestamp), so a relaunch does not look like an edit to every peer. Bumped only on a real profile edit
     * or a prekey rotation (see `MeshManager`); 0 until the first one.
     */
    val profileVersion: Flow<Long> = dataStore.data.map { it[KEY_PROFILE_VERSION] ?: 0L }

    /**
     * When this device last *published* its profile frame — the frame's `sentAt` and id, distinct from
     * [profileVersion]. Custody expiry is `sentAt + ttl`, so a frame stamped with the edit time is refused
     * as dead on arrival once that edit is a day old: the profile silently leaves custody, a late joiner
     * cannot pull it, and the Internet plane (which seals what custody holds) cannot carry it at all.
     * `MeshManager` re-publishes on a cadence inside the custody TTL and records the stamp here so the
     * cadence survives restarts. 0 until the first publish.
     */
    val profilePublishedAt: Flow<Long> = dataStore.data.map { it[KEY_PROFILE_PUBLISHED_AT] ?: 0L }

    /**
     * Content hash of the device's own avatar, or null if none is set. The avatar bytes live in the
     * encrypted `blobs` table keyed by this hash; the hash is what the profile frame advertises and
     * what the UI/notifications resolve against. (Pre-v6 this was derived from the avatar's filename.)
     */
    override val ownAvatarHash: Flow<String?> = dataStore.data.map { it[KEY_OWN_AVATAR_HASH] }

    /**
     * Per-conversation read watermarks: for each conversation id, the [MessageEntity.sentAt] of the
     * newest message the local user has seen there. The chat list counts messages newer than the
     * watermark (from other senders) as that conversation's unread badge. Stored under one dynamic
     * key per conversation (see [lastReadKey]); [lastReadAll] reads them back as a map for the list.
     */
    val lastReadAll: Flow<Map<String, Long>> =
        dataStore.data.map { prefs ->
            prefs
                .asMap()
                .filterKeys { it.name.startsWith(LAST_READ_PREFIX) }
                .entries
                .associate { (key, value) -> key.name.removePrefix(LAST_READ_PREFIX) to (value as? Long ?: 0L) }
        }

    /** Read watermark for a single conversation (0 until the user has read anything there). */
    fun lastReadAt(conversationId: String): Flow<Long> = dataStore.data.map { it[lastReadKey(conversationId)] ?: 0L }

    /**
     * Node ids the local user has blocked. Their messages/reactions are never stored, shown, or
     * notified, and they're hidden from the new-DM picker. Blocking is local-only and keyed by the
     * peer's node id; since a node id is now the hash of the peer's keypair, a blocked peer that
     * regenerates its identity key (e.g. a reinstall that drops `identity.key`) gets a fresh id and is
     * no longer matched — the cost of binding identity to the key rather than the device.
     */
    override val blockedNodeIds: Flow<Set<String>> = dataStore.data.map { it[KEY_BLOCKED] ?: emptySet() }

    /**
     * Device tags (see [app.getknit.knit.identity.DeviceTag]) the user has blocked. Because a nodeId is
     * the hash of a keypair, a blocked peer that regenerates its key returns under a new nodeId; the
     * device tag is key-independent, so `MeshManager.handleProfile` re-blocks the new id when the tag
     * matches. Maintained alongside [blockedNodeIds] by [block]/[unblock].
     */
    override val blockedDeviceTags: Flow<Set<String>> = dataStore.data.map { it[KEY_BLOCKED_TAGS] ?: emptySet() }

    /**
     * Conversation ids the user has explicitly **accepted** out of the message-request queue — a DM keyed by
     * the peer's node id, or a group keyed by its "g-…" id (see [app.getknit.knit.data.message.Conversations]).
     * `InboundPipeline` treats a DM/group as a stranger *request* — notifications suppressed, storage bounded —
     * unless it is accepted here, the DM peer is verified, or the user has already sent into it. Local-only and,
     * like [blockedNodeIds], keyed by node id for DMs, so a contact that regenerates its identity key returns as
     * a fresh request (a one-tap re-accept; the verified / own-message signals usually cover it anyway).
     */
    override val acceptedConversations: Flow<Set<String>> = dataStore.data.map { it[KEY_ACCEPTED] ?: emptySet() }

    /**
     * The quick-reaction row's most-recently-used emoji, newest first — the classic six until the first
     * pick. One separator-joined string, not a preference *set*: order is the whole datum. The codec, seed
     * and cap live in [RecentReactions]; the seed is never written, so absence means defaults.
     */
    val recentReactions: Flow<List<String>> = dataStore.data.map { RecentReactions.decode(it[KEY_RECENT_REACTIONS]) }

    /**
     * Whether to hide sensitive content received from others. Defaults to on. Gates receive-side hiding
     * only — the inbound toxic-text collapse, the inbound explicit-image blur, and the explicit-avatar
     * rejection (off → adopt anyway). It does **not** affect sending: the sender-side "good-citizen"
     * checks (block abusive text, confirm/hard-block explicit images) and the on-device screening always
     * run regardless, so toggling this flips already-received content's blur/collapse reactively without
     * re-scanning.
     */
    override val contentFilteringEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_CONTENT_FILTERING] ?: true }

    /**
     * Whether a link in a message this device sends grows a preview card — the page's title and picture,
     * fetched by **this** phone over the Internet and sent with the message, so the people it reaches never
     * contact the site (ADR: link previews). **Off by default**: each fetch is a socket toward a site somebody
     * chose by typing a link, from this phone's IP address, and that is the user's call to make. Folded with
     * `BuildConfig.INTERNET_PLANE` the way [spoolEnabled] is, so a build with no Internet plane has no
     * Internet feature at all, and gated once here so no consumer can forget it. The receive side needs no
     * setting: a card is an ordinary attachment, hidden by the content filter like any other when flagged.
     */
    val linkPreviewsEnabled: Flow<Boolean> =
        dataStore.data.map { BuildConfig.INTERNET_PLANE && (it[KEY_LINK_PREVIEWS] ?: false) }

    /**
     * Whether this device's profile declares its user "open to chat". Defaults to off. A profile field like
     * [displayName]/[status]: a change bumps [profileVersion] and republishes the profile on both paths
     * (`MeshManager.watchProfileChanges`), and while on, the presence cue (`presence/OpenToChatWatch`) nudges
     * the user when someone nearby declares the same.
     */
    val openToChat: Flow<Boolean> = dataStore.data.map { it[KEY_OPEN_TO_CHAT] ?: false }

    /**
     * The open-to-chat cue's durable state (`presence/OpenToChatWatch`): the peers a cue has named, as
     * `"<peerId>|<millis>"` entries — several per peer, one per cue, pruned to the last day — and when the
     * last cue was posted. Persisted so a process restart cannot re-nudge about someone named minutes ago;
     * written together so the two can never disagree. The watch reads them once at start and writes through —
     * it must never *collect* them, since a DataStore write re-emits every flow in the store.
     */
    val openToChatNamed: Flow<Set<String>> = dataStore.data.map { it[KEY_OPEN_TO_CHAT_NAMED] ?: emptySet() }
    val openToChatLastPostAt: Flow<Long> = dataStore.data.map { it[KEY_OPEN_TO_CHAT_LAST_POST_AT] ?: 0L }

    /**
     * Whether the mesh foreground service should be running — the persisted twin of "is the mesh on".
     * Defaults to on. Flipped to false when the user manually stops the service from its ongoing
     * notification, and back to true whenever the service (re)starts, so [app.getknit.knit.mesh.BootReceiver]
     * can restore the mesh after a device reboot **unless** the user had stopped it beforehand.
     */
    val meshEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_MESH_ENABLED] ?: true }

    /**
     * Local-clock time the first peer message was observed (0 until then) — the start of the
     * review-prompt engagement window (see [app.getknit.knit.review.ReviewPromptPolicy]). Deliberately a
     * locally-stamped watermark rather than anything derived from a message's `sentAt`, which is the
     * sender's skewable clock.
     */
    val reviewEngagementStartedAt: Flow<Long> = dataStore.data.map { it[KEY_REVIEW_ENGAGEMENT_STARTED_AT] ?: 0L }

    /** Local-clock time of the last rate-prompt shown (0 = never). See [recordReviewAttempt]. */
    val reviewLastAttemptAt: Flow<Long> = dataStore.data.map { it[KEY_REVIEW_LAST_ATTEMPT_AT] ?: 0L }

    /** Lifetime rate-prompts shown — we don't record the user's choice, so shown-count is all we keep. */
    val reviewAttemptCount: Flow<Long> = dataStore.data.map { it[KEY_REVIEW_ATTEMPT_COUNT] ?: 0L }

    /**
     * Whether the Internet (spool) plane may run — **default off**, deliberately. Uploading a
     * conversation's sealed history to third-party machines is a real threat-model change, so it is a
     * choice the user makes rather than one they inherit (docs/SPOOL_PROTOCOL.md §10). With it off, or
     * with [spoolUrls] empty, `ScopeSync` opens no socket at all.
     *
     * Also the plane's single kill switch: every consumer — `ScopeSync`'s url supplier, the group-root
     * mint pass, and `RelayStatusRepository.facts` (from which the header cloud, the per-chat relay
     * notice and the attachment markers all derive) — reads the plane's liveness through this one flow.
     * Gating **here** rather than at each of them is what makes `BuildConfig.INTERNET_PLANE` total: a
     * new consumer cannot forget it. That flag is true in every build as of 2.4.0 (ADR 064), which
     * introduced the feature; it stayed false in release/staging for two releases before that, and
     * `-PinternetPlane=false` still puts a build back in that state. The stored preference was
     * deliberately read but never cleared while the flag was off, so a device that opted in under a
     * flag-on build kept its choice across the introduction rather than being silently reset by it.
     */
    val spoolEnabled: Flow<Boolean> =
        dataStore.data.map { BuildConfig.INTERNET_PLANE && (it[KEY_SPOOL_ENABLED] ?: false) }

    /**
     * The spools to sync every scope against — full `wss://host/spool/v1[?k=token]` URLs. A set, since a
     * scope's members converge through the union of whatever every spool holds; the spools themselves
     * never talk to each other. Release builds refuse a non-`wss://` entry at dial time.
     *
     * Until the signed scope-config ctl ships (spec §5), this list is per-device rather than
     * per-conversation: every scope is synced against every configured spool.
     */
    val spoolUrls: Flow<Set<String>> = dataStore.data.map { it[KEY_SPOOL_URLS] ?: emptySet() }

    /**
     * The subset of [spoolUrls] the user has **parked**: still configured, deliberately not dialled.
     *
     * Stored as the disabled subset rather than the enabled one so that "in use" is what a URL means by
     * default — which is what a seeded default, a `--es url` from the debug bridge, and every list that
     * predates this setting all have to mean. A parked URL is not a removed one: removal forgets the
     * address and its `?k=` bearer token (spec §7.1), which for a private relay is the whole access
     * control, and `seedDefaultSpools`' one-shot marker makes it unrecoverable.
     *
     * For the relay editor only — it is the one screen that must render what it is *not* using. Everything
     * that asks which relays we actually dial reads [activeSpoolUrls].
     */
    val disabledSpoolUrls: Flow<Set<String>> = dataStore.data.map { it[KEY_SPOOL_DISABLED] ?: emptySet() }

    /**
     * The relays this device dials right now: [spoolUrls] minus [disabledSpoolUrls], and empty whenever
     * [spoolEnabled] is false.
     *
     * **This is the seam.** For the reason [spoolEnabled] folds `BuildConfig.INTERNET_PLANE` into itself
     * rather than leaving each consumer to remember it, the per-relay filter composes *here* too: one flow
     * answers "which relays may carry for us", so `ScopeSync`'s url supplier, the contact card's `sp` list
     * and `RelayStatusRepository`'s counts cannot disagree, and a future consumer cannot forget either
     * gate. Filtering at each call site would be two rules to keep in step instead of none.
     */
    val activeSpoolUrls: Flow<Set<String>> =
        dataStore.data.map { prefs ->
            if (!BuildConfig.INTERNET_PLANE || prefs[KEY_SPOOL_ENABLED] != true) {
                emptySet()
            } else {
                (prefs[KEY_SPOOL_URLS] ?: emptySet()) - (prefs[KEY_SPOOL_DISABLED] ?: emptySet())
            }
        }

    /**
     * Whether the user has been shown, and accepted, the disclosure behind [spoolEnabled] — what a spool
     * can observe (IP, timing, volume) and cannot (content, roster), that the choice is global rather
     * than per-conversation, and that switching off stops new uploads while sealed copies already at a
     * spool age out on the scope TTL.
     *
     * Kept separate from [spoolEnabled] rather than inferred from it so that turning the plane off and on
     * again does not re-prompt: consent is about having read the disclosure once, not about the current
     * switch position.
     */
    val spoolConsented: Flow<Boolean> = dataStore.data.map { it[KEY_SPOOL_CONSENTED] ?: false }

    /**
     * Whether the user has been shown, and accepted, the disclosure behind posting into the bridged
     * Meshtastic room — that the channel is cleartext to every radio in range, that many of those radios
     * relay it to a public Internet broker where third parties archive it, and that their Knit display name
     * rides on the front of every post.
     *
     * The last of those is the reason this exists at all. ADR 049 keeps the user's name off the public band
     * — the board is `Knit abcd` to everyone listening, never the person — and this is the single deliberate
     * exception to it, so it is made by the user, once, in front of the words that say what it costs.
     *
     * There is no paired enabled/disabled switch the way [spoolEnabled] pairs with [spoolConsented]: the
     * spool plane acts on its own in the background and needs a kill switch, while nothing here ever happens
     * unless somebody types in one room and presses send.
     */
    val meshtasticPostConsented: Flow<Boolean> = dataStore.data.map { it[KEY_MESHTASTIC_POST_CONSENTED] ?: false }

    /**
     * Whether the user has dismissed the Nearby room's "never sent over the Internet" notice. Sticky by
     * design: the notice states a permanent structural fact (the room is not scope-eligible, spec §4.4),
     * so it is the one relay notice that will never retire itself — a dismissal that came back on the next
     * launch would just be a nag. One device-wide flag rather than a per-conversation one because only the
     * room ever reads [app.getknit.knit.data.relay.RelayReach.Room].
     *
     * Deliberately one-way ([dismissRelayRoomNotice] never clears it): the fact it stated is still true and
     * a tap on the header's cloud glyph still explains the plane, so nothing is lost by never re-arming.
     */
    val relayRoomNoticeDismissed: Flow<Boolean> =
        dataStore.data.map { it[KEY_RELAY_ROOM_NOTICE_DISMISSED] ?: false }

    /**
     * Whether the LoRa (Meshtastic-over-BLE) plane may run — **default off**, and gated behind
     * `BuildConfig.LORA_PLANE` the same way [spoolEnabled] is gated behind the Internet-plane flag, so a
     * shipped build reads false no matter what is stored until the feature is introduced. With it off, or
     * with no board configured ([loraDeviceAddress] null), the LoRa transport opens no board session.
     */
    val loraEnabled: Flow<Boolean> =
        dataStore.data.map { BuildConfig.LORA_PLANE && (it[KEY_LORA_ENABLED] ?: false) }

    /**
     * Whether sealed 1:1 DMs may ride the LoRa plane (ADR 039) — **default on** whenever the plane is on. The
     * content stays end-to-end encrypted either way; what this controls is metadata exposure: on a public-PSK
     * rendezvous channel a DM's sender/recipient ids, timing and size are visible to any LoRa radio in range
     * (kilometres, where the phone radios exposed them at tens of metres). Off keeps DMs on the radios and the
     * spool while the Nearby room keeps riding LoRa. Gated on `BuildConfig.LORA_PLANE` like [loraEnabled].
     */
    val loraDmEnabled: Flow<Boolean> =
        dataStore.data.map { BuildConfig.LORA_PLANE && (it[KEY_LORA_DM_ENABLED] ?: true) }

    /**
     * Whether this board acts as a **bridge** between mesh pockets (ADR 044) — **default on** whenever the
     * plane is on. Live traffic crosses the hop either way; this governs the part nobody is waiting for: the
     * gossip offer that says what this node holds, and serving a far gateway the history its offer shows it
     * is missing. Off in both directions at once — a node that stops offering also stops being served, which
     * is the honest reading of "don't use my board for other people's backlog". The airtime governor bounds
     * the cost regardless; this is the switch for someone who would rather spend none of it.
     * Gated on `BuildConfig.LORA_PLANE` like [loraEnabled].
     */
    val loraBridgeEnabled: Flow<Boolean> =
        dataStore.data.map { BuildConfig.LORA_PLANE && (it[KEY_LORA_BRIDGE_ENABLED] ?: true) }

    /**
     * Whether the **Meshtastic room** — the bound board's primary (slot 0) channel, mirrored into
     * `Conversations.MESHTASTIC` (ADR 2026-09.26q3) — exists on this phone at all: **default on** whenever the
     * plane is on. Off, the transport drops a slot-0 chat packet where it lands, so nothing is judged,
     * verified, moderated, stored or notified; the room's row leaves the chat list even where history remains,
     * and a post cannot leave this device. Knit's own frames are untouched — this is the switch for someone who
     * wants the board as a Knit radio and not as a window onto whatever else is on its channel.
     *
     * It hides the room; it never deletes it. History stays where it is and comes back with the switch, which
     * is what makes this a display choice rather than a destructive one.
     * Gated on `BuildConfig.LORA_PLANE` like [loraEnabled].
     */
    val loraRoomEnabled: Flow<Boolean> =
        dataStore.data.map { BuildConfig.LORA_PLANE && (it[KEY_LORA_ROOM_ENABLED] ?: true) }

    /** The bonded Meshtastic board's MAC address the LoRa plane binds to, or null if none is chosen. */
    val loraDeviceAddress: Flow<String?> = dataStore.data.map { it[KEY_LORA_ADDRESS] }

    /** The chosen board's display name, for the settings row (the picker records it alongside the address). */
    val loraDeviceName: Flow<String?> = dataStore.data.map { it[KEY_LORA_NAME] }

    /** Which of the board's channels to transmit Knit frames on (default 0 = the primary channel). */
    val loraChannelIndex: Flow<Int> = dataStore.data.map { it[KEY_LORA_CHANNEL] ?: 0 }

    /**
     * The bound board's Meshtastic node number, as its last session reported it — what the profile advertises
     * (`ProfileContent.loraNode`) so a contact can line a post their board heard up with this phone.
     *
     * Persisted rather than read off the live link on purpose: the link drops on every BLE hiccup, and a
     * value that flipped to null with it would bump the profile version and re-flood the profile on each
     * reconnect. This changes only when a *different* board reports in, and clears with the binding.
     */
    val loraBoardNode: Flow<Long?> = dataStore.data.map { it[KEY_LORA_NODE] }

    /**
     * The bound board's Curve25519 public key (base64 of 32 bytes), beside [loraBoardNode] — what the profile
     * advertises (`ProfileContent.loraKey`) so a contact's phone can verify the posts firmware 2.8 signs for
     * us. Null while unbound, and on a board whose firmware does not sign: a key that never signs verifies
     * nothing, so it is not advertised. Written and cleared together with the node number, in one edit.
     */
    val loraBoardKey: Flow<String?> = dataStore.data.map { it[KEY_LORA_KEY] }

    /**
     * The board set up for Knit (ADR 045) and the housekeeping intervals it had *before* — so restoring puts
     * the user's own values back rather than the firmware's defaults. Null while no board is set up; a zero
     * interval means "never recorded", which the restore reads as "let the firmware decide".
     */
    val loraBoardSetup: Flow<KnitBoardSetup?> =
        dataStore.data.map { prefs ->
            prefs[KEY_LORA_SETUP_ADDRESS]?.let { address ->
                KnitBoardSetup(
                    address = address,
                    nodeInfoSecs = prefs[KEY_LORA_PRIOR_NODE_INFO] ?: 0,
                    positionSecs = prefs[KEY_LORA_PRIOR_POSITION] ?: 0,
                    smartPosition = prefs[KEY_LORA_PRIOR_SMART] ?: false,
                    telemetrySecs = prefs[KEY_LORA_PRIOR_TELEMETRY] ?: 0,
                    rebroadcastMode = prefs[KEY_LORA_PRIOR_REBROADCAST] ?: 0,
                    longName = prefs[KEY_LORA_PRIOR_LONG_NAME].orEmpty(),
                    shortName = prefs[KEY_LORA_PRIOR_SHORT_NAME].orEmpty(),
                    channelNum = prefs[KEY_LORA_PRIOR_CHANNEL_NUM] ?: 0,
                )
            }
        }

    suspend fun setDisplayName(value: String) = dataStore.edit { it[KEY_NAME] = value }

    suspend fun setStatus(value: String) = dataStore.edit { it[KEY_STATUS] = value }

    /** Persists display name + status in a single transaction so the profile watcher broadcasts once. */
    suspend fun setProfile(
        name: String,
        status: String,
    ) = dataStore.edit {
        it[KEY_NAME] = name
        it[KEY_STATUS] = status
    }

    suspend fun setAvatarUpdatedAt(value: Long) = dataStore.edit { it[KEY_AVATAR_UPDATED_AT] = value }

    suspend fun setProfileVersion(value: Long) = dataStore.edit { it[KEY_PROFILE_VERSION] = value }

    suspend fun setProfilePublishedAt(value: Long) = dataStore.edit { it[KEY_PROFILE_PUBLISHED_AT] = value }

    suspend fun setOwnAvatarHash(value: String) = dataStore.edit { it[KEY_OWN_AVATAR_HASH] = value }

    /** Removes the stored own-avatar hash so [ownAvatarHash] emits null again (the user cleared their photo). */
    suspend fun clearOwnAvatarHash() = dataStore.edit { it.remove(KEY_OWN_AVATAR_HASH) }

    suspend fun setLastReadAt(
        conversationId: String,
        value: Long,
    ) = dataStore.edit { it[lastReadKey(conversationId)] = value }

    /** Blocks [nodeId]; also records the peer's [deviceTag] (when known) so the block survives a key reset. */
    override suspend fun block(
        nodeId: String,
        deviceTag: String?,
    ) {
        dataStore.edit { prefs ->
            prefs[KEY_BLOCKED] = (prefs[KEY_BLOCKED] ?: emptySet()) + nodeId
            if (deviceTag != null) {
                prefs[KEY_BLOCKED_TAGS] = (prefs[KEY_BLOCKED_TAGS] ?: emptySet()) + deviceTag
            }
        }
    }

    /** Unblocks [nodeId]; also clears its [deviceTag] (when known) so the device is no longer re-blocked. */
    suspend fun unblock(
        nodeId: String,
        deviceTag: String? = null,
    ) = dataStore.edit { prefs ->
        prefs[KEY_BLOCKED] = (prefs[KEY_BLOCKED] ?: emptySet()) - nodeId
        if (deviceTag != null) {
            prefs[KEY_BLOCKED_TAGS] = (prefs[KEY_BLOCKED_TAGS] ?: emptySet()) - deviceTag
        }
    }

    /** Accepts [conversationId] out of the message-request queue (a DM peer id or a "g-…" group id). */
    suspend fun accept(conversationId: String) = dataStore.edit { it[KEY_ACCEPTED] = (it[KEY_ACCEPTED] ?: emptySet()) + conversationId }

    /** Fronts [emoji] in [recentReactions] — read-modify-write inside one edit, which DataStore serializes. */
    suspend fun recordReaction(emoji: String) =
        dataStore.edit { prefs ->
            val current = RecentReactions.decode(prefs[KEY_RECENT_REACTIONS])
            prefs[KEY_RECENT_REACTIONS] = RecentReactions.encode(RecentReactions.push(current, emoji))
        }

    /**
     * The intro driver's durable state (`IntroSync`): the peers an intro is pending with, and the
     * post-confirmation grace windows as `"<peerId>|<untilMillis>"` entries. Two small sets in the same
     * store as the accepted set — the ADR 028/037 posture for state that is a handful of ids, not rows.
     */
    val pendingIntros: Flow<Set<String>> = dataStore.data.map { it[KEY_PENDING_INTROS] ?: emptySet() }
    val introGrace: Flow<Set<String>> = dataStore.data.map { it[KEY_INTRO_GRACE] ?: emptySet() }

    /** Replaces both intro sets in one write, so a driver transition can never leave them disagreeing. */
    suspend fun setIntroState(
        pending: Set<String>,
        grace: Set<String>,
    ) = dataStore.edit {
        it[KEY_PENDING_INTROS] = pending
        it[KEY_INTRO_GRACE] = grace
    }

    suspend fun setContentFilteringEnabled(value: Boolean) = dataStore.edit { it[KEY_CONTENT_FILTERING] = value }

    suspend fun setLinkPreviewsEnabled(value: Boolean) = dataStore.edit { it[KEY_LINK_PREVIEWS] = value }

    suspend fun setOpenToChat(value: Boolean) = dataStore.edit { it[KEY_OPEN_TO_CHAT] = value }

    /** Replaces the cue's named set and last-post stamp in one write (see [openToChatNamed]). */
    suspend fun setOpenToChatCueState(
        named: Set<String>,
        lastPostAt: Long,
    ) = dataStore.edit {
        it[KEY_OPEN_TO_CHAT_NAMED] = named
        it[KEY_OPEN_TO_CHAT_LAST_POST_AT] = lastPostAt
    }

    suspend fun setMeshEnabled(value: Boolean) = dataStore.edit { it[KEY_MESH_ENABLED] = value }

    suspend fun setReviewEngagementStartedAt(value: Long) = dataStore.edit { it[KEY_REVIEW_ENGAGEMENT_STARTED_AT] = value }

    /** Stamps the attempt time and bumps the lifetime count in one transaction (mirrors [setProfile]). */
    suspend fun recordReviewAttempt(now: Long) =
        dataStore.edit {
            it[KEY_REVIEW_LAST_ATTEMPT_AT] = now
            it[KEY_REVIEW_ATTEMPT_COUNT] = (it[KEY_REVIEW_ATTEMPT_COUNT] ?: 0L) + 1
        }

    /** Clears all review-prompt state (debug bridge reset). */
    suspend fun clearReviewState() =
        dataStore.edit {
            it.remove(KEY_REVIEW_ENGAGEMENT_STARTED_AT)
            it.remove(KEY_REVIEW_LAST_ATTEMPT_AT)
            it.remove(KEY_REVIEW_ATTEMPT_COUNT)
        }

    suspend fun setSpoolEnabled(value: Boolean) = dataStore.edit { it[KEY_SPOOL_ENABLED] = value }

    suspend fun setLoraEnabled(value: Boolean) = dataStore.edit { it[KEY_LORA_ENABLED] = value }

    suspend fun setLoraDmEnabled(value: Boolean) = dataStore.edit { it[KEY_LORA_DM_ENABLED] = value }

    suspend fun setLoraBridgeEnabled(value: Boolean) = dataStore.edit { it[KEY_LORA_BRIDGE_ENABLED] = value }

    suspend fun setLoraRoomEnabled(value: Boolean) = dataStore.edit { it[KEY_LORA_ROOM_ENABLED] = value }

    /** Records the chosen board's address + name in one write so the row can never show a name without an address. */
    suspend fun setLoraDevice(
        address: String,
        name: String,
    ) = dataStore.edit {
        it[KEY_LORA_ADDRESS] = address
        it[KEY_LORA_NAME] = name
        // A different board has a different node number and key; forget the old ones until the new board
        // reports in, rather than advertise a board this phone no longer holds.
        it.remove(KEY_LORA_NODE)
        it.remove(KEY_LORA_KEY)
    }

    /**
     * The bound board reported its node number and, on firmware that signs, its key ([loraBoardNode],
     * [loraBoardKey]). One edit on purpose: the profile republishes on every change to either, so writing
     * them separately would flood it twice for one board.
     */
    suspend fun setLoraBoard(
        node: Long,
        key: String?,
    ) = dataStore.edit {
        it[KEY_LORA_NODE] = node
        if (key != null) it[KEY_LORA_KEY] = key else it.remove(KEY_LORA_KEY)
    }

    /** Forgets the chosen board (and disables the plane, since it has nothing to bind to). */
    suspend fun clearLoraDevice() =
        dataStore.edit {
            it.remove(KEY_LORA_ADDRESS)
            it.remove(KEY_LORA_NAME)
            it.remove(KEY_LORA_NODE)
            it.remove(KEY_LORA_KEY)
            it[KEY_LORA_ENABLED] = false
            // The setup record is about *that* board; keeping it would offer a restore for hardware this
            // device no longer knows, and would hand its intervals to whatever board is bound next.
            it.remove(KEY_LORA_SETUP_ADDRESS)
            it.remove(KEY_LORA_PRIOR_NODE_INFO)
            it.remove(KEY_LORA_PRIOR_POSITION)
            it.remove(KEY_LORA_PRIOR_SMART)
            it.remove(KEY_LORA_PRIOR_TELEMETRY)
            it.remove(KEY_LORA_PRIOR_REBROADCAST)
            it.remove(KEY_LORA_PRIOR_LONG_NAME)
            it.remove(KEY_LORA_PRIOR_SHORT_NAME)
            it.remove(KEY_LORA_PRIOR_CHANNEL_NUM)
        }

    suspend fun setLoraChannelIndex(index: Int) = dataStore.edit { it[KEY_LORA_CHANNEL] = index }

    /** Records a board as set up for Knit, along with the intervals a restore must put back. */
    suspend fun setLoraBoardSetup(board: KnitBoardSetup) =
        dataStore.edit {
            it[KEY_LORA_SETUP_ADDRESS] = board.address
            it[KEY_LORA_PRIOR_NODE_INFO] = board.nodeInfoSecs
            it[KEY_LORA_PRIOR_POSITION] = board.positionSecs
            it[KEY_LORA_PRIOR_SMART] = board.smartPosition
            it[KEY_LORA_PRIOR_TELEMETRY] = board.telemetrySecs
            it[KEY_LORA_PRIOR_REBROADCAST] = board.rebroadcastMode
            it[KEY_LORA_PRIOR_LONG_NAME] = board.longName
            it[KEY_LORA_PRIOR_SHORT_NAME] = board.shortName
            it[KEY_LORA_PRIOR_CHANNEL_NUM] = board.channelNum
        }

    /** Forgets the setup record — after a restore, or when the board itself is forgotten. */
    suspend fun clearLoraBoardSetup() =
        dataStore.edit {
            it.remove(KEY_LORA_SETUP_ADDRESS)
            it.remove(KEY_LORA_PRIOR_NODE_INFO)
            it.remove(KEY_LORA_PRIOR_POSITION)
            it.remove(KEY_LORA_PRIOR_SMART)
            it.remove(KEY_LORA_PRIOR_TELEMETRY)
            it.remove(KEY_LORA_PRIOR_REBROADCAST)
            it.remove(KEY_LORA_PRIOR_LONG_NAME)
            it.remove(KEY_LORA_PRIOR_SHORT_NAME)
            it.remove(KEY_LORA_PRIOR_CHANNEL_NUM)
        }

    /**
     * Records consent and enables the plane in **one** write, so the two can never disagree: a crash
     * between two edits would otherwise leave a device either consented-but-off (harmless) or, if the
     * order were reversed, relaying without having recorded that the disclosure was accepted.
     */
    suspend fun acceptSpoolConsent() =
        dataStore.edit {
            it[KEY_SPOOL_CONSENTED] = true
            it[KEY_SPOOL_ENABLED] = true
        }

    /** Records that the user accepted the disclosure behind [meshtasticPostConsented]. */
    suspend fun acceptMeshtasticPostConsent() = dataStore.edit { it[KEY_MESHTASTIC_POST_CONSENTED] = true }

    /**
     * Seeds the shipped default spools (`res/values/spools.xml`) into [spoolUrls] exactly once, marking
     * the install as seeded so a **removal sticks**. A default the app kept re-adding would not be a
     * default, it would be a policy — and this list decides which third parties see a conversation's
     * traffic pattern, so the user's edit has to be the last word.
     *
     * Idempotent and safe to call on every start. Seeding a URL does not use it: the plane stays off
     * until [spoolEnabled] is set, so a fresh install still opens no socket.
     */
    suspend fun seedDefaultSpools(defaults: List<String>) =
        dataStore.edit { prefs ->
            // Nothing to seed while the plane is dark, and — because the seeded marker is not written
            // either — the defaults still land on the first run of the build that un-hides it, which is
            // the first run where the user can actually see and edit the list.
            if (!BuildConfig.INTERNET_PLANE) return@edit
            if (prefs[KEY_SPOOL_SEEDED] == true) return@edit
            prefs[KEY_SPOOL_SEEDED] = true
            if (defaults.isNotEmpty()) prefs[KEY_SPOOL_URLS] = (prefs[KEY_SPOOL_URLS] ?: emptySet()) + defaults
        }

    /** Adds a spool URL to sync against (idempotent — the setting is a set, not a list). */
    suspend fun addSpoolUrl(url: String) = dataStore.edit { it[KEY_SPOOL_URLS] = (it[KEY_SPOOL_URLS] ?: emptySet()) + url }

    /**
     * Removes a spool URL, and clears any parked flag it carried in the same write.
     *
     * The two must move together: a stale flag left behind would make a later re-add of the same address
     * come back silently switched off, which reads as the app ignoring the user's action.
     */
    suspend fun removeSpoolUrl(url: String) =
        dataStore.edit {
            it[KEY_SPOOL_URLS] = (it[KEY_SPOOL_URLS] ?: emptySet()) - url
            it[KEY_SPOOL_DISABLED] = (it[KEY_SPOOL_DISABLED] ?: emptySet()) - url
        }

    /** Hides the room's relay notice for good. See [relayRoomNoticeDismissed]; there is no un-dismiss. */
    suspend fun dismissRelayRoomNotice() = dataStore.edit { it[KEY_RELAY_ROOM_NOTICE_DISMISSED] = true }

    /** Parks or un-parks one configured relay. See [disabledSpoolUrls]; the plane's own switch is [setSpoolEnabled]. */
    suspend fun setSpoolUrlEnabled(
        url: String,
        enabled: Boolean,
    ) = dataStore.edit {
        val parked = it[KEY_SPOOL_DISABLED] ?: emptySet()
        it[KEY_SPOOL_DISABLED] = if (enabled) parked - url else parked + url
    }

    override fun observeModelLoad(model: String): Flow<ModelLoadState> = dataStore.data.map { it.modelLoadState(model) }

    override suspend fun modelLoadState(model: String): ModelLoadState = dataStore.data.map { it.modelLoadState(model) }.first()

    /**
     * All three fields in **one** write, so they can never disagree — the `acceptSpoolConsent` argument.
     * It is also the durability barrier `moderation/ModelLoadGuard` relies on: `edit {}` fsyncs the new
     * file and renames it before it resumes, so a native crash immediately afterwards still finds this.
     */
    override suspend fun setModelLoadState(
        model: String,
        state: ModelLoadState,
    ) {
        dataStore.edit {
            it[modelStampKey(model)] = state.stamp
            it[modelPendingKey(model)] = state.pendingSince
            it[modelFailsKey(model)] = state.fails
        }
    }

    override suspend fun awareGiveUpStamp(): String = dataStore.data.map { it[KEY_AWARE_GIVE_UP_STAMP].orEmpty() }.first()

    override suspend fun setAwareGiveUpStamp(stamp: String) {
        dataStore.edit { it[KEY_AWARE_GIVE_UP_STAMP] = stamp }
    }

    private fun Preferences.modelLoadState(model: String) =
        ModelLoadState(
            stamp = this[modelStampKey(model)].orEmpty(),
            pendingSince = this[modelPendingKey(model)] ?: 0L,
            fails = this[modelFailsKey(model)] ?: 0,
        )

    /** Dynamic per-conversation read-watermark key, e.g. "last_read_nearby" / "last_read_<nodeId>". */
    private fun lastReadKey(conversationId: String) = longPreferencesKey(LAST_READ_PREFIX + conversationId)

    /** Dynamic per-model poison-pill keys, e.g. "model_load_stamp_toxicity" (see [ModelLoadJournal]). */
    private fun modelStampKey(model: String) = stringPreferencesKey(MODEL_LOAD_PREFIX + "stamp_" + model)

    private fun modelPendingKey(model: String) = longPreferencesKey(MODEL_LOAD_PREFIX + "pending_" + model)

    private fun modelFailsKey(model: String) = intPreferencesKey(MODEL_LOAD_PREFIX + "fails_" + model)

    private companion object {
        const val LAST_READ_PREFIX = "last_read_"
        const val MODEL_LOAD_PREFIX = "model_load_"

        val KEY_AWARE_GIVE_UP_STAMP = stringPreferencesKey("aware_give_up_stamp")
        val KEY_NAME = stringPreferencesKey("display_name")
        val KEY_STATUS = stringPreferencesKey("status")
        val KEY_AVATAR_UPDATED_AT = longPreferencesKey("avatar_updated_at")
        val KEY_PROFILE_VERSION = longPreferencesKey("profile_version")
        val KEY_PROFILE_PUBLISHED_AT = longPreferencesKey("profile_published_at")
        val KEY_OWN_AVATAR_HASH = stringPreferencesKey("own_avatar_hash")
        val KEY_BLOCKED = stringSetPreferencesKey("blocked_node_ids")
        val KEY_BLOCKED_TAGS = stringSetPreferencesKey("blocked_device_tags")
        val KEY_ACCEPTED = stringSetPreferencesKey("accepted_conversations")
        val KEY_PENDING_INTROS = stringSetPreferencesKey("pending_intros")
        val KEY_INTRO_GRACE = stringSetPreferencesKey("intro_grace")
        val KEY_CONTENT_FILTERING = booleanPreferencesKey("content_filtering_enabled")
        val KEY_LINK_PREVIEWS = booleanPreferencesKey("link_previews_enabled")
        val KEY_OPEN_TO_CHAT = booleanPreferencesKey("open_to_chat")
        val KEY_OPEN_TO_CHAT_NAMED = stringSetPreferencesKey("open_to_chat_named")
        val KEY_OPEN_TO_CHAT_LAST_POST_AT = longPreferencesKey("open_to_chat_last_post_at")
        val KEY_MESH_ENABLED = booleanPreferencesKey("mesh_enabled")
        val KEY_REVIEW_ENGAGEMENT_STARTED_AT = longPreferencesKey("review_engagement_started_at")
        val KEY_REVIEW_LAST_ATTEMPT_AT = longPreferencesKey("review_last_attempt_at")
        val KEY_REVIEW_ATTEMPT_COUNT = longPreferencesKey("review_attempt_count")
        val KEY_SPOOL_ENABLED = booleanPreferencesKey("spool_enabled")
        val KEY_SPOOL_URLS = stringSetPreferencesKey("spool_urls")
        val KEY_SPOOL_DISABLED = stringSetPreferencesKey("spool_urls_disabled")
        val KEY_SPOOL_SEEDED = booleanPreferencesKey("spool_defaults_seeded")
        val KEY_SPOOL_CONSENTED = booleanPreferencesKey("spool_consented")
        val KEY_MESHTASTIC_POST_CONSENTED = booleanPreferencesKey("meshtastic_post_consented")
        val KEY_RELAY_ROOM_NOTICE_DISMISSED = booleanPreferencesKey("relay_room_notice_dismissed")
        val KEY_LORA_ENABLED = booleanPreferencesKey("lora_enabled")
        val KEY_LORA_DM_ENABLED = booleanPreferencesKey("lora_dm_enabled")
        val KEY_LORA_BRIDGE_ENABLED = booleanPreferencesKey("lora_bridge_enabled")
        val KEY_LORA_ROOM_ENABLED = booleanPreferencesKey("lora_room_enabled")
        val KEY_LORA_ADDRESS = stringPreferencesKey("lora_device_address")
        val KEY_LORA_NAME = stringPreferencesKey("lora_device_name")
        val KEY_LORA_CHANNEL = intPreferencesKey("lora_channel_index")
        val KEY_LORA_NODE = longPreferencesKey("lora_board_node")
        val KEY_LORA_KEY = stringPreferencesKey("lora_board_key")
        val KEY_LORA_SETUP_ADDRESS = stringPreferencesKey("lora_setup_address")
        val KEY_LORA_PRIOR_NODE_INFO = intPreferencesKey("lora_prior_node_info_secs")
        val KEY_LORA_PRIOR_POSITION = intPreferencesKey("lora_prior_position_secs")
        val KEY_LORA_PRIOR_SMART = booleanPreferencesKey("lora_prior_smart_position")
        val KEY_LORA_PRIOR_TELEMETRY = intPreferencesKey("lora_prior_telemetry_secs")
        val KEY_LORA_PRIOR_REBROADCAST = intPreferencesKey("lora_prior_rebroadcast_mode")
        val KEY_LORA_PRIOR_CHANNEL_NUM = intPreferencesKey("lora_prior_channel_num")
        val KEY_LORA_PRIOR_LONG_NAME = stringPreferencesKey("lora_prior_long_name")
        val KEY_LORA_PRIOR_SHORT_NAME = stringPreferencesKey("lora_prior_short_name")
        val KEY_RECENT_REACTIONS = stringPreferencesKey("recent_reactions")
    }
}
