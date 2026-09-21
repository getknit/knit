package app.getknit.knit.mesh

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.getknit.knit.MainActivity
import app.getknit.knit.R
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.di.isKoinStarted
import app.getknit.knit.mesh.power.PowerMonitor
import app.getknit.knit.moderation.MlTextModerator
import app.getknit.knit.notifications.NotificationChannels
import app.getknit.knit.ui.isIgnoringBatteryOptimizations
import app.getknit.knit.ui.requiredRadioPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Foreground service that keeps the mesh alive while the app is backgrounded. Hosts the singleton
 * [MeshManager] and adds the background-survival machinery: a periodic heartbeat alarm and a
 * significant-motion trigger (new location → likely new peers), both of which nudge the transport to
 * rediscover/reconnect. (Wi-Fi Aware availability changes are handled inside the transport itself.)
 * The UI controls the mesh by starting/stopping it.
 */
class MeshService : LifecycleService() {
    private val meshManager: MeshController by inject()
    private val powerMonitor: PowerMonitor by inject()
    private val settings: SettingsStore by inject()
    private val scope: CoroutineScope by inject()
    private val textModel: MlTextModerator by inject()

    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private var significantMotion: Sensor? = null

    /**
     * Whether this instance actually holds the foreground state. False marks a **stillbirth** — the system
     * created the service at a moment we were not allowed to be foreground (see [postForeground]) — and every
     * lifecycle callback bails on it rather than touching the injected graph.
     */
    private var foregrounded = false

    /**
     * The other stillbirth: the system created us in a process that never ran `KnitApplication.onCreate`
     * (restricted backup mode — see [isKoinStarted]), so there is no graph to resolve and every `by inject()`
     * above would throw. Unlike a refused foreground start it leaves the heartbeat alarm armed: nothing was
     * refused, and its next tick lands in a normal process and starts the mesh properly.
     */
    private var graphless = false

    /**
     * The graph is resolved and the start tail ran on this instance — set by [startMesh], on the main thread,
     * and read by every later callback. Until then `onStartCommand` and `onDestroy` touch nothing behind a
     * `by inject()` above: the build is in flight on a worker (see [onCreate]), and a read would block the main
     * thread on Koin's single lock for exactly the seconds the build was moved off it to save. Whether the
     * mesh itself is up is [meshRunning]: a started service holds it down for the length of a pause.
     */
    private var meshStarted = false

    /**
     * `onDestroy` ran. A graph build still in flight checks it when it comes back to the main thread, so a
     * service the system took down mid-build never starts a mesh that nothing will stop.
     */
    private var destroyed = false

    /**
     * The pause this instance is honouring — the deadline [applyPause] last applied, main thread only — and
     * whether [meshManager] is up. The two are what every apply converges on, so the seed read on the worker,
     * the store collector's first emission, a notification tap and the banner's write can land in any order,
     * or twice, and the mesh ends in the state the store says. ADR 2026-09.wz99.
     */
    private var pausedUntil: Long? = null
    private var meshRunning = false

    /** The store collector ([observePause]); cancelled by Stop so its own null write cannot restart the mesh. */
    private var pauseJob: Job? = null

    /** The service's last write to the pause key, joined by the next so two of ours cannot land out of order. */
    private var lastPauseWrite: Job? = null

    /** Wall clock, replaceable by tests (`SystemClock` is shadowed under Robolectric; `System` is not). */
    internal var clock: () -> Long = System::currentTimeMillis

    private val motionListener =
        object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                meshManager.heal()
                armSignificantMotion() // one-shot sensor; re-arm for the next move
            }
        }

    override fun onCreate() {
        super.onCreate()
        // No graph in this process (restricted backup mode): decline before anything resolves it. stopSelf()
        // clears the sticky restart record; the heartbeat stays armed (see [graphless]).
        if (!isKoinStarted()) {
            Log.w(TAG, "created without the app graph (restricted backup mode?) — declining until a normal start")
            graphless = true
            stopSelf()
            return
        }
        // Channels are normally created at app startup (KnitApplication); ensure defensively in case
        // the process is started straight into the service.
        NotificationChannels.ensure(this)
        // Claim the foreground state before anything resolves the Koin graph — see [startForeground]. Every
        // line below it (observeStatus, powerMonitor, meshManager, settings) opens the database and the
        // keystore identity, and doing that first is what used to blow the startForegroundService deadline.
        foregrounded = startForeground()
        // Refused (see [postForeground]): leave without resolving the graph and without clearing `meshEnabled`,
        // so the next foreground app open (KnitApp) or the next reboot (BootReceiver) starts the mesh normally.
        // stopSelf() also clears the sticky restart record, so the system stops retrying a start that can't work.
        if (!foregrounded) {
            stopSelf()
            return
        }
        // The first read of each `by inject()` lazy builds its subtree of the graph — the keystore identity,
        // the Tink keysets (an Ed25519 table precompute, interpreted on a cold process), the transports, the
        // database — which is seconds on a slow phone and longer behind a busy keystore after boot. onCreate
        // and the onStartCommand right behind it run under the 20 s "executing service" ANR timer, a separate
        // deadline from the startForeground() one claimed above, and Play reported it lapsing (2.6.0, API 34)
        // in a process born for this service: the Activity path builds the same graph during KnitApp's first
        // composition, so the service's read there is a cache hit and only a boot, heartbeat or sticky restart
        // ever paid it here. So the build rides the app scope and only the start comes back to the main thread.
        // The app scope, not lifecycleScope: a built graph is process-wide and never wasted, and [destroyed]
        // covers the one thing cancellation would have. ADR 2026-09.vztn.
        scope.launch {
            // The pause deadline rides the same worker hop: a suspend read the main thread would otherwise wait on.
            val graph = runCatching { resolveGraph().let { settings.meshPausedUntil.first() } }
            withContext(Dispatchers.Main.immediate) {
                graph
                    .onSuccess { pausedSeed -> if (!destroyed) startMesh(pausedSeed) }
                    // A graph that cannot be built was a crash out of onCreate and still is one: re-thrown as
                    // an uncaught exception on the main thread — past the scope's handler, which would only
                    // log it and leave a "searching" notification that never resolves — so CrashHandler
                    // records it exactly as before.
                    .onFailure { failure -> Handler(Looper.getMainLooper()).post { throw failure } }
            }
        }
    }

    /**
     * Resolve the injected roots the start path reads, off the main thread. Each is a lazy whose first read
     * builds its subtree, so the values are the side effect; the log line is the one place the build's cost
     * and thread are visible on a device.
     */
    private fun resolveGraph(): List<Any> {
        val began = SystemClock.elapsedRealtime()
        val roots = listOf(meshManager, powerMonitor, settings)
        Log.i(TAG, "mesh graph resolved in ${SystemClock.elapsedRealtime() - began} ms on ${Thread.currentThread().name}")
        return roots
    }

    /**
     * The second half of [onCreate], on the main thread, once [resolveGraph] has made every read below cheap.
     * [pausedSeed] is the store's pause deadline as read on the worker: a live one brings the service up
     * paused — radios down, resume alarms armed — instead of raising the links a pause exists to keep down.
     */
    private fun startMesh(pausedSeed: Long?) {
        observeStatus()
        warmModelOnFirstPeer()
        powerMonitor.start() // seed power state before the discovery loop first reads it
        applyPause(MeshPause.activeDeadline(pausedSeed, clock()))
        // observeStatus may have posted the running text before the seed was applied; say "paused" now.
        if (pausedUntil != null) refreshNotification()
        meshStarted = true
        // Remember the mesh is running so BootReceiver restores it after a reboot; a later manual Stop
        // flips this off. Guarded to skip the redundant write on the common already-enabled start.
        scope.launch { if (!settings.meshEnabled.first()) settings.setMeshEnabled(true) }
        scheduleHeartbeat()
        armSignificantMotion()
        observePause()
    }

    /**
     * Converge on [until]: null runs the mesh, a deadline holds it down with the resume alarms armed for it.
     * Idempotent on [pausedUntil] and [meshRunning] rather than edge-triggered, so every caller — the seed,
     * the store collector, a notification tap — can apply what it knows without caring who applied it first.
     * Returns whether anything changed; posting the notification is the caller's, since [onStartCommand]
     * re-claims at its tail anyway. Never runs after [destroyed]: a stopped service starts nothing.
     */
    private fun applyPause(until: Long?): Boolean {
        if (destroyed) return false
        val changed = until != pausedUntil
        pausedUntil = until
        if (until == null) {
            if (changed) cancelResume()
            if (!meshRunning) {
                meshRunning = true
                meshManager.start()
            }
        } else {
            if (meshRunning) {
                meshRunning = false
                meshManager.stop()
            }
            if (changed) armResume(until)
        }
        return changed
    }

    /**
     * Follow the store: the chat list's Resume and the debug bridge write the key and nothing else, and this
     * is how the write reaches the radios. A deadline already in the past applies as "not paused".
     */
    private fun observePause() {
        pauseJob =
            lifecycleScope.launch {
                settings.meshPausedUntil.collect { raw ->
                    if (applyPause(MeshPause.activeDeadline(raw, clock()))) refreshNotification()
                }
            }
    }

    /** A pause whose deadline has passed while no alarm reached us (a late inexact delivery) ends on any start. */
    private fun expirePauseIfDue() {
        val until = pausedUntil ?: return
        if (until <= clock()) resumeNow()
    }

    /**
     * The notification's Pause: applied inline first, so the re-claim at the tail of [onStartCommand] posts the
     * paused text in the same call (no flicker through the running one), then written. Before [meshStarted]
     * only the store is written and the seed or the collector reconciles. An unoffered span is ignored — the
     * value rides a `PendingIntent` extra.
     */
    private fun pauseFor(minutes: Int) {
        val until = MeshPause.deadline(clock(), minutes) ?: return
        if (meshStarted) applyPause(until)
        writePause(until)
    }

    /** The notification's Resume, the resume alarms and an expired deadline: back up, inline, then written. */
    private fun resumeNow() {
        if (meshStarted) applyPause(null)
        writePause(null)
    }

    /** On the app scope so it outlives a Stop; serialised so an expiry's null cannot land over a newer Pause. */
    private fun writePause(value: Long?) {
        val previous = lastPauseWrite
        lastPauseWrite =
            scope.launch {
                previous?.join()
                settings.setMeshPausedUntil(value)
            }
    }

    private fun refreshNotification() {
        postForeground(buildNotification(meshManager.neighborCount.value, meshManager.transportHealth.value))
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        // Stillborn instance: don't act on the intent — every branch below resolves the Koin graph — and don't
        // return START_STICKY, which would re-arm the restart that landed us here.
        if (!foregrounded) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            // User tapped Stop on the ongoing notification: remember it so we don't auto-restart on
            // the next reboot. On the app-lifetime scope so the write outlives stopSelf()/onDestroy().
            // A pause ends with it — stopped is stopped, and the next start must not come up paused — but the
            // collector goes first: stopSelf() is asynchronous and lifecycleScope outlives onDestroy, so its
            // own null write would otherwise raise the radios for the moment before they come down again.
            pauseJob?.cancel()
            scope.launch { settings.setMeshEnabled(false) }
            writePause(null)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_HEAL -> {
                // A mesh still coming up (see [onCreate]) starts fresh, which is all a heal would do; a paused
                // one is not running, and heal() is a no-op on it.
                expirePauseIfDue()
                if (meshStarted) meshManager.heal()
            }

            // A new pause supersedes an expired one outright: expiring first would raise the radios for the
            // instant before this took them down again.
            ACTION_PAUSE -> {
                pauseFor(intent.getIntExtra(EXTRA_PAUSE_MINUTES, 0))
            }

            ACTION_RESUME -> {
                resumeNow()
            }

            // A plain start — KnitApp on every navigation and resume, a sticky restart's first command.
            else -> {
                expirePauseIfDue()
            }
        }
        // Re-claim the foreground state on every start, not only the first. The system can take it from a
        // running service without telling it: a background-restricted app loses it the moment it leaves the
        // screen (`ActiveServices.stopAllForegroundServicesLocked`, a `stopForeground` with no callback), and
        // the service runs on as a plain background service for the ~60 s settle time before it is stopped.
        // A `startForegroundService` into that demoted instance — `KnitApp`'s resume observer, the very next
        // open — arms the startForeground() deadline against this method, which used to return without ever
        // calling it: `ForegroundServiceDidNotStartInTimeException`, Play-reported on 2.5.1 / Android 15. Same
        // call `observeStatus` makes on every update, so it is idempotent and cheap; a refusal means the state
        // is gone for this session, and stopping here (the mesh comes down in `onDestroy`) beats running a
        // service the system is about to stop anyway. ADR 2026-09.f69x.
        //
        // The start that created this instance lands here in the same main-thread batch as onCreate, while the
        // graph is still building on a worker: until [meshStarted] the claim re-posts the "searching" seed
        // rather than reading a count that would block on the build (ADR 2026-09.vztn).
        val notification =
            if (meshStarted) {
                buildNotification(meshManager.neighborCount.value, meshManager.transportHealth.value)
            } else {
                buildNotification(count = 0, health = null)
            }
        if (!postForeground(notification)) {
            Log.w(TAG, "foreground state refused on restart — stopping until the app is next opened")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        // Nothing was ever started (see [onCreate]); touching the injected fields here would build the very
        // Koin graph the stillbirth path exists to skip. The heartbeat alarm is still cancelled: it needs no
        // graph, and a live one left armed by an earlier ungraceful death would otherwise keep waking the
        // device every 15 minutes to attempt a background service start the system will refuse. A [graphless]
        // instance keeps it: that start was never refused, only landed in the wrong process.
        // The resume alarms go with the heartbeat, for the same reason: left armed past a death, one would
        // create a service the system refuses (a stillbirth) or, on 29-30, a mesh nobody asked for.
        if (!foregrounded) {
            if (!graphless) {
                cancelHeartbeat()
                cancelResume()
            }
            super.onDestroy()
            return
        }
        // Taken down while the graph was still building on a worker: nothing is running yet, and the build's
        // return to the main thread reads [destroyed] and starts nothing. Reading the injected fields here
        // would block on that build.
        if (!meshStarted) {
            cancelHeartbeat()
            cancelResume()
            super.onDestroy()
            return
        }
        powerMonitor.stop()
        significantMotion?.let { sensorManager.cancelTriggerSensor(motionListener, it) }
        cancelHeartbeat()
        cancelResume()
        if (meshRunning) meshManager.stop()
        super.onDestroy()
    }

    private fun armSignificantMotion() {
        significantMotion = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        significantMotion?.let { sensorManager.requestTriggerSensor(motionListener, it) }
    }

    private fun heartbeatIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            2,
            Intent(this, MeshService::class.java).setAction(ACTION_HEAL),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun scheduleHeartbeat() {
        getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            heartbeatIntent(),
        )
    }

    private fun cancelHeartbeat() {
        getSystemService(AlarmManager::class.java).cancel(heartbeatIntent())
    }

    /** The Resume action and both resume alarms carry this; the notification shares the while-idle token. */
    private fun resumeIntent(requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, MeshService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Two inexact alarms for one deadline, because neither alone is enough without `SCHEDULE_EXACT_ALARM`
     * (a Settings trip on 14+, for a messenger): `setAndAllowWhileIdle` reaches a dozing phone but its
     * delivery window is 0.75 × the span (`AlarmManagerService.maxTriggerTime` — 45 min late on a one-hour
     * pause is legal); `setWindow` is bounded to ten minutes on an awake phone (the floor our targetSdk gets)
     * but is held to a maintenance window in Doze. Same action, different tokens, armed and cancelled as one.
     * The alarms' `startService` lands on a running foreground service, like the heartbeat's; they never
     * create one on purpose. Late is still covered: every start runs [expirePauseIfDue].
     */
    private fun armResume(until: Long) {
        val at = SystemClock.elapsedRealtime() + (until - clock()).coerceAtLeast(0L)
        val alarms = getSystemService(AlarmManager::class.java)
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, resumeIntent(REQUEST_RESUME_IDLE))
        alarms.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, RESUME_WINDOW_MS, resumeIntent(REQUEST_RESUME_WINDOW))
    }

    private fun cancelResume() {
        val alarms = getSystemService(AlarmManager::class.java)
        alarms.cancel(resumeIntent(REQUEST_RESUME_IDLE))
        alarms.cancel(resumeIntent(REQUEST_RESUME_WINDOW))
    }

    /**
     * Post the initial ongoing notification synchronously, from a fixed "searching" seed.
     *
     * **Deliberately reads nothing from [meshManager].** Touching it here would resolve the mesh half of
     * the Koin graph — opening the SQLCipher-backed Room database and minting/unwrapping the keystore
     * identity — before we ever reach [ServiceCompat.startForeground], and `onCreate` runs on the main
     * thread. AOSP gives `startForegroundService` a deadline (`mServiceStartForegroundTimeoutMs`: 10 s
     * through Android 14, 30 s on 15) and kills the process with
     * `ForegroundServiceDidNotStartInTimeException` when it lapses, so on slow
     * hardware that graph build was a launch-time crash. Now the foreground state is claimed first and the
     * graph is built after — and off the main thread, because `onCreate` also has the 20 s "executing
     * service" ANR timer to meet (see [onCreate]); [observeStatus] replaces this text with the live
     * count/health as soon as the first value arrives.
     *
     * Returns whether the foreground state was actually claimed — it can be refused, see [postForeground].
     */
    private fun startForeground(): Boolean = postForeground(buildNotification(count = 0, health = null))

    /**
     * Keep the ongoing notification's text in step with live connectivity: the reachable-peer count and
     * radio health. [MeshManager.neighborCount] is already smoothed (it rides the lingered `reachable`
     * set), so the text won't thrash as ephemeral links flap. Cancelled with the service via
     * [lifecycleScope].
     */
    private fun observeStatus() {
        lifecycleScope.launch {
            combine(meshManager.neighborCount, meshManager.transportHealth) { count, health ->
                count to health
            }.distinctUntilChanged()
                .collect { (count, health) -> postForeground(buildNotification(count, health)) }
        }
    }

    /**
     * Load the toxicity model each time a peer comes into range after none was, off the inbound path. The
     * first classify() otherwise pays the model load inline in the router's single inbound collector,
     * stalling both radios for the duration; a peer nearby is the one signal that a message may be about to
     * need it. A phone alone in a drawer never loads it (the warm-up used to run 5 s into every process
     * start), and one that let the model go after ten idle minutes alone gets it back when company arrives —
     * if this beats their first message; a peer that stays through the idle release is not an edge, and that
     * message reloads inline (`ModelLease`). The watch rides [lifecycleScope] so a stopped service drops it;
     * the load itself rides the app scope so it finishes.
     */
    private fun warmModelOnFirstPeer() {
        lifecycleScope.launch {
            meshManager.neighborCount
                .map { it > 0 }
                .distinctUntilChanged()
                .filter { it }
                .collect { scope.launch { textModel.warmUp() } }
        }
    }

    /** The ongoing notification for the state [pausedUntil] says: the live line while running, else the pause. */
    private fun buildNotification(
        count: Int,
        health: TransportHealth?,
    ): Notification = pausedUntil?.let { pausedNotification(it) } ?: runningNotification(count, health)

    private fun notificationBuilder(): NotificationCompat.Builder {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setOngoing(true)
            .setContentIntent(openApp)
    }

    private fun stopIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, MeshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * One token per offered span: `PendingIntent` identity ignores extras, so two of these on one request
     * code would be the same token and both buttons would pause for whichever was minted first.
     */
    private fun pauseIntent(
        requestCode: Int,
        minutes: Int,
    ): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, MeshService::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_PAUSE_MINUTES, minutes),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Pause first, Stop last: the shade caps a notification at three actions, so the 12 h span is not offered. */
    private fun runningNotification(
        count: Int,
        health: TransportHealth?,
    ): Notification =
        notificationBuilder()
            .setContentTitle(getString(R.string.mesh_notification_title))
            .setContentText(contentText(count, health))
            .addAction(0, getString(R.string.mesh_notification_pause_short), pauseIntent(REQUEST_PAUSE_SHORT, MeshPause.SHORT_MINUTES))
            .addAction(0, getString(R.string.mesh_notification_pause_long), pauseIntent(REQUEST_PAUSE_LONG, MeshPause.LONG_MINUTES))
            .addAction(0, getString(R.string.mesh_notification_stop), stopIntent())
            .build()

    private fun pausedNotification(until: Long): Notification =
        notificationBuilder()
            .setContentTitle(getString(R.string.mesh_notification_paused_title))
            .setContentText(getString(R.string.mesh_notification_paused_until, pausedUntilLabel(this, until, clock())))
            .addAction(0, getString(R.string.mesh_notification_resume), resumeIntent(REQUEST_RESUME_IDLE))
            .addAction(0, getString(R.string.mesh_notification_stop), stopIntent())
            .build()

    /**
     * The ongoing notification's status line — the non-Compose twin of the chat screens'
     * `connectionLabel`, sharing the same string resources so the shade and the in-app row stay in step.
     */
    private fun contentText(
        count: Int,
        health: TransportHealth?,
    ): CharSequence =
        when (health) {
            // Pre-graph seed from [startForeground] — the transport hasn't reported yet, so "searching" is the
            // only honest line (and it needs no Settings.Global read, unlike the Unavailable branch).
            null -> {
                getString(R.string.mesh_notification_searching)
            }

            TransportHealth.Unavailable -> {
                if (isAirplaneModeOn()) {
                    getString(R.string.chat_connection_airplane)
                } else {
                    getString(R.string.chat_connection_radio_off)
                }
            }

            TransportHealth.Degraded -> {
                getString(R.string.chat_connection_degraded)
            }

            // The one surface a user sees *while* the Wi-Fi search is refused off screen — and tapping it
            // opens the app, which is what lifts the refusal (see [meshForegroundServiceTypes]).
            TransportHealth.ForegroundOnly -> {
                getString(R.string.mesh_notification_foreground_only)
            }

            TransportHealth.Healthy -> {
                if (count == 0) {
                    getString(R.string.mesh_notification_searching)
                } else {
                    resources.getQuantityString(R.plurals.mesh_notification_connected, count, count)
                }
            }
        }

    private fun isAirplaneModeOn(): Boolean = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    /**
     * (Re-)post the foreground notification, reporting whether the foreground state is held. Calling
     * [ServiceCompat.startForeground] again with the same id is the supported way to update it, and — unlike
     * `NotificationManagerCompat.notify` — needs no `POST_NOTIFICATIONS` permission.
     *
     * **The first call can be refused.** Since Android 12 an app may only claim the foreground while it is
     * itself foreground or holds one of the listed exemptions, and *the system creating this service is not
     * one of them*: a `START_STICKY` restart after the process dies — to low memory, to an OEM app-sleep
     * sweep, or to our own last-resort wedge cure (`WifiAwareTransport.checkWedge`) — arrives in the
     * background with nothing to stand on and gets `ForegroundServiceStartNotAllowedException` (an
     * [IllegalStateException] subclass, so no `Build.VERSION` dance is needed to catch it). Thrown out of
     * `onCreate` that was a crash, field-observed on Android 15; declining instead costs the mesh only the
     * time until the app is next opened. See [canReclaimForegroundService] for the pre-check that keeps us
     * from *choosing* to land here.
     */
    private fun postForeground(notification: Notification): Boolean =
        try {
            // The runtime type is what the platform acts on (the manifest only bounds it) — see
            // [meshForegroundServiceTypes] for why `location` rides along on API 29-32.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, meshForegroundServiceTypes())
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "foreground start refused — mesh stays down until the app is next opened", e)
            false
        }

    companion object {
        private const val TAG = "MeshService"
        private const val CHANNEL_ID = NotificationChannels.STATUS
        private const val NOTIFICATION_ID = 1
        internal const val ACTION_STOP = "app.getknit.knit.STOP_MESH"
        internal const val ACTION_HEAL = "app.getknit.knit.HEAL_MESH"
        internal const val ACTION_PAUSE = "app.getknit.knit.PAUSE_MESH"
        internal const val ACTION_RESUME = "app.getknit.knit.RESUME_MESH"
        internal const val EXTRA_PAUSE_MINUTES = "minutes"

        // PendingIntent request codes: 0 is the open-app activity, 2 the heartbeat. Distinct on purpose —
        // the system tells tokens apart by code and filter, never by extras.
        private const val REQUEST_STOP = 1
        private const val REQUEST_PAUSE_SHORT = 3
        private const val REQUEST_PAUSE_LONG = 4
        private const val REQUEST_RESUME_IDLE = 5
        private const val REQUEST_RESUME_WINDOW = 6

        /** The windowed resume alarm's window: the floor the platform applies to a non-exact `setWindow` anyway. */
        private const val RESUME_WINDOW_MS = 10 * 60_000L

        /**
         * Ask the system to run the mesh in the foreground, reporting whether the request was **accepted**.
         *
         * Since Android 12 `Context.startForegroundService` itself throws
         * `ForegroundServiceStartNotAllowedException` — at *this* call site, before the service is ever
         * created, so [postForeground]'s catch is downstream of it and cannot see it — when the process is
         * neither foreground nor exempt. Both guards are needed and neither is redundant:
         * [canReclaimForegroundService] declines the starts we can predict will be refused, and the `catch`
         * closes the gap between that check and the binder call landing, which is exactly the window this
         * exists for (`KnitApp`'s route-keyed effect can be scheduled while foreground and land after a task
         * switch, a screen-off or an incoming call has taken it away). The exception is an
         * [IllegalStateException] subclass, so the catch needs no `Build.VERSION` dance on a minSdk-29 file —
         * the same reasoning as [postForeground].
         *
         * A refusal is **not** a dropped start: the caller records it (`MeshStartGate`) and `KnitApp`'s
         * `ON_RESUME` observer retries from a state where the foreground is guaranteed. Swallowing it
         * silently would leave a messenger with no transport and a "searching" notification that never
         * resolves. Work item #32; ADR 043. A start from the boot receiver goes through [startFromBoot]
         * instead — the pre-check reads process state, and a receiver's is one it would refuse.
         */
        fun start(context: Context): Boolean {
            if (!canReclaimForegroundService(context)) {
                Log.w(TAG, "mesh start refused (backgrounded, unexempted) — deferred to the next resume")
                return false
            }
            return request(context)
        }

        /**
         * [start] for `BootReceiver`, which skips [canReclaimForegroundService] on purpose.
         *
         * `ACTION_BOOT_COMPLETED` is a listed exemption to the Android 12+ background start restriction —
         * the system delivers it with the uid on the temporary foreground-service allowlist — but that is
         * the *platform's* exemption, honoured inside `startForegroundService`, and the pre-check cannot see
         * it. What the pre-check sees is process state: a process running a broadcast receiver is at
         * `PROCESS_STATE_RECEIVER`, which `RunningAppProcessInfo` reports as `IMPORTANCE_SERVICE`, well
         * above the `IMPORTANCE_FOREGROUND` bar. So on any phone without the battery exemption the pre-check
         * refused every boot start before it reached the system, and the mesh stayed down after a reboot
         * until Knit was next opened (work item #76). The call-site `catch` in [request] is the guard here:
         * the allowlist window is short (10 s from delivery), so a boot start that lands late is refused
         * like any other, reported rather than thrown. ADR 2026-09.29dw.
         */
        fun startFromBoot(context: Context): Boolean = request(context)

        /** The binder call itself, with the call-site guard both entry points share. */
        private fun request(context: Context): Boolean =
            try {
                ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
                true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "mesh start refused at the call site — deferred to the next resume", e)
                false
            }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MeshService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}

/**
 * The foreground-service type bitmask [MeshService.postForeground] claims, **tiered like the radio permissions**:
 * `connectedDevice` everywhere, plus `location` on exactly the API levels where [requiredRadioPermissions]
 * rides the location grant (29-32).
 *
 * Why the type, and why it has to be the *runtime* one. On those versions the Aware service checks the
 * location **app-op** on every `publish`/`subscribe` (`WifiAwareServiceImpl.enforceLocationPermission`), and
 * a "While using the app" grant is a foreground-only app-op — so a service that holds no location type loses
 * discovery the moment the activity leaves the screen, while `checkSelfPermission` still says granted (work
 * item #62: the Pixel 3 re-attached 45 times in four minutes off screen, blind throughout). What lifts the
 * app-op for a backgrounded uid is the `PROCESS_CAPABILITY_FOREGROUND_LOCATION` capability, which
 * `OomAdjuster` derives on every pass from the **live** `ServiceRecord.foregroundServiceType` — the bits
 * passed to `startForeground`, never the manifest attribute alone. Passing `0` here, as the pre-34 branch used
 * to, left the service typeless on the very versions that needed the bit. On 29 that is the whole story
 * (`PROCESS_STATE_FOREGROUND_SERVICE_LOCATION`); on 30-32 the capability also needs
 * `mAllowWhileInUsePermissionInFgs`, which the system grants only to a start from a TOP / visible uid —
 * neither the battery allowlist nor `BOOT_COMPLETED` counts (`ActiveServices.shouldAllowFgsWhileInUsePermissionLocked`,
 * android12-release). A boot- or sticky-restarted service therefore still discovers only on screen until
 * Knit is next opened: `KnitApp`'s `ON_RESUME` observer starts the service from a visible activity, the flag
 * is re-evaluated on every start while false, and [MeshService.onStartCommand] re-posts the state with the
 * type (ADR 2026-09.f69x). `WifiAwareTransport` names that residual as `TransportHealth.ForegroundOnly`
 * rather than churning the session. ADR 2026-09.535d.
 *
 * Why it stops at 32: from 33 discovery rides `NEARBY_WIFI_DEVICES` with `neverForLocation` and needs no
 * location at all, and from 34 a `location` type at runtime requires the `FOREGROUND_SERVICE_LOCATION`
 * permission the manifest deliberately does not declare. Tied to [requiredRadioPermissions] rather than to a
 * second `SDK_INT` ladder so the two tiers cannot drift; `MeshForegroundServiceTypesTest` pins the mapping.
 * `ServiceCompat` masks the bits to `FOREGROUND_SERVICE_TYPE_ALLOWED_SINCE_Q` on 29-33, which both are.
 */
fun meshForegroundServiceTypes(sdkInt: Int = Build.VERSION.SDK_INT): Int {
    val location =
        if (Manifest.permission.ACCESS_FINE_LOCATION in requiredRadioPermissions(sdkInt)) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
    return ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or location
}

/**
 * Whether the system would let us claim the mesh foreground service *right now* — the precondition for any
 * deliberate end to this process that expects `START_STICKY` to bring the service back.
 *
 * Since Android 12 a backgrounded app can only start a foreground service under a listed exemption, and a
 * system-initiated sticky restart is not one of them. A process that dies while backgrounded and unexempted
 * therefore comes back to a refused [MeshService.postForeground] and no mesh at all. Two of the exemptions
 * are ours to check cheaply, and they are the two a Knit user can actually be in:
 *
 * - **A visible activity.** `IMPORTANCE_FOREGROUND` really does mean the UI is up — the service on its own
 *   only ever reaches the weaker `IMPORTANCE_FOREGROUND_SERVICE`, so this can't self-satisfy.
 * - **The battery-optimization exemption**, offered on the onboarding permission screen. Opt-in, so most
 *   installs won't have it; that is exactly why the background case has to be handled rather than assumed.
 *
 * What it cannot read is an exemption that rides the *event*, not the process: `BOOT_COMPLETED` puts the uid
 * on a temporary allowlist for the delivery, while the receiver's process reads as `IMPORTANCE_SERVICE`. A
 * caller that holds such an exemption must not ask this — see [MeshService.startFromBoot] (work item #76).
 *
 * A top-level function (like [shouldStartMeshOnBoot]) so the transports can consult it without depending on
 * the service class.
 */
fun canReclaimForegroundService(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    if (isIgnoringBatteryOptimizations(context)) return true
    val state = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(state)
    return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}
