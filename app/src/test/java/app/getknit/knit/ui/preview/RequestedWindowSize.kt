package app.getknit.knit.ui.preview

import org.junit.rules.ExternalResource

/**
 * Sizes each window to what its view asked for, as a phone's window manager does. Robolectric otherwise gives a
 * `wrap_content` window (a dialog) the whole display, so `ViewRootImpl` measures the dialog at two widths on every
 * traversal (the platform's preferred dialog width, then halfway to the display's), and a text field re-measured at
 * each asks for the next pass. Compose never idles, and the preview fails after the 60 s idling timeout. On a phone
 * the window shrinks to its content and the passes settle.
 */
class RequestedWindowSize : ExternalResource() {
    private var previous: String? = null

    override fun before() {
        previous = System.setProperty(PROPERTY, "true")
    }

    override fun after() {
        previous?.let { System.setProperty(PROPERTY, it) } ?: System.clearProperty(PROPERTY)
    }

    private companion object {
        /** Read by Robolectric's `ShadowWindowManagerGlobal` at each relayout. Robolectric publishes no constant for it. */
        const val PROPERTY = "robolectric.windowManager.useRequestedSize"
    }
}
