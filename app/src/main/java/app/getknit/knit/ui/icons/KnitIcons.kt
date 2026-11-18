package app.getknit.knit.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import app.getknit.knit.R

/**
 * Marks Knit draws itself, because no Material glyph says what they say.
 *
 * Each is a vector drawable rather than an [ImageVector] built in Kotlin, because these marks are needed in
 * two worlds: Compose reads them here, and the notification framework needs a real `R.drawable` for a
 * status-bar icon. One resource keeps those from drifting apart.
 */
object KnitIcons {
    /**
     * Direct file transfer: a page with a signal breaking off its corner.
     *
     * A paperclip would have been wrong here. It is the mark for an attachment — small, sealed, carried by the
     * mesh and kept in the blob store — and this is the opposite of that: the file leaves the phone whole, over
     * a link the two devices raise between themselves. The page says "a whole file", the waves say "over the
     * air", and nothing else in the app draws either.
     */
    val DirectTransfer: ImageVector
        @Composable
        get() = ImageVector.vectorResource(R.drawable.ic_direct_transfer)

    /**
     * A group with no photo and too few members to draw as a cluster: Material's own "group" glyph, but as
     * a resource rather than `Icons.Filled.Group`, because the notification shade paints the same fallback
     * disc onto a `Bitmap` and an [ImageVector] cannot be drawn there. ADR 2026-09.zapp.
     */
    val Group: ImageVector
        @Composable
        get() = ImageVector.vectorResource(R.drawable.ic_group_glyph)

    /**
     * A radio room (the Meshtastic or the MeshCore room, a board's own channel bridged into Knit): Material's "cell
     * tower" glyph, as a resource for the same two worlds as [Group]. It replaces the Knit mesh mark there, because that
     * mark beside a public channel's name read as one of Knit's own rooms, and it is neither network's logo.
     */
    val RadioRoom: ImageVector
        @Composable
        get() = ImageVector.vectorResource(R.drawable.ic_radio_room_glyph)

    /**
     * The read tick: Material's ✓✓ knocked out of a filled disc (ADR 2026-11.z6jw). It differs from the
     * delivered ✓✓ in shape as well as colour — a disc where there was none — so it reads without colour vision, and as
     * one even-odd path it takes a single tint like every other tick, the checks showing the bubble through.
     *
     * Built in Kotlin rather than as a vector drawable because `deliveryIcon` hands it out from plain code and nothing
     * outside Compose ever draws it. The checks are `done_all`'s geometry, thickened to stay legible at the tick's 14 dp
     * and centred in the disc.
     */
    val ReadTick: ImageVector by lazy {
        ImageVector
            .Builder(name = "ReadTick", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(READ_TICK_PATH),
                pathFillType = PathFillType.EvenOdd,
                fill = SolidColor(Color.Black),
            ).build()
    }

    // The disc (two arcs), then the three knocked-out strokes of the double check, which never overlap one another.
    private const val READ_TICK_PATH =
        "M12,1A11,11 0,1 1,12,23A11,11 0,1 1,12,1Z " +
            "M10.55,10.59L12.11,12.15L15.67,8.60L14.10,7.04Z " +
            "M18.36,7.04L12.11,13.29L9.90,11.07L8.06,12.91L12.11,16.96L20.20,8.88Z " +
            "M7.20,12.63L5.64,11.07L3.80,12.91L7.86,16.96L9.42,15.40L6.93,12.91Z"
}
