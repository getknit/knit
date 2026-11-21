package app.getknit.knit.ui.camera

import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import java.util.concurrent.TimeUnit

/** The phone's camera bound to use cases outside any screen, for the CameraX suites. */
object CameraFixtures {
    /**
     * Runs [block] with [useCases] bound to the back camera (the front one on a phone without one) on a lifecycle held
     * resumed, and unbinds them after, whatever [block] does.
     */
    fun <T> bound(
        context: Context,
        vararg useCases: UseCase,
        block: () -> T,
    ): T {
        // FEATURE_CAMERA_ANY is what the app's own gate asks; every emulator and phone the suites run on has one, so a
        // device that reports none fails here rather than skipping.
        assertTrue("a camera", context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        val provider = ProcessCameraProvider.getInstance(context).get(PROVIDER_S, TimeUnit.SECONDS)
        val owner = onMain { ResumedOwner() }
        onMain {
            val selector =
                if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
            provider.bindToLifecycle(owner, selector, *useCases)
        }
        return try {
            block()
        } finally {
            onMain {
                provider.unbindAll()
                owner.destroy()
            }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    /** A lifecycle at RESUMED from its creation until [destroy]; made and moved on the main thread, as the registry asks. */
    private class ResumedOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private const val PROVIDER_S = 30L
}
