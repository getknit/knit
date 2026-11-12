package app.getknit.knit.usbcontrol

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * The bridge's `…debug.USBCTL` (the USB control spike): `--es op enable|disable|open|close|state|pairings|forget`,
 * `state` when absent. `enable` turns the server on (it stays on across restarts); `open` picks up a knit-cli accessory
 * attached while it was off; `pairings` lists the paired computers (a handle prefix, the name, the times: never a key);
 * `forget --es handle <prefix|all>` forgets them and ends a session under one. It never shows a pairing code: that is
 * the phone's screen's alone. Every reply carries the server's state.
 */
internal object UsbControlBridge {
    /** The shortest handle prefix `forget` takes, so a slip can't forget every computer. */
    private const val MIN_PREFIX = 4

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

                "pairings" -> {
                    return JSONObject()
                        .put("status", "ok")
                        .put("message", "pairings")
                        .put("paired", UsbControlServer.pairingsJson(context))
                        .put("usb", UsbControlServer.state(context))
                }

                "forget" -> {
                    val handle = intent.getStringExtra("handle")?.trim().orEmpty()
                    if (handle.length < MIN_PREFIX && handle != UsbControlServer.FORGET_ALL) {
                        return JSONObject().put("status", "error").put("message", "forget needs --es handle <prefix of $MIN_PREFIX+>|all")
                    }
                    when (val n = UsbControlServer.forget(context, handle)) {
                        null -> return JSONObject().put("status", "error").put("message", "the pairings can't be read or written right now")
                        else -> "forgot $n"
                    }
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
