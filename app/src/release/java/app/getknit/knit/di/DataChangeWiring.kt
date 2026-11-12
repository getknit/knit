package app.getknit.knit.di

import android.content.Context
import app.getknit.knit.data.changes.DataChangeSink

/** Release-variant change wiring: nobody reads what the repositories wrote (the debug variant's USB control does). */
@Suppress("UNUSED_PARAMETER")
fun dataChangeSink(context: Context): DataChangeSink = DataChangeSink.NONE
