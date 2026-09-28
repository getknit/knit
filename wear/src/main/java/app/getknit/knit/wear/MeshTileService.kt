package app.getknit.knit.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.layout.androidImageResource
import androidx.wear.protolayout.layout.imageResource
import androidx.wear.protolayout.material3.CardColors
import androidx.wear.protolayout.material3.CardDefaults.filledCardColors
import androidx.wear.protolayout.material3.CardDefaults.filledTonalCardColors
import androidx.wear.protolayout.material3.CardDefaults.filledVariantCardColors
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.material3.DataCardStyle
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.icon
import androidx.wear.protolayout.material3.iconDataCard
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textDataCard
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.modifiers.loadAction
import androidx.wear.protolayout.modifiers.padding
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.tile
import androidx.wear.tiles.timeInterval
import androidx.wear.tiles.timeline
import androidx.wear.tiles.timelineEntry
import app.getknit.knit.wearstatus.MeshState
import app.getknit.knit.wearstatus.WearStatus
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The Knit tile: the mesh state as its title (or, when a radio the phone has is weak or down, that problem),
 * then three data cards — peers nearby, messages relayed today (the watch's own history), messages held for
 * others — and a Refresh edge button. Material 3 Expressive (ProtoLayout Material3), in the watch's dynamic
 * colours where the user has them on, else Knit's coral ([KnitTileColors]).
 *
 * A tile request must answer at once, so it never waits on Bluetooth: it draws the cached snapshot and, when
 * that is due (or Refresh was tapped), starts a background read that asks for a redraw when it lands
 * ([StatusRefresh]). The layout is a timeline whose live entry expires [StatusText.STALE_MS] after the read,
 * on the watch's own clock, into the same reading titled with the time it was taken ("As of 12:40") — which
 * stays true however long nothing asks — or, if a read since found no phone, "No phone" ([StatusText.shown]).
 */
class MeshTileService : Material3TileService(defaultColorScheme = KnitTileColors) {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val now = System.currentTimeMillis()
        val clicked = requestParams.currentState.lastClickableId == REFRESH_ID
        // The last click id can ride along on the redraw the read itself asks for; a refresh that just ran
        // is not asked for again, which is what keeps that redraw from starting another read.
        val refresh = clicked && now - StatusRefresh.lastForcedMs > CLICK_DEBOUNCE_MS
        StatusRefresh.kick(this@MeshTileService, force = refresh)
        val reading = StatusRefresh.reading.value
        val today = StatusHistory.today(this@MeshTileService, now)
        val snapshot = PhoneStatusReader.cached(this@MeshTileService)
        val failedAt = PhoneStatusReader.failedAt(this@MeshTileService)
        val shown = StatusText.shown(snapshot, failedAt, now)
        if (snapshot == null || shown == null || shown.agedSinceMs != null) {
            return tile(timeline(timelineEntry(layout(shown, today, reading))), freshness = FRESHNESS)
        }
        // Live now: the default entry is what the reading becomes when its window closes.
        val closed = timelineEntry(layout(StatusText.afterLive(snapshot, failedAt), today, reading))
        val live =
            timelineEntry(
                layout(shown, today, reading),
                validity =
                    timeInterval(
                        snapshot.fetchedAtMs.milliseconds,
                        (snapshot.fetchedAtMs + StatusText.STALE_MS).milliseconds,
                    ),
            )
        return tile(timeline(closed, live), freshness = FRESHNESS)
    }

    private fun MaterialScope.layout(
        shown: Shown?,
        today: Today,
        reading: Boolean,
    ): LayoutElement {
        val status = shown?.status
        val aged = shown?.agedSinceMs
        return primaryLayout(
            titleSlot = { text((aged?.let { "As of ${Counts.clock(it)}" } ?: title(status)).layoutString) },
            mainSlot = {
                when {
                    status == null -> {
                        message(Glyph.NoPhone, "No phone", "Open Knit on your phone")
                    }

                    StatusText.resting(status) -> {
                        message(
                            StatusText.glyph(status.state),
                            "Mesh ${StatusText.word(status.state).lowercase()}",
                            "Resume on your phone",
                        )
                    }

                    else -> {
                        metrics(status, today, aged = aged != null)
                    }
                }
            },
            bottomSlot = {
                textEdgeButton(
                    onClick = clickable(loadAction(), id = REFRESH_ID),
                    modifier = LayoutModifier.contentDescription("Refresh from phone"),
                ) {
                    text(
                        (
                            if (reading) {
                                "Reading…"
                            } else if (status == null) {
                                "Try again"
                            } else {
                                "Refresh"
                            }
                        ).layoutString,
                    )
                }
            },
        )
    }

    private fun title(status: WearStatus?): String =
        StatusLines.problemShort(status)
            ?: when (status?.state) {
                null -> "Knit"
                MeshState.NoRadio -> "Radios off"
                MeshState.Degraded -> "Weak mesh"
                else -> StatusText.word(status.state)
            }

    /**
     * Three cards: the count that matters most in the state's colour (quiet like the others once the reading is
     * [aged]), the other two quieter. An older phone sends no carrying count; its third card is the all-time
     * relayed total instead.
     */
    private fun MaterialScope.metrics(
        status: WearStatus,
        today: Today,
        aged: Boolean,
    ): LayoutElement {
        val carrying = StatusText.carrying(status)
        return buttonGroup {
            buttonGroupItem {
                metric(
                    status.nearby.toString(),
                    "nearby",
                    StatusLines.nearbyLine(status).text,
                    if (aged) filledTonalCardColors() else stateColors(status.state),
                )
            }
            buttonGroupItem {
                metric(
                    StatusText.relayedToday(today).text,
                    "today",
                    StatusLines.relayedLine(status, today).text,
                    filledTonalCardColors(),
                )
            }
            buttonGroupItem {
                if (status.extra == null) {
                    metric(
                        Counts.compact(status.relayed),
                        "relayed",
                        "${Counts.grouped(status.relayed)} relayed in all",
                        filledVariantCardColors(),
                    )
                } else {
                    metric(carrying.text, "held", StatusLines.carryingLine(status).text, filledVariantCardColors())
                }
            }
        }
    }

    /**
     * One narrow data card: a numeral that shrinks to fit ("3.3k" and "3/4" in a third of a small screen) over
     * a one-word label. The compact style's 14 dp side padding is cut to 4 so the label is not ellipsized.
     */
    private fun MaterialScope.metric(
        value: String,
        label: String,
        description: String,
        colors: CardColors,
    ): LayoutElement =
        textDataCard(
            onClick = openApp(),
            modifier = LayoutModifier.contentDescription(description),
            title = { text(value.layoutString, incrementsForTypographySize = SHRINK_TO_FIT) },
            content = { text(label.layoutString, incrementsForTypographySize = LABEL_SHRINK) },
            width = expand(),
            height = expand(),
            colors = colors,
            style = DataCardStyle.smallCompactDataCardStyle(),
            contentPadding = padding(horizontal = CARD_SIDE_PADDING_DP, vertical = CARD_END_PADDING_DP),
        )

    /** Resting or unreachable: one wide card with the state's glyph, what it means, and what to do. */
    private fun MaterialScope.message(
        glyph: Glyph,
        headline: String,
        hint: String,
    ): LayoutElement =
        iconDataCard(
            onClick = openApp(),
            title = { text(headline.layoutString, typography = Typography.TITLE_MEDIUM) },
            content = { text(hint.layoutString, maxLines = 2) },
            secondaryIcon = { icon(imageResource(androidImageResource(glyph.res))) },
            width = expand(),
            height = expand(),
            colors = filledTonalCardColors(),
        )

    /** Linked and alone in the primary colour; a weak or radio-less mesh in the error container. */
    private fun MaterialScope.stateColors(state: MeshState): CardColors =
        when (state) {
            MeshState.Degraded, MeshState.NoRadio -> {
                CardColors(
                    backgroundColor = colorScheme.errorContainer,
                    titleColor = colorScheme.onErrorContainer,
                    contentColor = colorScheme.onErrorContainer,
                )
            }

            MeshState.Alone -> {
                filledVariantCardColors()
            }

            else -> {
                filledCardColors()
            }
        }

    private fun openApp(): Clickable =
        clickable(
            action =
                ActionBuilders.LaunchAction
                    .Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity
                            .Builder()
                            .setPackageName(packageName)
                            .setClassName(StatusActivity::class.java.name)
                            .build(),
                    ).build(),
            id = OPEN_ID,
        )

    private companion object {
        const val REFRESH_ID = "refresh"
        const val OPEN_ID = "open"
        const val CLICK_DEBOUNCE_MS = 15_000L
        const val CARD_SIDE_PADDING_DP = 4f
        const val CARD_END_PADDING_DP = 8f
        val FRESHNESS = 5.minutes

        /**
         * Autosize steps, in sp: the largest that fits is used (renderers without autosize take the base). At
         * most nine — ProtoLayout caps a font style at ten sizes, the base included.
         */
        val SHRINK_TO_FIT = listOf(-3f, -6f, -9f, -12f, -15f, -18f)

        /** The label's own, gentler steps: every size must stay positive. */
        val LABEL_SHRINK = listOf(-1f, -2f, -3f)
    }
}

/** The tile's scheme when dynamic colour is off: :app's dark coral, as the watch app's own fallback. */
@Suppress("MagicNumber") // ARGB literals, copied from :app's ui/theme/Color.kt
private val KnitTileColors =
    ColorScheme(
        primary = 0xFFFFB5A0.toInt().argb,
        primaryDim = 0xFFE89C87.toInt().argb,
        primaryContainer = 0xFF7E2D17.toInt().argb,
        onPrimary = 0xFF5F1500.toInt().argb,
        onPrimaryContainer = 0xFFFFDBD1.toInt().argb,
        secondary = 0xFFC5C4D2.toInt().argb,
        secondaryContainer = 0xFF444450.toInt().argb,
        onSecondary = 0xFF2D2E39.toInt().argb,
        onSecondaryContainer = 0xFFE0E0EC.toInt().argb,
        tertiary = 0xFF72DAA0.toInt().argb,
        tertiaryContainer = 0xFF005230.toInt().argb,
        onTertiary = 0xFF00391F.toInt().argb,
        onTertiaryContainer = 0xFF8FF7BD.toInt().argb,
        error = 0xFFFFB4AB.toInt().argb,
        onError = 0xFF690005.toInt().argb,
    )
