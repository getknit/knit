package app.getknit.knit.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.LruCache
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import app.getknit.knit.R
import app.getknit.knit.data.decodeBoundedFromBytes
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.GROUP_FACES_MAX
import app.getknit.knit.data.message.GROUP_FACES_MIN
import app.getknit.knit.ui.components.avatarInitial
import app.getknit.knit.ui.theme.AvatarTint
import app.getknit.knit.ui.theme.AvatarTintsLight
import app.getknit.knit.ui.theme.SurfaceLight
import app.getknit.knit.ui.theme.ThemePreferences
import app.getknit.knit.ui.theme.avatarTintIndex
import app.getknit.knit.ui.util.clusterCells
import kotlin.math.min

/**
 * The bitmaps a notification shows: a decoded photo when there is one ([bitmapFor]), else the generated
 * twin of what the chat list draws for the same conversation ([fallbackAvatar]) — the room's mesh mark, a
 * photo-less group's member cluster or tinted glyph, a photo-less person's tinted initial. Split out of
 * [MessageNotifier] so the posting logic and the painting stay readable apart; the palette, the initial rule
 * and the cluster geometry are the in-app ones, imported rather than copied (ADR 2026-09.j8c7, ADR
 * 2026-09.zapp). [themePrefs] is read only to learn whether the app is on the wallpaper palette, so the tile
 * in the shade is harmonized the way the one in the list is.
 */
internal class NotificationAvatars(
    private val context: Context,
    private val themePrefs: ThemePreferences,
) {
    /**
     * Decoded avatars, keyed by the content hash of their bytes. One post decodes the same JPEG once per
     * message ([NotificationHistory] holds 8) plus self, and [postSummary] posts again right after — so
     * without this a busy thread re-decodes one avatar ten times. Content-keyed, so a peer *changing*
     * their avatar simply misses. Budgeted in bytes rather than entries because a 256² ARGB_8888 bitmap
     * is 256 kB; [LruCache] is internally synchronized, which matters because [bitmapFor] runs outside
     * the [states] lock.
     */
    private val avatarCache =
        object : LruCache<Int, Bitmap>(AVATAR_CACHE_BYTES) {
            override fun sizeOf(
                key: Int,
                value: Bitmap,
            ): Int = value.allocationByteCount
        }

    /**
     * Decodes an avatar for the notification, **bounded** to [AVATAR_PX] on each edge via
     * [decodeBoundedFromBytes] — these bytes are peer-supplied (a profile frame off the mesh), and while
     * the blob's byte size is bounded its *pixel* count is not, so a small, highly compressible image
     * would otherwise decode to hundreds of MB. Null (unreadable bytes) falls back to a generated avatar
     * at the caller; only successes are cached.
     */
    fun bitmapFor(bytes: ByteArray?): Bitmap? {
        if (bytes == null) return null
        val key = bytes.contentHashCode()
        avatarCache.get(key)?.let { return it }
        val bitmap = runCatching { decodeBoundedFromBytes(bytes, AVATAR_PX) }.getOrNull() ?: return null
        avatarCache.put(key, bitmap)
        return bitmap
    }

    /**
     * The photoless avatar for a conversation, each the twin of what the chat list draws: a Knit room gets
     * the Knit mesh mark (the room glyph), a radio room the radio glyph ([bridgedRoomAvatar]), a group its
     * members' [clusterAvatar] — or, under two [faces], the people glyph on a disc tinted by the group id
     * ([groupGlyphAvatar]) — and a DM a [letterAvatar] on its title initial, colored by the conversation [key].
     * ADR 2026-09.zapp.
     */
    fun fallbackAvatar(
        kind: ConversationKind,
        title: String,
        key: String,
        faces: List<NotifFace>,
    ): Bitmap =
        when (kind) {
            ConversationKind.NEARBY, ConversationKind.COMMONS -> roomAvatar()
            ConversationKind.MESHTASTIC, ConversationKind.MESHCORE -> bridgedRoomAvatar()
            ConversationKind.GROUP -> if (faces.size >= GROUP_FACES_MIN) clusterAvatar(faces) else groupGlyphAvatar(key)
            ConversationKind.DM -> letterAvatar(title, key)
        }

    /**
     * The Nearby/broadcast room's icon: the Knit mesh mark ([R.drawable.ic_knit_room], the circular variant)
     * drawn edge-to-edge, the notification twin of the chat list's room glyph (`RoomAvatar` — a
     * `secondaryContainer` background with the `onSecondaryContainer`-tinted logo filling the circle). The
     * logo's own disc is the [logoTint] color and its mesh cut-outs reveal the [background] beneath, so the
     * two-tone circle carries its own contrast — fixed to the light-scheme pair to stay legible on either a
     * light or dark notification shade. The adaptive mask rounds the square bitmap to the same circle.
     */
    fun roomAvatar(): Bitmap {
        // CoralSecondaryContainerLight / CoralOnSecondaryContainerLight — the chat-list room glyph colors.
        val background = 0xFFE0E0EC.toInt()
        val logoTint = 0xFF181824.toInt()
        val bitmap = createBitmap(AVATAR_PX, AVATAR_PX)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        ContextCompat.getDrawable(context, R.drawable.ic_knit_room)?.mutate()?.apply {
            setTint(logoTint)
            setBounds(0, 0, AVATAR_PX, AVATAR_PX)
            draw(canvas)
        }
        return bitmap
    }

    /**
     * A radio room's icon (the Meshtastic or the MeshCore room): the radio glyph ([R.drawable.ic_radio_room_glyph]) at
     * [GLYPH_FRACTION] of [roomAvatar]'s disc, in [roomAvatar]'s fixed light pair — the twin of the in-app `RoomAvatar`
     * with `bridged`, so a radio channel's post never wears the Knit mark in the shade either.
     */
    fun bridgedRoomAvatar(): Bitmap {
        // CoralSecondaryContainerLight / CoralOnSecondaryContainerLight, as roomAvatar.
        val background = 0xFFE0E0EC.toInt()
        val glyphTint = 0xFF181824.toInt()
        val bitmap = createBitmap(AVATAR_PX, AVATAR_PX)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val side = (AVATAR_PX * GLYPH_FRACTION).toInt()
        val inset = (AVATAR_PX - side) / 2
        ContextCompat.getDrawable(context, R.drawable.ic_radio_room_glyph)?.mutate()?.apply {
            setTint(glyphTint)
            setBounds(inset, inset, inset + side, inset + side)
            draw(canvas)
        }
        return bitmap
    }

    /**
     * A generated avatar for [name] when it has no photo: a circle (the adaptive mask rounds the filled
     * square) colored deterministically by the identity [key] — the node id, or a conversation id — with the
     * leading grapheme initial, the same initial rule as the in-app [app.getknit.knit.ui.components.Avatar]
     * fallback. Keyed on the identity rather than the name so two people with the same name at least
     * differ in shade (ADR 058), and drawn from the same palette and slot as the in-app avatar
     * ([AvatarTintsLight] via [avatarTintIndex], ADR 2026-09.j8c7), so the face in the shade is the face in
     * the list. Fixed to the light pair for the same reason [roomAvatar] is: the shade's own polarity is
     * the system's, not the app's, and a tone-85 disc with a tone-10 initial reads on either. When the app
     * is on the wallpaper palette the tint is harmonized toward the same primary `KnitTheme` uses, so the
     * two stay identical there too.
     */
    fun letterAvatar(
        name: String,
        key: String,
    ): Bitmap {
        val tint = tintFor(key, wallpaperAccent())
        val bitmap = createBitmap(AVATAR_PX, AVATAR_PX)
        val canvas = Canvas(bitmap)
        canvas.drawColor(tint.container.toArgb())
        drawInitial(canvas, avatarInitial(name), RectF(0f, 0f, AVATAR_PX.toFloat(), AVATAR_PX.toFloat()), tint.onContainer)
        return bitmap
    }

    /**
     * A photo-less group's icon: its other members' faces in the cells `clusterCells` lays out — the same
     * geometry the in-app `GroupAvatar` places its cells with, so the shade shows the picture the list does
     * (ADR 2026-09.zapp). Each cell is the member's photo centre-cropped to it, or their initial on their own
     * tint — the colour they wear alone. [faces] arrive in `groupFaceIds` order and are placed as given, and
     * the adaptive mask rounds the square to the same circle. The seams are painted the light surface: in-app
     * they are transparent and show the real surface, but the shade flattens an adaptive icon's transparency
     * to black (checked on the emulator), so the shade's seam is [SurfaceLight] the way [roomAvatar] pins its
     * pair — the shade's polarity is the system's, not the app's.
     */
    private fun clusterAvatar(faces: List<NotifFace>): Bitmap {
        val shown = faces.take(GROUP_FACES_MAX)
        val bitmap = createBitmap(AVATAR_PX, AVATAR_PX)
        val canvas = Canvas(bitmap)
        canvas.drawColor(SurfaceLight.toArgb())
        // One accent for the whole bitmap: harmonizing is per identity colour, and the seams are not one.
        val accent = wallpaperAccent()
        val px = AVATAR_PX.toFloat()
        clusterCells(shown.size, gap = CLUSTER_GAP_PX / px).forEachIndexed { i, cell ->
            val face = shown[i]
            val dst = RectF(cell.left * px, cell.top * px, cell.right * px, cell.bottom * px)
            val photo = bitmapFor(face.avatarBytes)
            if (photo != null) {
                // Filtered so a 256² face scaled into a 128-wide cell doesn't alias.
                canvas.drawBitmap(photo, centredCrop(photo, dst), dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            } else {
                val tint = tintFor(face.nodeId, accent)
                canvas.drawRect(dst, Paint().apply { color = tint.container.toArgb() })
                drawInitial(canvas, avatarInitial(face.name), dst, tint.onContainer)
            }
        }
        return bitmap
    }

    /**
     * A group with neither a photo nor enough members for a cluster: the people glyph on a disc tinted by
     * the group id, the twin of the in-app fallback. The glyph is the one XML both worlds share
     * ([R.drawable.ic_group_glyph]) at the same [GLYPH_FRACTION] of the disc.
     */
    private fun groupGlyphAvatar(key: String): Bitmap {
        val tint = tintFor(key, wallpaperAccent())
        val bitmap = createBitmap(AVATAR_PX, AVATAR_PX)
        val canvas = Canvas(bitmap)
        canvas.drawColor(tint.container.toArgb())
        val side = (AVATAR_PX * GLYPH_FRACTION).toInt()
        val inset = (AVATAR_PX - side) / 2
        ContextCompat.getDrawable(context, R.drawable.ic_group_glyph)?.mutate()?.apply {
            setTint(tint.onContainer.toArgb())
            setBounds(inset, inset, inset + side, inset + side)
            draw(canvas)
        }
        return bitmap
    }

    /**
     * The source rectangle of [photo] that fills [dst] edge to edge at [dst]'s aspect, taken from its centre —
     * what `ContentScale.Crop` does for the in-app cell. A square avatar in a half-width cell loses its sides,
     * never its middle.
     */
    private fun centredCrop(
        photo: Bitmap,
        dst: RectF,
    ): Rect {
        val scale = min(photo.width / dst.width(), photo.height / dst.height())
        val w = (dst.width() * scale).toInt()
        val h = (dst.height() * scale).toInt()
        val left = (photo.width - w) / 2
        val top = (photo.height - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /**
     * [initial] centred in [cell] at [INITIAL_FRACTION] of the cell's short side — the in-app rule, where a
     * whole disc and a quarter cell scale their letter the same way.
     */
    private fun drawInitial(
        canvas: Canvas,
        initial: String,
        cell: RectF,
        color: Color,
    ) {
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color.toArgb()
                textAlign = Paint.Align.CENTER
                textSize = min(cell.width(), cell.height()) * INITIAL_FRACTION
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
        val baseline = cell.centerY() - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initial, cell.centerX(), baseline, paint)
    }

    /**
     * The tint an identity [key] wears in the shade: its slot in [AvatarTintsLight] (the light pair, whatever
     * the app's own polarity — the shade's is the system's), steered toward the wallpaper [accent] when there
     * is one, exactly as `KnitTheme` steers the in-app palette.
     */
    private fun tintFor(
        key: String,
        accent: Color?,
    ): AvatarTint = AvatarTintsLight[avatarTintIndex(key)].let { if (accent != null) it.harmonizedToward(accent) else it }

    /** The wallpaper primary the in-app palette is harmonized toward, or null when the coral scheme is in force. */
    private fun wallpaperAccent(): Color? = if (onWallpaperPalette()) dynamicLightColorScheme(context).primary else null

    /** Whether `KnitTheme` is drawing the wallpaper palette: the Material You switch, on a release that has one. */
    private fun onWallpaperPalette(): Boolean = themePrefs.dynamicColor.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Which palette the generated avatars are painted from now, as a word: part of a shortcut's fingerprint, so a
     * Material You switch repaints the photo-less shortcuts too (ConversationShortcuts).
     */
    fun paletteKey(): String = if (onWallpaperPalette()) "wallpaper" else "static"

    private companion object {
        // Generated letter-avatar geometry/palette (source avatars are 256²; this matches closely enough).
        private const val AVATAR_PX = 256

        // The initial's height as a fraction of its disc or cell — the in-app Avatar's rule.
        private const val INITIAL_FRACTION = 0.5f

        // The people and radio glyphs' side as a fraction of the disc; GroupAvatar's and RoomAvatar's GLYPH_FRACTION.
        private const val GLYPH_FRACTION = 0.6f

        // The cluster's seam in bitmap pixels: about the in-app 1.5dp once the 256² icon shows at the shade's ~48dp.
        private const val CLUSTER_GAP_PX = 8f

        // ~8 distinct 256² ARGB_8888 avatars resident — more than one notification ever shows.
        private const val AVATAR_CACHE_BYTES = 2 * 1024 * 1024
    }
}
