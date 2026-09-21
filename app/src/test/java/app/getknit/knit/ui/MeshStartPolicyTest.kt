package app.getknit.knit.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one rule both of `KnitApp`'s starters ask (ADR 2026-09.wz99): a Stop from the notification is sticky. */
class MeshStartPolicyTest {
    @Test
    fun `starts only past onboarding with the flag on`() {
        assertTrue(shouldStartMeshFromUi(pastOnboarding = true, meshEnabled = true, seedDemo = false))
        assertFalse(shouldStartMeshFromUi(pastOnboarding = false, meshEnabled = true, seedDemo = false))
    }

    @Test
    fun `a stopped mesh is not started by an open`() {
        assertFalse(shouldStartMeshFromUi(pastOnboarding = true, meshEnabled = false, seedDemo = false))
    }

    @Test
    fun `a demo build never starts the mesh`() {
        assertFalse(shouldStartMeshFromUi(pastOnboarding = true, meshEnabled = true, seedDemo = true))
    }
}
