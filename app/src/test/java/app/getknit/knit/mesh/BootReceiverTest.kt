package app.getknit.knit.mesh

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.ui.requiredMeshPermissions
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The boot-restart decision ([shouldStartMeshOnBoot]): the mesh is restored after a reboot only when it was
 * left enabled **and** the mesh permissions are still granted. Robolectric hosts it for the real permission
 * check ([hasAllMeshPermissions] over [requiredMeshPermissions], location-free at the emulated SDK 36); the
 * enabled flag is stubbed rather than round-tripped through a DataStore (that round-trip is covered by
 * SettingsStoreTest). `SEED_DEMO` is false in the debug unit-test variant, so the demo short-circuit isn't
 * exercised here.
 */
@RunWith(AndroidJUnit4::class)
class BootReceiverTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun store(enabled: Boolean): SettingsStore {
        val settings = mockk<SettingsStore>()
        every { settings.meshEnabled } returns flowOf(enabled)
        return settings
    }

    private fun grantMeshPermissions() = shadowOf(app).grantPermissions(*requiredMeshPermissions())

    @Test
    fun `starts when enabled and permitted`() =
        runTest {
            grantMeshPermissions()
            assertTrue(shouldStartMeshOnBoot(app, store(enabled = true)))
        }

    @Test
    fun `does not start when the user disabled the mesh`() =
        runTest {
            grantMeshPermissions()
            assertFalse(shouldStartMeshOnBoot(app, store(enabled = false)))
        }

    @Test
    fun `does not start when mesh permissions are missing`() =
        runTest {
            // No grant — Robolectric denies runtime permissions by default, so the mesh must stay down.
            assertFalse(shouldStartMeshOnBoot(app, store(enabled = true)))
        }
}
