package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User/device settings backed by a Preferences DataStore (replaces the legacy
 * SharedPreferences). Holds the profile/mesh toggles and per-conversation read state. (The node id is
 * no longer persisted here — it is derived from the E2E keypair; see [app.getknit.knit.identity.Identity].)
 */
class SettingsStore(
    private val dataStore: DataStore<Preferences>,
) : InboundSettings {
    override val displayName: Flow<String> = dataStore.data.map { it[KEY_NAME] ?: "" }
    val status: Flow<String> = dataStore.data.map { it[KEY_STATUS] ?: "" }

    /** Bumped whenever the avatar image changes, so profile re-broadcasts can be triggered. */
    val avatarUpdatedAt: Flow<Long> = dataStore.data.map { it[KEY_AVATAR_UPDATED_AT] ?: 0L }

    /**
     * Monotonic version of this device's own profile: the profile frame's id and signed `sentAt` both derive
     * from it, so it must be **stable across app restarts** (persisted here, not a launch timestamp) — otherwise
     * every relaunch mints a new custodied profile frame and the store-and-forward digests never converge.
     * Bumped only on a real profile edit (see `MeshManager`); 0 until the first edit.
     */
    val profileVersion: Flow<Long> = dataStore.data.map { it[KEY_PROFILE_VERSION] ?: 0L }

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

    /** Local-clock time of the last In-App Review API attempt (0 = never). See [recordReviewAttempt]. */
    val reviewLastAttemptAt: Flow<Long> = dataStore.data.map { it[KEY_REVIEW_LAST_ATTEMPT_AT] ?: 0L }

    /** Lifetime In-App Review API attempts — the API gives no feedback, so attempts are all we can count. */
    val reviewAttemptCount: Flow<Long> = dataStore.data.map { it[KEY_REVIEW_ATTEMPT_COUNT] ?: 0L }

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

    /** Moves [conversationId] back to the request queue (undo an accept). */
    suspend fun unaccept(conversationId: String) = dataStore.edit { it[KEY_ACCEPTED] = (it[KEY_ACCEPTED] ?: emptySet()) - conversationId }

    suspend fun setContentFilteringEnabled(value: Boolean) = dataStore.edit { it[KEY_CONTENT_FILTERING] = value }

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

    /** Dynamic per-conversation read-watermark key, e.g. "last_read_nearby" / "last_read_<nodeId>". */
    private fun lastReadKey(conversationId: String) = longPreferencesKey(LAST_READ_PREFIX + conversationId)

    private companion object {
        const val LAST_READ_PREFIX = "last_read_"

        val KEY_NAME = stringPreferencesKey("display_name")
        val KEY_STATUS = stringPreferencesKey("status")
        val KEY_AVATAR_UPDATED_AT = longPreferencesKey("avatar_updated_at")
        val KEY_PROFILE_VERSION = longPreferencesKey("profile_version")
        val KEY_OWN_AVATAR_HASH = stringPreferencesKey("own_avatar_hash")
        val KEY_BLOCKED = stringSetPreferencesKey("blocked_node_ids")
        val KEY_BLOCKED_TAGS = stringSetPreferencesKey("blocked_device_tags")
        val KEY_ACCEPTED = stringSetPreferencesKey("accepted_conversations")
        val KEY_CONTENT_FILTERING = booleanPreferencesKey("content_filtering_enabled")
        val KEY_MESH_ENABLED = booleanPreferencesKey("mesh_enabled")
        val KEY_REVIEW_ENGAGEMENT_STARTED_AT = longPreferencesKey("review_engagement_started_at")
        val KEY_REVIEW_LAST_ATTEMPT_AT = longPreferencesKey("review_last_attempt_at")
        val KEY_REVIEW_ATTEMPT_COUNT = longPreferencesKey("review_attempt_count")
    }
}
