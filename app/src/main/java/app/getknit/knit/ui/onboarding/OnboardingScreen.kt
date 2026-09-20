package app.getknit.knit.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.mesh.RadioSupport
import app.getknit.knit.ui.MeshPermissionTier
import app.getknit.knit.ui.meshPermissionTier
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.theme.KnitMotion
import org.koin.androidx.compose.koinViewModel

/**
 * First-run gate, three pages: what Knit is ([WelcomePage]), what to call you ([NamePage], optional), and
 * the grants it needs ([PermissionsPage]). Start is enabled once the **radio** permissions are held — the
 * transports assume them — while notifications and the battery exemption are offered on the same page and
 * never block. Only on hardware with **neither** Wi-Fi Aware nor Bluetooth LE does it show an "unsupported"
 * notice (but still lets the user in, to read what they have).
 *
 * A phone that has seen the intro once and comes back with a grant revoked opens on the permissions page
 * ([app.getknit.knit.data.settings.SettingsStore.onboardingSeen]). Whether onboarding shows at all is
 * `KnitApp`'s decision, on `hasRadioPermissions` alone.
 *
 * This stateful wrapper owns the Android-only pieces — the hardware probe, and the permission launchers and
 * probes via [rememberOnboardingPermissions] — plus the [OnboardingViewModel] for the step and the name;
 * [OnboardingScreenContent] is the previewable, testable layout.
 */
@Composable
fun OnboardingScreen(
    onReady: () -> Unit,
    onRestore: () -> Unit = {},
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    // The mesh runs on either radio plane, so a device with Wi-Fi Aware OR Bluetooth LE can participate — the
    // same verdict the composite builds its children from, so this never says "supported" for a plane it skips.
    val meshSupported = remember { RadioSupport.probe(context).any }
    val tier = remember { meshPermissionTier() }
    val permissions = rememberOnboardingPermissions()
    // Null until the store has answered whether this phone has seen the intro; one blank frame at most.
    val step by viewModel.step.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    val alias by viewModel.alias.collectAsStateWithLifecycle()
    val nodeId by viewModel.nodeId.collectAsStateWithLifecycle()
    val current = step ?: return

    OnboardingScreenContent(
        step = current,
        name = name,
        alias = alias,
        nodeId = nodeId,
        rows = permissions.rows,
        tier = tier,
        meshSupported = meshSupported,
        onNext = viewModel::next,
        onBack = viewModel::back,
        onNameChange = viewModel::setName,
        onNameCommit = viewModel::commitName,
        onRequestRadio = permissions.requestRadio,
        onRequestNotifications = permissions.requestNotifications,
        onAllowBattery = permissions.requestBattery,
        onOpenUnusedPauseSettings = permissions.openUnusedPauseSettings,
        onOpenSettings = permissions.openSettings,
        onReady = onReady,
        onRestore = onRestore,
    )
}

/**
 * The stepper: the current page under a fixed footer (step dots + the one CTA). `AnimatedContent` rather
 * than a pager on purpose — leaving the name page has a side effect (the name is persisted), which a button
 * expresses and a swipe would not, and only the current page is composed, so a test that looks for
 * `onboarding_start` cannot find it lurking off-screen. System Back steps back; on the welcome page it
 * leaves the app, as it always has.
 */
@Composable
internal fun OnboardingScreenContent(
    step: OnboardingStep,
    name: String,
    alias: String,
    nodeId: String,
    rows: PermissionRows,
    tier: MeshPermissionTier,
    meshSupported: Boolean,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onNameCommit: () -> Unit,
    onRequestRadio: () -> Unit,
    onRequestNotifications: () -> Unit,
    onAllowBattery: () -> Unit,
    onOpenSettings: () -> Unit,
    onReady: () -> Unit,
    onOpenUnusedPauseSettings: () -> Unit = {},
    onRestore: () -> Unit = {},
) {
    BackHandler(enabled = step != OnboardingStep.WELCOME, onBack = onBack)
    val enterForward = KnitMotion.enterStep(forward = true)
    val exitForward = KnitMotion.exitStep(forward = true)
    val enterBack = KnitMotion.enterStep(forward = false)
    val exitBack = KnitMotion.exitStep(forward = false)
    Scaffold(
        modifier = Modifier.testTag("screen_onboarding"),
        bottomBar = {
            OnboardingFooter(
                step = step,
                nameBlank = name.isBlank(),
                startEnabled = rows.radioGranted,
                onNext = onNext,
                onReady = onReady,
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                // Every page fills the box, so there is no size to animate; say so rather than let the
                // default SizeTransform run a spring the reduce-motion gate never sees.
                if (targetState.ordinal > initialState.ordinal) {
                    (enterForward togetherWith exitForward).using(null)
                } else {
                    (enterBack togetherWith exitBack).using(null)
                }
            },
            label = "onboardingStep",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { current ->
            when (current) {
                OnboardingStep.WELCOME -> {
                    WelcomePage(onRestore = onRestore)
                }

                OnboardingStep.NAME -> {
                    NamePage(
                        name = name,
                        alias = alias,
                        nodeId = nodeId,
                        onNameChange = onNameChange,
                        onNameCommit = onNameCommit,
                        onDone = onNext,
                    )
                }

                OnboardingStep.PERMISSIONS -> {
                    PermissionsPage(
                        rows = rows,
                        tier = tier,
                        meshSupported = meshSupported,
                        onRequestRadio = onRequestRadio,
                        onRequestNotifications = onRequestNotifications,
                        onAllowBattery = onAllowBattery,
                        onOpenSettings = onOpenSettings,
                        onOpenUnusedPauseSettings = onOpenUnusedPauseSettings,
                    )
                }
            }
        }
    }
}

/**
 * Dots and the CTA. One button per page: "Get started", then "Skip for now" that becomes "Continue" the
 * moment something is typed (what's in the field is what gets saved — there is no second path), then
 * "Start meshing". Rides above the keyboard on the name page.
 */
@Composable
private fun OnboardingFooter(
    step: OnboardingStep,
    nameBlank: Boolean,
    startEnabled: Boolean,
    onNext: () -> Unit,
    onReady: () -> Unit,
) {
    val focus = LocalFocusManager.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StepDots(step = step, modifier = Modifier.padding(bottom = 16.dp))
        when (step) {
            OnboardingStep.WELCOME -> {
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth().testTag("onboarding_next")) {
                    Text(stringResource(R.string.onboarding_get_started))
                }
            }

            OnboardingStep.NAME -> {
                Button(
                    onClick = {
                        focus.clearFocus()
                        onNext()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("onboarding_next"),
                ) {
                    Text(stringResource(if (nameBlank) R.string.onboarding_name_skip else R.string.onboarding_continue))
                }
            }

            OnboardingStep.PERMISSIONS -> {
                Button(
                    onClick = onReady,
                    enabled = startEnabled,
                    modifier = Modifier.fillMaxWidth().testTag("onboarding_start"),
                ) {
                    Text(stringResource(R.string.onboarding_start))
                }
            }
        }
    }
}

/**
 * Three dots, the current one stretched into a pill. Material 3 has no page-indicator component, so this is
 * the smallest correct one: a single semantics node reading "Step n of 3", nothing focusable.
 */
@Composable
private fun StepDots(
    step: OnboardingStep,
    modifier: Modifier = Modifier,
) {
    val steps = OnboardingStep.entries
    val label = stringResource(R.string.onboarding_step_of, step.ordinal + 1, steps.size)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEach { candidate ->
            val active = candidate == step
            val width by animateDpAsState(
                targetValue = if (active) ACTIVE_DOT_WIDTH else DOT_SIZE,
                animationSpec = KnitMotion.spatial(),
                label = "stepDot",
            )
            Box(
                modifier =
                    Modifier
                        .width(width)
                        .height(DOT_SIZE)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
            )
        }
    }
}

private val DOT_SIZE = 8.dp
private val ACTIVE_DOT_WIDTH = 20.dp

@Preview(showBackground = true)
@Composable
fun OnboardingWelcomePreview() =
    KnitPreview {
        OnboardingScreenContent(
            step = OnboardingStep.WELCOME,
            name = "",
            alias = "SmartlyBrightSparrow",
            nodeId = "node-preview",
            rows = PermissionRows.FRESH,
            tier = MeshPermissionTier.NEARBY_DEVICES,
            meshSupported = true,
            onNext = {},
            onBack = {},
            onNameChange = {},
            onNameCommit = {},
            onRequestRadio = {},
            onRequestNotifications = {},
            onAllowBattery = {},
            onOpenSettings = {},
            onReady = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun OnboardingNamePreview() =
    KnitPreview {
        OnboardingScreenContent(
            step = OnboardingStep.NAME,
            name = "",
            alias = "SmartlyBrightSparrow",
            nodeId = "node-preview",
            rows = PermissionRows.FRESH,
            tier = MeshPermissionTier.NEARBY_DEVICES,
            meshSupported = true,
            onNext = {},
            onBack = {},
            onNameChange = {},
            onNameCommit = {},
            onRequestRadio = {},
            onRequestNotifications = {},
            onAllowBattery = {},
            onOpenSettings = {},
            onReady = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun OnboardingPermissionsPreview() =
    KnitPreview {
        OnboardingScreenContent(
            step = OnboardingStep.PERMISSIONS,
            name = "Sam Rivera",
            alias = "SmartlyBrightSparrow",
            nodeId = "node-preview",
            rows = PermissionRows.FRESH,
            tier = MeshPermissionTier.NEARBY_DEVICES,
            meshSupported = true,
            onNext = {},
            onBack = {},
            onNameChange = {},
            onNameCommit = {},
            onRequestRadio = {},
            onRequestNotifications = {},
            onAllowBattery = {},
            onOpenSettings = {},
            onReady = {},
        )
    }
