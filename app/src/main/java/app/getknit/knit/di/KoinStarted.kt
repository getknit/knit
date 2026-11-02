package app.getknit.knit.di

import org.koin.core.context.GlobalContext

/**
 * Whether this process has the app's Koin graph — i.e. whether `KnitApplication.onCreate` ran here.
 *
 * It does not always. Android brings an app up for full backup / restore in *restricted backup mode*
 * (`ActivityThread.handleBindApplication` → `makeApplicationInner(restrictedBackupMode = true)`): the base
 * `android.app.Application` is instantiated instead of ours and no ContentProvider is installed, so
 * `startKoin` never happens. That process is still a live record for our uid, and
 * `ActiveServices.bringUpServiceLocked` (likewise the broadcast queue) hands a start to whichever process is
 * alive — so the revive alarm, a `START_STICKY` restart or a notification action can land in it. A
 * component created there must **decline**, not resolve: its first `by inject()` reads this same global and
 * throws `IllegalStateException("KoinApplication has not been started")` — a Play-reported crash on 2.5.0,
 * Android 15, in the background. The process is killed by the backup manager when the pass ends, and the
 * next ordinary start lands in a normal process.
 *
 * Reads the context Koin's own `inject()` resolves from, so the two cannot disagree.
 */
fun isKoinStarted(): Boolean = GlobalContext.getOrNull() != null
