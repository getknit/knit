package app.getknit.knit.di

import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.MeshtasticLink
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Test
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * The radio-reached edge is a constructor lambda of the LoRa transport that only `di/MeshModule` binds to the manager
 * (ADR 040's 2026-10-31 amendment). Every transport test builds its own, so a module that forgot it would leave the
 * chat's middle state dead on the phone with every other suite green: this builds the production definition and calls
 * the lambda it was handed.
 */
class LoraRadioReachedWiringTest {
    private val manager = mockk<MeshManager>(relaxed = true)
    private val scope = CoroutineScope(Job())
    private val app: KoinApplication =
        koinApplication {
            allowOverride(true)
            modules(
                meshModule,
                module {
                    single<MeshtasticLink> { mockk(relaxed = true) }
                    single<CoroutineScope> { scope }
                    single { mockk<SettingsStore>(relaxed = true) }
                    single { mockk<Identity>(relaxed = true) }
                    single { mockk<IdentityKeyStore>(relaxed = true) }
                    single { manager }
                },
            )
        }

    @After
    fun tearDown() {
        app.close()
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun theTransportsRadioReachedEdgeReachesTheManager() {
        val transport = app.koin.get<LoraMeshTransport>()
        val field = LoraMeshTransport::class.java.getDeclaredField("onRadioReached").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val edge = field.get(transport) as (String, Boolean) -> Unit
        edge("row-1", true)

        verify(exactly = 1) { manager.onLoraRadioReached("row-1", true) }
    }
}
