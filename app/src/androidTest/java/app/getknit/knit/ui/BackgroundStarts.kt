package app.getknit.knit.ui

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Lets the test process start the app's activity while another app is in front, on Xiaomi's HyperOS and MIUI.
 *
 * Both seeded bases launch [app.getknit.knit.MainActivity] from the instrumentation, which runs in the app's own
 * process, while Firebase Test Lab's backdrop app holds the screen. AOSP allows that start for an instrumented app
 * (`BAL_ALLOW_PERMISSION`). HyperOS adds a gate of its own and refuses it (`ActivityStarterImpl: MIUILOG- Permission
 * Denied Activity`, then `Abort background activity starts`), after which `ActivityScenario.launch` waits with no
 * timeout. The first run on the Xiaomi 14 (houji, API 35, 2026-11-19) hung on its first test until the run timed out.
 *
 * The gate reads MIUI app op [MIUI_OP_BACKGROUND_START], the "Display pop-up windows while running in the background"
 * switch. The op number is undocumented and comes from developer reports, so [allow] logs what `appops get` reads
 * back under [TAG]; a launch that still hangs on Xiaomi is diagnosed from that line in the run's logcat.
 */
internal object BackgroundStarts {
    private const val TAG = "BackgroundStarts"

    /** MIUI's app op for starting an activity from the background; not in the public SDK. */
    private const val MIUI_OP_BACKGROUND_START = 10021

    /**
     * Sets the op to `allow` for the app under test on a Xiaomi device; a no-op everywhere else. Runs before every
     * launch, because each Orchestrator test is a fresh process whose package data was cleared.
     */
    fun allow() {
        if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val set = shell("appops set $pkg $MIUI_OP_BACKGROUND_START allow")
        val readBack = shell("appops get $pkg $MIUI_OP_BACKGROUND_START")
        Log.i(TAG, "op $MIUI_OP_BACKGROUND_START for $pkg: set='$set' get='$readBack'")
    }

    /**
     * Runs [command] as the shell user and returns its output; reading to EOF waits for the command to finish. `appops`
     * prints nothing on success and reports an unknown op or a refusal on stderr, so on API 31+ stderr is returned too:
     * without it a failed set and a good one log the same empty line.
     */
    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return automation.executeShellCommand(command).readAll()
        val (stdout, stdin, stderr) = automation.executeShellCommandRwe(command)
        stdin.close()
        val out = stdout.readAll()
        val err = stderr.readAll()
        return if (err.isEmpty()) out else "$out [stderr: $err]"
    }

    private fun ParcelFileDescriptor.readAll(): String =
        ParcelFileDescriptor.AutoCloseInputStream(this).use { it.readBytes().decodeToString().trim() }
}
