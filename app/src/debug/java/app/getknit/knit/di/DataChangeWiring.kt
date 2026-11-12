package app.getknit.knit.di

import android.content.Context
import app.getknit.knit.data.changes.DataChangeSink
import app.getknit.knit.usbcontrol.UsbControlServer

/**
 * Debug-variant change wiring: the repositories report what they wrote to the USB control's change log, which keeps
 * it only while USB control is switched on (`usbcontrol/ChangeLog`). `src/release` wires nobody.
 */
fun dataChangeSink(context: Context): DataChangeSink = UsbControlServer.changeSink(context)
