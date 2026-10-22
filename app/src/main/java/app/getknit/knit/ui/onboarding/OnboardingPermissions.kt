package app.getknit.knit.ui.onboarding

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.getknit.knit.di.CALLS_WIRED
import app.getknit.knit.ui.BackgroundBattery
import app.getknit.knit.ui.DeviceSupervision
import app.getknit.knit.ui.MeshPermissionTier
import app.getknit.knit.ui.UnusedAppPause
import app.getknit.knit.ui.backgroundBattery
import app.getknit.knit.ui.camera.openAppSettings
import app.getknit.knit.ui.deviceSupervision
import app.getknit.knit.ui.fullScreenCalls
import app.getknit.knit.ui.openFullScreenCallSettings
import app.getknit.knit.ui.openUnusedAppPauseSettings
import app.getknit.knit.ui.optionalNotificationPermission
import app.getknit.knit.ui.requestIgnoreBatteryOptimizations
import app.getknit.knit.ui.requiredRadioPermissions
import app.getknit.knit.ui.unusedAppPause

/**
 * What the permissions page draws. SDK-free on purpose: the holder below resolves the API tier, so the page
 * and its tests never read `Build.VERSION`. `needsSettings` means Android will not show that dialog again
 * and the row's action has to be "Open settings" rather than "Allow".
 */
@Immutable
data class PermissionRows(
    val radioGranted: Boolean,
    val radioNeedsSettings: Boolean,
    val notificationsGranted: Boolean,
    val notificationsNeedSettings: Boolean,
    val batteryExempt: Boolean,
    /**
     * Battery use set to Restricted (App info → App battery usage): the mesh stops the moment Knit leaves
     * the screen, and no prompt the app can raise changes that — the row reads it back as "Open settings"
     * with its own hint. Wins over [batteryExempt], which can still read true underneath it.
     */
    val batteryRestricted: Boolean = false,
    /**
     * Who else administers this phone. Decides which "Open settings" hint a row shows — the one that names
     * the parent or the administrator where they can actually hold that grant ([supervisedHint]).
     */
    val supervision: DeviceSupervision = DeviceSupervision.None,
    /**
     * Android's "Pause app activity if unused" switch, which takes the grants back after months without
     * use; `null` where the platform has no such switch (Android 10) and the row is not drawn. Its only
     * action is "Open settings" — there is no dialog for it — and it never touches Start.
     */
    val unusedPause: UnusedAppPause? = null,
    /**
     * Whether an incoming call may ring full screen over the lock screen; `null` where there is nothing to ask
     * (a build without calls, or below Android 14) and the row is not drawn. Like [unusedPause] it is a switch on a
     * system page, not a dialog, so its only action is that page — and this row is the one place Knit asks for it
     * unprompted (`fullScreenCalls`).
     */
    val fullScreenCalls: Boolean? = null,
) {
    companion object {
        /** A fresh install: nothing asked, nothing held. */
        val FRESH =
            PermissionRows(
                radioGranted = false,
                radioNeedsSettings = false,
                notificationsGranted = false,
                notificationsNeedSettings = false,
                batteryExempt = false,
            )

        /** Everything held. */
        val ALL =
            PermissionRows(
                radioGranted = true,
                radioNeedsSettings = false,
                notificationsGranted = true,
                notificationsNeedSettings = false,
                batteryExempt = true,
            )
    }
}

/** The rows plus the six things a row can do. Handed to the page by [rememberOnboardingPermissions]. */
@Stable
class OnboardingPermissions(
    val rows: PermissionRows,
    val requestRadio: () -> Unit,
    val requestNotifications: () -> Unit,
    val requestBattery: () -> Unit,
    val openSettings: () -> Unit,
    val openUnusedPauseSettings: () -> Unit,
    val openFullScreenSettings: () -> Unit,
)

/**
 * Whether a request for [missing] would go unanswered: the system dialog is gone for good once the user has
 * refused with "don't ask again" (or twice, on older releases). Android reports that as
 * `shouldShowRequestPermissionRationale == false` — but it reports the same **before the first ask**, so
 * [asked] is what tells the two apart. `any`, not `all`: one grant the dialog will skip is enough to strand
 * the whole set, since the radio request is one dialog sequence.
 */
internal fun needsSettings(
    asked: Boolean,
    missing: List<String>,
    canAskAgain: (String) -> Boolean,
): Boolean = asked && missing.any { !canAskAgain(it) }

/** The two rows whose grants somebody other than the user can hold; the battery row is never one. */
internal enum class GrantRow { Radio, Notifications }

/**
 * Which "Open settings" hint the row shows on a phone somebody else administers, or `null` for Android's
 * own "won't ask again" line. A policy-denied grant is indistinguishable from "don't ask again" from
 * inside the app (ADR 2026-09.a8ud), so the hint can only be shown where that policy *could* be the cause:
 *
 * - **Family Link** offers a parent only the classic groups — Location, Camera, Microphone… — never
 *   Nearby devices or Notifications. So the radio row can be parent-held only on the tiers that put
 *   Location in it (API 29–32); on 33+ the generic line stays, and the notifications row is never theirs.
 * - **Managed** (an EMM) can deny any runtime permission, so both rows name the administrator on every tier.
 */
internal fun supervisedHint(
    supervision: DeviceSupervision,
    tier: MeshPermissionTier,
    row: GrantRow,
): DeviceSupervision? =
    when (supervision) {
        DeviceSupervision.None -> {
            null
        }

        DeviceSupervision.Managed -> {
            DeviceSupervision.Managed
        }

        DeviceSupervision.FamilyLink -> {
            DeviceSupervision.FamilyLink.takeIf {
                row == GrantRow.Radio && tier != MeshPermissionTier.NEARBY_DEVICES
            }
        }
    }

/**
 * The permissions page's state: the five probes (radio set, notification grant, battery position, the
 * unused-app switch, full-screen calls), the two permission launchers, and the battery / app-settings intents. Everything
 * that needs an `Activity` or a `Context` lives here rather than in the ViewModel, in the
 * `rememberLocationGate` / `MicGate` idiom.
 *
 * The probes are re-read on every launcher result **and** on every resume: the system permission dialog,
 * the battery dialog and the app-info Settings page are all separate activities, so coming back from any of
 * them is an `ON_RESUME` on this back-stack entry — which is what flips a row to "Allowed" after the user
 * granted it in Settings. `asked` survives rotation (`rememberSaveable`) but not process death; a fresh
 * process that lost it self-heals on the next tap (an instant refusal callback sets it again), which is one
 * wasted tap and not worth a DataStore key.
 */
@Composable
internal fun rememberOnboardingPermissions(
    sdkInt: Int = Build.VERSION.SDK_INT,
    callsWired: Boolean = CALLS_WIRED,
): OnboardingPermissions {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val radioSet = remember(sdkInt) { requiredRadioPermissions(sdkInt) }
    val notificationPermission = remember(sdkInt) { optionalNotificationPermission(sdkInt) }
    var radioAsked by rememberSaveable { mutableStateOf(false) }
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }

    fun canAskAgain(permission: String): Boolean =
        activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: true

    // Probed as one unit, on demand, and held as state — not derived in composition. A second refusal
    // changes nothing Compose can see (the grants were false before, `asked` was already true), so a
    // rationale re-asked during composition would never run again and the row would stay on "Allow".
    fun probe(): PermissionRows {
        val missingRadio = radioSet.filter { !context.holds(it) }
        val missingNotifications = listOfNotNull(notificationPermission).filter { !context.holds(it) }
        val battery = backgroundBattery(context)
        val supervision = deviceSupervision(context)
        return PermissionRows(
            radioGranted = missingRadio.isEmpty(),
            radioNeedsSettings = needsSettings(radioAsked, missingRadio, ::canAskAgain),
            notificationsGranted = missingNotifications.isEmpty(),
            notificationsNeedSettings = needsSettings(notificationsAsked, missingNotifications, ::canAskAgain),
            batteryExempt = battery == BackgroundBattery.Unrestricted,
            batteryRestricted = battery == BackgroundBattery.Restricted,
            supervision = supervision,
            unusedPause = unusedAppPause(context),
            fullScreenCalls = fullScreenCalls(context, callsWired),
        )
    }
    var rows by remember { mutableStateOf(probe()) }

    val radioLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            radioAsked = true
            // Re-probe rather than read the result map: a partial grant is a map of mixed values, and the
            // gate is "all of them".
            rows = probe()
        }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsAsked = true
            rows = probe()
        }
    LifecycleResumeEffect(Unit) {
        rows = probe()
        onPauseOrDispose { }
    }

    return OnboardingPermissions(
        rows = rows,
        requestRadio = { radioLauncher.launch(radioSet) },
        requestNotifications = { notificationPermission?.let(notificationLauncher::launch) },
        requestBattery = { requestIgnoreBatteryOptimizations(context) },
        openSettings = { openAppSettings(context) },
        openUnusedPauseSettings = { openUnusedAppPauseSettings(context) },
        openFullScreenSettings = { openFullScreenCallSettings(context) },
    )
}

private fun Context.holds(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
