package app.getknit.knit.usbcontrol

import android.app.Activity
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.core.content.IntentCompat

/**
 * Where Android lands `USB_ACCESSORY_ATTACHED` for knit-cli (debug builds only; `res/xml/knit_usb_accessory.xml`
 * matches it). Launching it is what grants Knit the accessory, so it hands the accessory to [UsbControlServer] and
 * finishes at once: no UI, translucent, out of recents.
 */
class UsbAccessoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Exported, so the extras may be another app's: one that fails to unparcel is no accessory, not a crash.
        runCatching { IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java) }
            .getOrNull()
            ?.let { UsbControlServer.onAttached(applicationContext, it) }
        finish()
    }
}
