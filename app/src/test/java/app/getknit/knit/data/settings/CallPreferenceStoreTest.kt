package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import app.getknit.knit.call.VoxSensitivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * C-3.11-3: the chat picks its first call by the route until the person prefers mesh calls. C-3.11-11: hands-free opens
 * at Medium sensitivity until the person picks another.
 */
class CallPreferenceStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val counter = AtomicInteger()

    private fun TestScope.newStore(): CallPreferenceStore = CallPreferenceStore(newDataStore())

    private fun TestScope.newDataStore() =
        File(tmp.root, "settings-${counter.incrementAndGet()}.preferences_pb").let { file ->
            PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        }

    @Test
    fun meshCallsAreNotPreferredByDefault() =
        runTest {
            assertFalse(newStore().preferMesh.first())
        }

    @Test
    fun theSwitchRoundTrips() =
        runTest {
            val store = newStore()
            store.setPreferMesh(true)
            assertTrue(store.preferMesh.first())
            store.setPreferMesh(false)
            assertFalse(store.preferMesh.first())
        }

    @Test
    fun handsFreeOpensAtMediumSensitivityByDefault() =
        runTest {
            assertEquals(VoxSensitivity.MEDIUM, newStore().voxSensitivity.first())
        }

    @Test
    fun theSensitivityRoundTrips() =
        runTest {
            val store = newStore()
            for (pick in VoxSensitivity.entries) {
                store.setVoxSensitivity(pick)
                assertEquals(pick, store.voxSensitivity.first())
            }
        }

    @Test
    fun aSensitivityThisBuildDoesNotKnowReadsAsTheDefault() =
        runTest {
            val data = newDataStore()
            data.edit { it[CallPreferenceStore.KEY_VOX_SENSITIVITY] = "LOUDEST" }
            assertEquals(VoxSensitivity.DEFAULT, CallPreferenceStore(data).voxSensitivity.first())
        }
}
