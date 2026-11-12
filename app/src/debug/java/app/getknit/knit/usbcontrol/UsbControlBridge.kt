package app.getknit.knit.usbcontrol

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * The bridge's `…debug.USBCTL` (the USB control spike): `--es op enable|disable|open|close|state`, `state` when absent.
 * `enable` turns the server on (it stays on across restarts); `open` picks up a knit-cli accessory attached while it
 * was off. Every reply carries the server's state.
 */
internal object UsbControlBridge {
    fun handle(
        context: Context,
        intent: Intent,
    ): JSONObject {
        val note =
            when (val op = intent.getStringExtra("op") ?: "state") {
                "enable" -> {
                    UsbControlServer.setEnabled(context, true)
                    "enabled"
                }

                "disable" -> {
                    UsbControlServer.setEnabled(context, false)
                    "disabled"
                }

                "open" -> {
                    UsbControlServer.openAttached(context)
                }

                "close" -> {
                    UsbControlServer.close("closed by the bridge")
                    "closed"
                }

                "state" -> {
                    "state"
                }

                else -> {
                    return JSONObject().put("status", "error").put("message", "unknown op: $op")
                }
            }
        return JSONObject()
            .put("status", "ok")
            .put("message", note)
            .put("usb", UsbControlServer.state(context))
    }
}
