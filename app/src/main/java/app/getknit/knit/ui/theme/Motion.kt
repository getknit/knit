package app.getknit.knit.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * Whether the platform's **Remove animations** accessibility setting is on, for the subtree below
 * [KnitTheme]. Every spec and transition in [KnitMotion] reads it and collapses to an instant change, so a
 * call site never has to ask — it names the motion it wants and gets nothing when the user asked for nothing.
 *
 * `static` on purpose: it changes about once in the life of a process, and a static local trades cheap reads
 * for an expensive (but effectively never taken) subtree recomposition.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Knit's motion vocabulary — the single place that decides *how* the app moves, so call sites choose a
 * meaning ("this pops in") rather than a duration and an easing curve.
 *
 * The specs are springs, not hand-tuned `tween`s, and they keep Material 3's split between **spatial**
 * motion (something moves or resizes — underdamped, with a little follow-through) and **effects** (something
 * fades or recolours in place — critically damped, because a colour that overshoots is a different colour).
 * Borrowing that split keeps the whole app coherent for free.
 *
 * The damping and stiffness values below are M3's own *standard* motion tokens, copied rather than read from
 * `MaterialTheme.motionScheme`: that property and the `MotionScheme` type are still `internal` in Material3
 * 1.4.0 (the JVM symbols are public, but the Kotlin metadata is not), so they cannot be referenced from here
 * yet. They are the standard scheme, not the expressive one, which is louder than this app wants. When
 * `MotionScheme` goes public, these four functions are the only place that has to change.
 *
 * **Every animation in the app is finite.** An infinite transition composed on a settled screen never lets
 * Compose go idle, which times out `waitForIdle`/`waitUntil` in both the Robolectric screen tests and the
 * seeded instrumentation suite. The two that exist (the typing indicator's sweep and the chat-list skeleton's
 * pulse) are only ever composed transiently. See ADR 047.
 */
object KnitMotion {
    /** Something moves or changes size. */
    @Composable
    fun <T> spatial(): FiniteAnimationSpec<T> =
        if (LocalReduceMotion.current) snap() else spring(dampingRatio = SPATIAL_DAMPING, stiffness = SPATIAL_DEFAULT_STIFFNESS)

    /** Something small moves — a glyph, a badge — where the default spec would read as sluggish. */
    @Composable
    fun <T> fastSpatial(): FiniteAnimationSpec<T> =
        if (LocalReduceMotion.current) snap() else spring(dampingRatio = SPATIAL_DAMPING, stiffness = SPATIAL_FAST_STIFFNESS)

    /** Something fades or recolours in place. */
    @Composable
    fun <T> effects(): FiniteAnimationSpec<T> = effectsFor(LocalReduceMotion.current)

    /**
     * [effects] for a caller outside composition — a `Modifier.Node` animating on its own coroutine — which
     * reads [LocalReduceMotion] itself (`currentValueOf`) and passes it in.
     */
    fun <T> effectsFor(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else spring(dampingRatio = EFFECTS_DAMPING, stiffness = EFFECTS_DEFAULT_STIFFNESS)

    /** A fade that must not be noticed — icon swaps, press states. */
    @Composable
    fun <T> fastEffects(): FiniteAnimationSpec<T> =
        if (LocalReduceMotion.current) snap() else spring(dampingRatio = EFFECTS_DAMPING, stiffness = EFFECTS_FAST_STIFFNESS)

    /** Plain arrival: content that is simply there now. */
    @Composable
    fun enterFade(): EnterTransition = if (LocalReduceMotion.current) EnterTransition.None else fadeIn(animationSpec = effects())

    /** The counterpart to [enterFade]. */
    @Composable
    fun exitFade(): ExitTransition = if (LocalReduceMotion.current) ExitTransition.None else fadeOut(animationSpec = effects())

    /**
     * A small thing appearing where there was nothing: a delivery tick, a plane glyph, an unread badge. The
     * scale starts near 1 deliberately — a glyph that grows from zero is a celebration, and none of these
     * are.
     */
    @Composable
    fun enterPop(): EnterTransition =
        if (LocalReduceMotion.current) {
            EnterTransition.None
        } else {
            fadeIn(animationSpec = fastEffects()) + scaleIn(animationSpec = fastSpatial(), initialScale = POP_SCALE)
        }

    /** The counterpart to [enterPop]. */
    @Composable
    fun exitPop(): ExitTransition =
        if (LocalReduceMotion.current) {
            ExitTransition.None
        } else {
            fadeOut(animationSpec = fastEffects()) + scaleOut(animationSpec = fastSpatial(), targetScale = POP_SCALE)
        }

    /**
     * A band claiming vertical space it did not have: the radio-warning banner, the pinned relay/LoRa
     * notices. It opens rather than shoving the content below it down in a single frame.
     */
    @Composable
    fun enterReveal(): EnterTransition =
        if (LocalReduceMotion.current) {
            EnterTransition.None
        } else {
            expandVertically(animationSpec = spatial()) + fadeIn(animationSpec = effects())
        }

    /** The counterpart to [enterReveal]. */
    @Composable
    fun exitReveal(): ExitTransition =
        if (LocalReduceMotion.current) {
            ExitTransition.None
        } else {
            shrinkVertically(animationSpec = spatial()) + fadeOut(animationSpec = effects())
        }

    /**
     * A peer arriving: a fade carrying a small horizontal offset — a twenty-fourth of the width, not a
     * page slide — from the end when going [forward], from the start when coming back. It reads as depth
     * rather than travel, which is what keeps it from tiring on a screen opened dozens of times a day. The
     * onboarding stepper's page change; the same recipe `KnitApp`'s NavHost uses between routes.
     */
    @Composable
    fun enterStep(forward: Boolean): EnterTransition =
        if (LocalReduceMotion.current) {
            EnterTransition.None
        } else {
            fadeIn(animationSpec = effects()) +
                slideInHorizontally(animationSpec = spatial()) { (if (forward) it else -it) / STEP_SLIDE_DIVISOR }
        }

    /** The counterpart to [enterStep]: the departing peer drifts the other way. */
    @Composable
    fun exitStep(forward: Boolean): ExitTransition =
        if (LocalReduceMotion.current) {
            ExitTransition.None
        } else {
            fadeOut(animationSpec = effects()) +
                slideOutHorizontally(animationSpec = spatial()) { (if (forward) -it else it) / STEP_SLIDE_DIVISOR }
        }
}

// M3's standard motion tokens (androidx.compose.material3.tokens.StandardMotionTokens). Spatial motion is
// slightly underdamped so a moving thing settles with a hint of follow-through; effects are critically damped
// (ratio 1) so a fade or a colour change never overshoots into a value that was never asked for.
private const val SPATIAL_DAMPING = 0.9f
private const val SPATIAL_DEFAULT_STIFFNESS = 700f
private const val SPATIAL_FAST_STIFFNESS = 1400f
private const val EFFECTS_DAMPING = 1f
private const val EFFECTS_DEFAULT_STIFFNESS = 1600f
private const val EFFECTS_FAST_STIFFNESS = 3800f

// A step slides a twenty-fourth of the width: enough to give the fade a direction, too little to read as travel.
private const val STEP_SLIDE_DIVISOR = 24

/**
 * The scale a control should draw at while [interactionSource] reports it pressed — 1 at rest, a shade under
 * it while held. Returns the factor rather than a `Modifier` so the caller applies it through
 * `graphicsLayer`, which scales the *drawing* only: the touch target keeps its declared size, and the 48dp
 * minimum the ATF audit checks for is never quietly shrunk by a press.
 *
 * A [State] rather than a bare `Float` so the caller reads it **inside** the `graphicsLayer` lambda. That
 * defers the read to the draw phase, so a press repaints instead of recomposing — which matters because the
 * biggest user of this is an avatar, and avatars come in lists.
 *
 * This is deliberately not a ripple replacement. Ripple still reads as "the tap registered"; this is the
 * physical half — the control giving under the finger — and the two are meant to be seen together.
 */
@Composable
fun rememberPressScale(interactionSource: InteractionSource): State<Float> {
    val pressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = KnitMotion.fastSpatial(),
        label = "pressScale",
    )
}

// How far a pressed control gives. Small on purpose: enough to feel pressed, not enough to read as a bounce.
private const val PRESS_SCALE = 0.92f

// Near 1 on purpose: enough to read as "this arrived", not enough to read as a flourish.
private const val POP_SCALE = 0.85f

/**
 * Reads the platform animation scale and tracks it live.
 *
 * `ANIMATOR_DURATION_SCALE` is what Android's accessibility **Remove animations** setting zeroes, so honoring
 * it needs no setting of our own — the user has already told the system once, for every app. It is read the
 * same permission-free way [app.getknit.knit.ui.components.ConnectionStatusRow] reads airplane mode, but
 * observed rather than sampled: toggling the accessibility setting does not recreate the activity, so a
 * composition-time read alone would not take effect until the process restarted.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    // Android Studio's preview renderer has no real ContentResolver behind LocalContext, and every @Preview
    // in the app now runs through KnitTheme. Previews show motion as it ships; they are not where anyone
    // checks the accessibility path.
    if (LocalInspectionMode.current) return false
    val resolver = LocalContext.current.contentResolver
    var reduced by remember(resolver) { mutableStateOf(reduceMotionFor(animatorScale(resolver))) }
    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    reduced = reduceMotionFor(animatorScale(resolver))
                }
            }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/**
 * Whether [animatorScale] means "no animations". Split out as a pure function so the rule is testable on the
 * JVM without a device. A scale of 0 disables animators outright; anything above it merely speeds them up or
 * slows them down, which is a preference about pace rather than a request for stillness.
 */
internal fun reduceMotionFor(animatorScale: Float): Boolean = animatorScale <= 0f

/** The current global animator duration scale, or 1 (the platform default) if it has never been set. */
private fun animatorScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
