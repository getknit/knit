@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain are experimental kotlinx APIs

package app.getknit.knit.ui.onboarding

import app.getknit.knit.TextLimits
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.moderation.NameScreen
import app.getknit.knit.moderation.ShownNames
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The stepper's contract: pages advance in order and stop at the ends; leaving the name page is the one
 * moment the name is written (normalized, capped, and only when it changed) and the intro is marked seen;
 * a phone that has seen the intro opens on the permissions page. Pure JVM, mockk stand-ins for the store
 * and identity — the same rig as `ProfileViewModelTest`.
 */
class OnboardingViewModelTest {
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)
    private val seenFlow = MutableStateFlow(false)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "node-abc"
        every { settings.onboardingSeen } returns seenFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = OnboardingViewModel(settings, identity)

    @Test
    fun stepsAdvanceInOrderAndStopAtTheEnds() =
        runTest {
            val vm = vm()
            assertEquals(OnboardingStep.WELCOME, vm.step.value)
            vm.back()
            assertEquals(OnboardingStep.WELCOME, vm.step.value)
            vm.next()
            assertEquals(OnboardingStep.NAME, vm.step.value)
            vm.next()
            assertEquals(OnboardingStep.PERMISSIONS, vm.step.value)
            vm.next()
            assertEquals(OnboardingStep.PERMISSIONS, vm.step.value)
            vm.back()
            assertEquals(OnboardingStep.NAME, vm.step.value)
        }

    @Test
    fun aliasComesFromTheNodeId() =
        runTest {
            assertEquals(Alias.aliasFor("node-abc"), vm().alias.value)
        }

    @Test
    fun aReturningPhoneOpensOnThePermissionsPage() =
        runTest {
            seenFlow.value = true
            assertEquals(OnboardingStep.PERMISSIONS, vm().step.value)
        }

    @Test
    fun leavingTheNamePageNormalizesCapsAndWritesTheNameOnce() =
        runTest {
            val vm = vm()
            vm.next()
            vm.setName("  Ada   Lovelace  ")
            vm.next()
            assertEquals("Ada Lovelace", vm.name.value)
            coVerify(exactly = 1) { settings.setDisplayName("Ada Lovelace") }
            coVerify(exactly = 1) { settings.markOnboardingSeen() }

            // Back and Continue again with nothing changed: no second write.
            vm.back()
            vm.next()
            coVerify(exactly = 1) { settings.setDisplayName(any()) }
        }

    @Test
    fun setNameCapsAtTheDisplayNameLimit() =
        runTest {
            val vm = vm()
            vm.setName("x".repeat(TextLimits.DISPLAY_NAME + 10))
            assertEquals(TextLimits.DISPLAY_NAME, vm.name.value.length)
        }

    @Test
    fun aBlankNameWritesNothingButStillMarksSeen() =
        runTest {
            val vm = vm()
            vm.next()
            vm.setName("   ")
            vm.next()
            coVerify(exactly = 0) { settings.setDisplayName(any()) }
            coVerify(exactly = 1) { settings.markOnboardingSeen() }
        }

    @Test
    fun clearingANameWrittenAPageAgoWritesTheClear() =
        runTest {
            val vm = vm()
            vm.next()
            vm.setName("Ada")
            vm.next()
            vm.back()
            vm.setName("")
            vm.next()
            coVerify(exactly = 1) { settings.setDisplayName("Ada") }
            coVerify(exactly = 1) { settings.setDisplayName("") }
        }

    @Test
    fun commitSnapsTheFieldWithoutWriting() =
        runTest {
            val vm = vm()
            vm.setName(" Ada  B ")
            vm.commitName()
            assertEquals("Ada B", vm.name.value)
            coVerify(exactly = 0) { settings.setDisplayName(any()) }
        }

    @Test
    fun stepIsUnknownUntilTheStoreAnswers() =
        runTest {
            // A store that hasn't answered yet: the pager must not be built on a guess.
            every { settings.onboardingSeen } returns flow { awaitCancellation() }
            assertEquals(null, vm().step.value)
        }

    /** ADR 2026-11.y4pc: Continue refuses a flagged name, stays on the page and says why until the next keystroke. */
    @Test
    fun aFlaggedNameKeepsThePageAndIsNeverWritten() =
        runTest {
            val vm =
                OnboardingViewModel(
                    settings,
                    identity,
                    names = ShownNames(NameScreen(listOf("kike"), listOf("fuck", "kike"), emptyList(), emptyList(), emptyList())),
                )
            vm.next()
            vm.setName("Fuck69")
            vm.next()
            assertEquals(OnboardingStep.NAME, vm.step.value)
            assertTrue(vm.nameRefused.value)
            coVerify(exactly = 0) { settings.setDisplayName(any()) }

            vm.setName("Ada")
            assertFalse("a keystroke clears the refusal", vm.nameRefused.value)
            vm.next()
            assertEquals(OnboardingStep.PERMISSIONS, vm.step.value)
            coVerify(exactly = 1) { settings.setDisplayName("Ada") }
        }
}
