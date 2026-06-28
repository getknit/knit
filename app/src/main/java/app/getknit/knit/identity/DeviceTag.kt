package app.getknit.knit.identity

import java.security.MessageDigest

/**
 * A stable, app-scoped per-**device** tag used *only* for soft block-list continuity.
 *
 * Since a [nodeId][NodeId] is now the hash of the E2E keypair (self-certifying), a blocked peer that
 * reinstalls/wipes loses their key and returns under a *new* nodeId — which would silently evade a
 * block. To keep blocking sticky, a device advertises this key-independent tag (derived from a stable
 * device id such as `ANDROID_ID`, which survives reinstall/data-clear on the same device + signing
 * key); when a blocked device reappears under a new identity, its tag still matches.
 *
 * This is a **soft deterrent only**: the tag is self-asserted, so a motivated abuser running a
 * modified app can change it. It is never used for routing, addressing, key pinning, or trust — only
 * the local block list. It is salted (not the raw device id) and 64-bit, large enough that collisions
 * (which would wrongly block a bystander) are negligible. Pure/Android-free so it is unit-testable.
 */
object DeviceTag {

    /** The 16-hex (64-bit) tag for [rawDeviceId], or null when the platform reports no stable id. */
    fun derive(rawDeviceId: String?): String? =
        rawDeviceId?.takeIf { it.isNotBlank() }?.let { id ->
            MessageDigest.getInstance("SHA-256")
                .digest((SALT + id).encodeToByteArray())
                .take(TAG_BYTES)
                .joinToString("") { "%02x".format(it) }
        }

    /** App-specific salt; keeps the tag from being the raw device id and domain-separates it. */
    private const val SALT = "knit-device-tag-v1:"

    /** 8 bytes → 16 hex chars (64 bits): collision-resistant enough to never block a bystander. */
    private const val TAG_BYTES = 8
}
