package app.getknit.knit

import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import app.getknit.knit.crash.CrashHandler
import app.getknit.knit.crash.crashStore
import app.getknit.knit.crash.currentCrashEnvironment
import app.getknit.knit.crash.importAnrs
import app.getknit.knit.data.LinkCardStore
import app.getknit.knit.data.backup.RestoreApplier
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.di.appModule
import app.getknit.knit.di.callModule
import app.getknit.knit.di.meshModule
import app.getknit.knit.di.moderationModule
import app.getknit.knit.di.seedDemoIfEnabled
import app.getknit.knit.di.startCallsIfWired
import app.getknit.knit.di.startDemoDirectorIfEnabled
import app.getknit.knit.di.uiModule
import app.getknit.knit.mesh.power.AppVisibility
import app.getknit.knit.notifications.ConversationShortcutSync
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.transfer.DirectHostGuard
import app.getknit.knit.transfer.DirectWifi
import app.getknit.knit.ui.backup.RestartActivity
import app.getknit.knit.ui.image.BlobFetcher
import app.getknit.knit.ui.image.BlobKeyer
import app.getknit.knit.ui.image.LinkCardFetcher
import app.getknit.knit.ui.image.LinkCardKeyer
import app.getknit.knit.ui.theme.ThemePreferences
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class KnitApplication :
    Application(),
    SingletonImageLoader.Factory {
    // Resolved lazily — first touched in newImageLoader(), which Coil calls well after startKoin().
    private val blobDao: BlobDao by inject()
    private val linkCards: LinkCardStore by inject()

    override fun onCreate() {
        super.onCreate()
        // The restore trampoline's process (ui/backup/RestartActivity) exists to kill this one and relaunch
        // it; it must not build a Koin graph, open the DataStore a second time or seed anything of its own.
        if (getProcessName().endsWith(RestartActivity.PROCESS_SUFFIX)) return
        // Before startKoin, deliberately. The crashes worth capturing most are the ones in startup itself:
        // a SQLCipher or tflite .so that won't load, a Koin graph that throws while building KnitDatabase.
        // Every one of those kills the app before any injectable object exists, which is also why the store is
        // built by hand here rather than resolved. (A Keystore *refusal* is not one of them: MeshService and
        // ui/StorageGate stand down on it rather than crash, and nothing is wiped — ADR 2026-10.47rw.)
        // Chains to whatever handler was already default, so the "Knit keeps stopping" dialog and the
        // process kill still happen exactly as before.
        val crashes = crashStore(this)
        val environment = currentCrashEnvironment()
        CrashHandler.install(crashes, environment)
        // A backup restore staged before the relaunch lands here, while nothing has the database, the
        // identity file or the DataStore open — the one window in which those files can be replaced. After
        // the crash handler, so a failure in the move is captured like any other startup fault.
        RestoreApplier.applyPending(this)
        val koinApp =
            startKoin {
                androidLogger()
                androidContext(this@KnitApplication)
                // The voice calls' graph exists only in a build that wires them (di/CallWiring, debug today).
                modules(listOf(appModule, meshModule, moderationModule, uiModule) + listOfNotNull(callModule()))
            }
        // Register the message notification channel up front so it appears in system settings.
        koinApp.koin.get<Notifier>().createChannel()

        // Start the theme-flag read now, not at setContent. Koin singles are lazy, so resolving it here is
        // what actually begins the DataStore collect; MainActivity then reads an already-warmed value and
        // the app does not repaint from coral to the wallpaper palette a frame into launch.
        koinApp.koin.get<ThemePreferences>()

        // Knit is on screen while any of its activities is started, as the process lifecycle counts them (#170): one
        // activity's own onStart/onStop read false on a lit screen whenever a new MainActivity replaced an old one.
        // Here, on the main thread, because addObserver insists on it and the mesh graph resolves off it.
        ProcessLifecycleOwner.get().lifecycle.addObserver(koinApp.koin.get<AppVisibility>())

        // Seed the shipped default spools once (res/values/spools.xml). Opens no socket by itself — the
        // Internet plane stays off until the user turns it on — and a later removal sticks. A no-op while
        // the plane is dark (`BuildConfig.INTERNET_PLANE`), including the seeded marker, so the defaults
        // land on the first run of the build that introduces the feature.
        //
        // Its OWN coroutine, deliberately: chained behind warmUp() it inherited a ~16 MB model load, so a
        // fresh install sat with an unconfigured spool list for tens of seconds (observed on a Pixel 8),
        // and any throw from the warm-up would have skipped the seed entirely. The two share a scope, not
        // a sequence.
        //
        // A renamed house relay is moved to its current name first (ADR 2026-10.qpja): on every start, since a restore
        // brings the former name back, and it reads once and writes nothing on a phone with nothing to move. A commons
        // room is bound to its relay's URL, so it moves too — where the build has rooms, and never fatally: the database
        // may not open while the Keystore refuses (ADR 2026-10.47rw), and the next start tries again.
        koinApp.koin.get<CoroutineScope>().launch {
            val settings = koinApp.koin.get<SettingsStore>()
            settings.renameRelays()
            settings.seedDefaultSpools(resources.getStringArray(R.array.default_spools).toList())
            if (BuildConfig.COMMONS) runCatching { koinApp.koin.get<CommonsRepository>().renameRelays() }
        }

        // Settle what the previous process left about hosting a Wi-Fi Direct group (ADR 2026-10.w3dw): a group of ours
        // still marked up means it died hosting. Before any group goes up (the lease waits on it), so a second death
        // latches before a third try; one store read on a phone that never hosted.
        koinApp.koin.get<CoroutineScope>().launch {
            runCatching { koinApp.koin.get<DirectHostGuard>().load() }
        }

        // Clear a Wi-Fi Direct group a previous run left on air. Nothing removes one when the process dies
        // mid-transfer, and a live group both beacons our credentials and keeps Wi-Fi Aware off the radio.
        // Cheap when there is nothing to find (a binder and one query), never touches a group it did not
        // name, and held back past the cold-start window.
        koinApp.koin.get<CoroutineScope>().launch {
            delay(SWEEP_DELAY_MS)
            runCatching { koinApp.koin.get<DirectWifi>().sweep() }
        }

        // Keep the conversation shortcuts inside the chat list's universe (ADR 2026-10.jbsa): the first pass cleans
        // up what an older build left (a deleted or blocked chat still in the launcher's long-press menu), then the
        // sync watches for faces that change while the app is away. Held back past the cold start; a phone with no
        // shortcut opens nothing for it.
        koinApp.koin.get<CoroutineScope>().launch {
            delay(ConversationShortcutSync.START_DELAY_MS)
            koinApp.koin.get<ConversationShortcutSync>().start()
        }

        // Copy any ANR the platform recorded against an earlier process into the crash store, thread dump and all,
        // before the platform's few-day retention takes it: Play shows an idle-main ANR with no trace at all (ADR
        // 2026-10.fzsy). Off the main thread and past the cold start; a phone with nothing new pays one binder call.
        koinApp.koin.get<CoroutineScope>().launch(Dispatchers.IO) {
            delay(ANR_IMPORT_DELAY_MS)
            val added = importAnrs(this@KnitApplication, crashes, environment)
            if (added > 0) Log.i(TAG, "imported $added ANR report(s) into Diagnostics")
        }

        // Demo-screenshot mode (`-PseedDemo=true`): fill the DB with a realistic conversation history so
        // the app renders populated on an emulator. Debug-only — the seeder lives in `src/debug`, so this is
        // a no-op in release (see the per-variant di/DemoWiring). Off by default even in debug.
        seedDemoIfEnabled(koinApp.koin)
        // Demo-trailer mode (`-PdemoDirector=true`): play the scripted, animated promo conversation instead
        // of the static seed. Also debug-only and a no-op in release.
        startDemoDirectorIfEnabled(koinApp.koin)
        // Experimental voice calls (di/CallWiring): the ring and missed-call surfaces collect from process start, on
        // a background thread. A no-op in release.
        startCallsIfWired(koinApp.koin)
    }

    /**
     * App-wide Coil loader. Images come exclusively from the encrypted `blobs` table via
     * [BlobFetcher]/[BlobKeyer] — and, for the picture inside a link-preview card, through the card store via
     * [LinkCardFetcher]/[LinkCardKeyer]; there is deliberately no network fetcher, so nothing in the app can
     * load a URL. The disk cache is disabled so decrypted bytes are never persisted to disk (only the
     * in-memory bitmap cache is used). The animated decoder keeps GIFs/WebP animating.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .diskCache(null)
            .components {
                add(BlobKeyer())
                add(BlobFetcher.Factory(blobDao))
                add(LinkCardKeyer())
                add(LinkCardFetcher.Factory(linkCards))
                add(AnimatedImageDecoder.Factory())
            }.build()

    private companion object {
        /** How long the leftover-group sweep waits out the cold-start window: a stale group has waited this long already. */
        const val SWEEP_DELAY_MS = 8_000L

        /** How long the ANR import waits out the cold start; the platform keeps a record for days. */
        const val ANR_IMPORT_DELAY_MS = 5_000L

        const val TAG = "KnitApplication"
    }
}
