package app.getknit.knit.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager

/**
 * Who, besides the user, holds this phone's switches. Read wherever a permission row lands on "Open settings"
 * and on the two status surfaces (Settings, Diagnostics), so a grant a parent or an administrator turned off
 * is named as theirs instead of sent to a greyed-out toggle. ADR 2026-09.a8ud.
 *
 * A policy-denied runtime permission (`DevicePolicyManager.setPermissionGrantState`) looks exactly like a
 * "don't ask again" from inside the app — an instant, dialog-less refusal with
 * `shouldShowRequestPermissionRationale == false` — and the `POLICY_FIXED` flag behind it has no public read.
 * So this is the only signal the copy can use: not "this grant is blocked", only "this phone has someone who
 * could have blocked it".
 */
enum class DeviceSupervision {
    /** Nobody but the user: a plain consumer phone. */
    None,

    /**
     * A Google Family Link supervised child account. Device-verified 2026-09-16: the supervision component
     * is the profile owner of the primary user, app limits and downtime suspend the package without touching
     * a running foreground service, and a parent's permission denial arrives as `POLICY_FIXED`. The parent
     * app exposes only the classic groups (Location, Camera, Microphone, …), never Nearby devices.
     */
    FamilyLink,

    /**
     * Something else holds policy — a work profile, an organisation-owned device, or any user restriction
     * in force. An EMM can deny any runtime permission, so the copy names "whoever manages this phone".
     */
    Managed,
}

/** The package Family Link registers as profile owner on a supervised primary user (Android 11+). */
internal const val FAMILY_LINK_SUPERVISION_PACKAGE = "com.google.android.gms.supervision"

/** Which of the three positions the phone is in right now. Every read is best-effort and falls to [None]. */
fun deviceSupervision(context: Context): DeviceSupervision =
    when {
        isFamilyLinkSupervised(context) -> DeviceSupervision.FamilyLink
        isManaged(context) -> DeviceSupervision.Managed
        else -> DeviceSupervision.None
    }

private fun isFamilyLinkSupervised(context: Context): Boolean =
    runCatching {
        context.getSystemService(DevicePolicyManager::class.java)?.isProfileOwnerApp(FAMILY_LINK_SUPERVISION_PACKAGE)
    }.getOrNull() == true

private fun isManaged(context: Context): Boolean =
    runCatching {
        val userManager = context.getSystemService(UserManager::class.java)
        val devicePolicy = context.getSystemService(DevicePolicyManager::class.java)
        val profileOrOrgOwned =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                (
                    userManager?.isManagedProfile == true ||
                        devicePolicy?.isOrganizationOwnedDeviceWithManagedProfile == true
                )
        // Only a device owner or a profile owner can hold a switch. A plain device admin (Find My Device is one
        // on most phones) cannot, and the user-restriction bundle is not a signal either: the system keeps its
        // own keys there — an unmanaged Pixel 3 with its bootloader locked carried `no_oem_unlock=true`, a key
        // no owner may even set — so any read of it names a consumer phone as Managed sooner or later.
        val ownerPresent =
            devicePolicy != null &&
                devicePolicy.activeAdmins.orEmpty().any { admin ->
                    devicePolicy.isDeviceOwnerApp(admin.packageName) || devicePolicy.isProfileOwnerApp(admin.packageName)
                }
        profileOrOrgOwned || ownerPresent
    }.getOrNull() == true
