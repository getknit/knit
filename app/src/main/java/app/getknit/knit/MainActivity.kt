package app.getknit.knit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.contentcapture.ContentCaptureManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.getknit.knit.ui.KnitApp
import app.getknit.knit.ui.RouteInbox
import app.getknit.knit.ui.StorageGate
import app.getknit.knit.ui.StorageUnavailableScreen
import app.getknit.knit.ui.WindowWedgePolicy
import app.getknit.knit.ui.addcontact.ContactCardInbox
import app.getknit.knit.ui.addcontact.contactLinkFrom
import app.getknit.knit.ui.relay.RelayInviteInbox
import app.getknit.knit.ui.relay.relayInviteFrom
import app.getknit.knit.ui.share.ShareInbox
import app.getknit.knit.ui.share.SharedContent
import app.getknit.knit.ui.signout.SignOut
import app.getknit.knit.ui.theme.KnitTheme
import app.getknit.knit.ui.theme.ThemePreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import androidx.compose.ui.contentcapture.ContentCaptureManager as ComposeContentCaptureManager

class MainActivity : ComponentActivity() {
    // Single-shot holder for content arriving via the system share sheet; KnitApp/ChatScreen drain it.
    private val shareInbox: ShareInbox by inject()

    // Single-shot holder for a notification-tap deep-link route (e.g. "chat/<id>"); KnitApp drains it.
    private val routeInbox: RouteInbox by inject()

    // Single-shot holder for a contact link (a tapped getknit.app/c link, or a shared text carrying one).
    private val contactCardInbox: ContactCardInbox by inject()

    // Single-shot holder for a relay invite (a tapped getknit.app/r link, or a shared text carrying one).
    private val relayInviteInbox: RelayInviteInbox by inject()

    // The theme flags, already warmed by KnitApplication so the first composition reads a settled value.
    private val themePrefs: ThemePreferences by inject()

    // Opens the database and the identity off the main thread before KnitApp composes (ADR 2026-10.47rw).
    private val storageGate: StorageGate by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        disableContentCapture()
        watchForUndrawnWindow()
        // A cold-start share: stage the payload before composition so KnitApp opens the picker.
        handleShareIntent(intent)
        // A cold-start notification tap: stage its deep-link route so KnitApp navigates to that thread.
        handleRouteIntent(intent)
        // A cold-start contact link: stage it so KnitApp opens the Add-contact screen.
        handleContactLinkIntent(intent)
        // A cold-start relay invite: stage it so KnitApp opens the Internet-relays screen.
        handleRelayInviteIntent(intent)
        // Debug builds honor a deep-link route extra so screenshots (demo builds) and automation agents
        // (any debug build, over the real mesh) can jump straight to a screen, e.g.
        // `adb shell am start -n app.getknit.knit/.MainActivity --es demo_route chat/nearby`. Gated to
        // debug so release never reads it. (Demo builds still swap in DemoTransport via SEED_DEMO.)
        val startRoute =
            if (BuildConfig.SEED_DEMO || BuildConfig.DEBUG) {
                intent?.getStringExtra(EXTRA_DEMO_ROUTE)
            } else {
                null
            }
        storageGate.open()
        retryStorageOnResume()
        setContent {
            // collectAsStateWithLifecycle seeds the first composition from the StateFlow's current value,
            // and keeps the theme live when the switch is toggled in Settings without leaving the app.
            val dynamicColor by themePrefs.dynamicColor.collectAsStateWithLifecycle()
            val storage by storageGate.state.collectAsStateWithLifecycle()
            KnitTheme(dynamicColor = dynamicColor) {
                when (val state = storage) {
                    StorageGate.State.Ready -> {
                        KnitApp(startRoute = startRoute)
                    }

                    is StorageGate.State.Unavailable -> {
                        // Start over is "Sign out here"'s wipe: the platform's Clear storage, Keystore keys included,
                        // so the next open is onboarding (where a backup can be restored) under a fresh key.
                        StorageUnavailableScreen(
                            trying = state.trying,
                            onRetry = storageGate::open,
                            onStartOver = { SignOut.here(this@MainActivity) },
                        )
                    }

                    StorageGate.State.Opening -> {
                        // Nothing to draw yet, and nothing is: see holdFirstDrawWhileOpening.
                    }
                }
            }
        }
        holdFirstDrawWhileOpening()
    }

    /**
     * Keeps the first frame back while [storageGate] opens storage on its worker, so the launch looks exactly as it
     * did when `KnitApp` opened it on the main thread: the system splash (or, below API 31, the splash-coloured
     * window background) until the app's first real frame. The standard way to hold a splash — what
     * `SplashScreen.setKeepOnScreenCondition` does inside — and it cannot trip [WindowWedgePolicy], which acts on
     * a window the platform reports *not visible*; a held draw leaves the window visible. A healthy open is the
     * few hundred milliseconds the main thread used to block for; a refusal ends in the Try again screen.
     */
    private fun holdFirstDrawWhileOpening() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (storageGate.state.value == StorageGate.State.Opening) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
    }

    /** A refusal is worth another try every time the user comes back to the app; [StorageGate.open] is idempotent. */
    private fun retryStorageOnResume() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (storageGate.state.value is StorageGate.State.Unavailable) storageGate.open()
            }
        }
    }

    /**
     * Opt the whole app out of content capture (the on-device "app content" feed to Android System
     * Intelligence): an offline, end-to-end-encrypted messenger has no business streaming its screen text to
     * another process. Two switches, because the platform one only silences the events: Compose keeps its own
     * manager and still re-walks every on-screen semantics node whenever the tree changes (measured: ~2 ms per
     * frame across a 120-cell emoji grid fling, ~260 ms per fling), and only its flag stops that.
     *
     * `DEPRECATION`: compileSdk 37.1 deprecates the platform flag with no replacement that reaches minSdk 29 —
     * and the deprecated setter still works, which is the whole point. Extracted into its own function so the
     * suppression covers exactly this call and not the rest of `onCreate`.
     */
    @Suppress("DEPRECATION")
    @OptIn(ExperimentalComposeUiApi::class)
    private fun disableContentCapture() {
        getSystemService(ContentCaptureManager::class.java)?.isContentCaptureEnabled = false
        ComposeContentCaptureManager.isEnabled = false
    }

    /**
     * Recover from a window the platform never makes visible (ADR 2026-09.un9n).
     *
     * Observed on a Pixel 7 (Android 17, `CP2A.260705.006`) after a launcher tap landed ~100 ms into a
     * back-to-home teardown, which made AMS open a *second* task for this `singleTask` Activity
     * (`Add Task{#12257} to hidden list because adding Task{#12260}`). The replacement Activity then held
     * input focus and ran normally — back callbacks registered and unregistered, our own Compose popups
     * drew — while `ViewRootImpl` reported `!mAppVisible` and therefore drew the main window **not once in
     * 94 seconds**. All the user sees is `windowBackground`, so a live, fully interactive app reads as
     * frozen on a blank screen, and reopening from the launcher cannot help: `singleTask` re-resumes the
     * very same window. Nothing an app does causes this and nothing short of a new window clears it.
     *
     * So: poll only while RESUMED (the loop is cancelled at ON_PAUSE), and hand the three observations to
     * [WindowWedgePolicy], which owns the grace period, the cooldown and the per-process ceiling. The
     * ceiling and the last-recreate stamp live in the companion **on purpose** — they must outlive the
     * Activity they are protecting, or every recreate would reset its own loop guard.
     */
    private fun watchForUndrawnWindow() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var wedgedSince = 0L
                while (true) {
                    delay(WEDGE_POLL_MS)
                    val decision =
                        WindowWedgePolicy.decide(
                            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                            focused = hasWindowFocus(),
                            windowVisible = window.decorView.windowVisibility == View.VISIBLE,
                            recreatable = !isFinishing && !isChangingConfigurations,
                            now = SystemClock.elapsedRealtime(),
                            wedgedSince = wedgedSince,
                            lastRecreateAt = lastWedgeRecreateAt,
                            recreates = wedgeRecreates,
                            graceMs = WEDGE_GRACE_MS,
                            cooldownMs = WEDGE_COOLDOWN_MS,
                            maxRecreates = MAX_WEDGE_RECREATES,
                        )
                    wedgedSince = decision.nextWedgedSince
                    if (decision.action != WindowWedgePolicy.Action.Recreate) continue
                    lastWedgeRecreateAt = SystemClock.elapsedRealtime()
                    wedgeRecreates++
                    Log.e(TAG, "window resumed+focused but not visible for ${WEDGE_GRACE_MS}ms — recreating (#$wedgeRecreates)")
                    recreate()
                    return@repeatOnLifecycle
                }
            }
        }
    }

    // Share into an already-running instance (launchMode=singleTask). Re-stage into the inbox; KnitApp
    // observes it and routes to the share-target picker.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        // A notification tap on an already-running instance: stage the deep-link route; KnitApp navigates.
        handleRouteIntent(intent)
        handleContactLinkIntent(intent)
        handleRelayInviteIntent(intent)
    }

    /** Stage a contact link (a VIEW of a card link, or a SEND whose text carries one) into the [ContactCardInbox]. */
    private fun handleContactLinkIntent(intent: Intent?) {
        val link = contactLinkFrom(intent?.action, intent?.dataString, intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        if (link != null) contactCardInbox.offer(link)
    }

    /** Stage a relay invite (a VIEW of an invite link, or a SEND whose text carries one) into the [RelayInviteInbox]. */
    private fun handleRelayInviteIntent(intent: Intent?) {
        val link = relayInviteFrom(intent?.action, intent?.dataString, intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        if (link != null) relayInviteInbox.offer(link)
    }

    /** Stage a notification deep-link route ([EXTRA_ROUTE], e.g. "chat/<id>") into the [RouteInbox]. */
    private fun handleRouteIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ROUTE)?.let { routeInbox.offer(it) }
    }

    /** Parse an ACTION_SEND intent into the [ShareInbox]. Other intents (incl. the launcher) are ignored. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        // A shared text that IS a contact link or a relay invite is an import, not a message draft — the
        // Android-idiomatic route for a link on 12+, where an unverified https link opens in the browser
        // rather than here.
        if (contactLinkFrom(intent.action, null, text) != null || relayInviteFrom(intent.action, null, text) != null) return
        // EXTRA_STREAM is read-granted for any stream our filters accept, which since ADR 2026-09.qq2r is
        // any type at all. The split is by *destination*, not by grant: an image can be attached in any
        // thread, while a file is offered only in DMs and groups, so the two ride separate fields and the
        // chat screen says so when it cannot take one.
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toString()
        val isImage = intent.type?.startsWith("image/") == true
        shareInbox.offer(
            SharedContent(
                text = text,
                imageUri = stream?.takeIf { isImage },
                fileUri = stream?.takeIf { !isImage },
            ),
        )
    }

    companion object {
        /** Deep-link route extra set by [app.getknit.knit.notifications.MessageNotifier] on a notification tap. */
        const val EXTRA_ROUTE = "app.getknit.knit.NOTIF_ROUTE"
        private const val EXTRA_DEMO_ROUTE = "demo_route"

        private const val TAG = "MainActivity"

        /** How often [watchForUndrawnWindow] samples, and for how long the wedge must hold before it acts. */
        private const val WEDGE_POLL_MS = 500L
        private const val WEDGE_GRACE_MS = 2_500L

        /**
         * Loop guards for [watchForUndrawnWindow]'s `recreate()`, **process-scoped rather than per-Activity**:
         * the thing they protect against is the replacement window wedging too, so an Activity field would
         * reset the guard on the very recreate it is supposed to be counting. A wedge that outlives three
         * attempts is not one we can clear, and flickering at the user forever is worse than a blank screen.
         */
        @Volatile private var lastWedgeRecreateAt = 0L

        @Volatile private var wedgeRecreates = 0
        private const val WEDGE_COOLDOWN_MS = 60_000L
        private const val MAX_WEDGE_RECREATES = 3
    }
}
