package app.getknit.knit.ui

import app.getknit.knit.BuildConfig

/**
 * Whether the UI should start the mesh service right now — `KnitApp`'s route effect and its `ON_RESUME`
 * observer both ask this, so the two starters cannot disagree. Past onboarding ([pastOnboarding]: a route
 * that is not the onboarding one), and only while the user has not stopped the mesh: [meshEnabled] is
 * `SettingsStore.meshEnabled`, the switch the notification's Stop flips off, **read fresh from the store at
 * the moment of the decision**. A lifecycle-collected copy is stale exactly when it matters: it stops
 * collecting while the activity is off screen, which is where the Stop tap lands, so the resume that follows
 * would read the old `true` and start the service — which writes the flag back to true as it comes up
 * (device-observed on the Pixel 3, 2026-09-20). Demo builds never start it: there is no real mesh and the
 * seeded data needs no transport. ADR 2026-09.wz99.
 */
fun shouldStartMeshFromUi(
    pastOnboarding: Boolean,
    meshEnabled: Boolean,
    seedDemo: Boolean = BuildConfig.SEED_DEMO,
): Boolean = !seedDemo && pastOnboarding && meshEnabled
