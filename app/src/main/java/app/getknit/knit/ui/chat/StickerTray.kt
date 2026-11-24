package app.getknit.knit.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.data.sticker.Sticker
import app.getknit.knit.ui.image.BlobImage
import app.getknit.knit.ui.preview.KnitPreview
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.Flow

/**
 * What the chat hands the sticker tray and the bubbles (ADR 2026-11.rk6d): the long-press menu's [actions], the tray's
 * two lists as flows the open tray alone collects, and the send a tap makes — the screen's, which adds the reply being
 * drafted.
 */
class StickerUi(
    val actions: StickerActions,
    val saved: Flow<List<Sticker>>,
    val recent: Flow<List<Sticker>>,
    val onSend: (Sticker) -> Unit,
)

/** The tray's two tabs. */
enum class StickerTab(
    @param:StringRes val label: Int,
) {
    Saved(R.string.sticker_tab_saved),
    Recent(R.string.sticker_tab_recent),
}

/** The composer's sticker button, at the start of the field: opens the tray. The trailing buttons' shape and inset. */
@Composable
internal fun StickerTrayButton(onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .testTag("chat_stickers")
                .clip(CircleShape)
                // The label rides the icon's contentDescription, as on the paperclip, so TalkBack reads it once.
                .clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.EmojiEmotions,
            contentDescription = stringResource(R.string.sticker_tray_title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The sticker tray (ADR 2026-11.rk6d): the stickers the user saved and the ones this phone sent lately. In the chat's
 * window ([InWindowSheet]) so the keyboard stays up, as the emoji sheet does. A tap sends the sticker and leaves the
 * tray open for the next one; the lists are collected only while it is open.
 */
@Composable
internal fun StickerTraySheet(
    stickers: StickerUi,
    onDismiss: () -> Unit,
) {
    val saved by stickers.saved.collectAsStateWithLifecycle(initialValue = null)
    val recent by stickers.recent.collectAsStateWithLifecycle(initialValue = null)
    InWindowSheet(
        paneTitle = stringResource(R.string.sticker_tray_title),
        onDismiss = onDismiss,
        modifier = Modifier.testTag("sticker_tray"),
    ) {
        StickerTraySheetContent(
            saved = saved,
            recent = recent,
            savedHashes = stickers.actions.savedHashes,
            onSend = stickers.onSend,
            onSave = stickers.actions.onSave,
            onRemove = stickers.actions.onRemove,
        )
    }
}

/**
 * The tray's body, stateless apart from the tab and an open cell menu: [saved] and [recent] are null until their first
 * read. It opens on Saved, or on Recent when nothing is saved and something was sent; [initialTab] exists for the
 * previews. A long-press on a cell offers Remove from stickers on a saved one, else Save sticker.
 */
@Composable
internal fun StickerTraySheetContent(
    saved: List<Sticker>?,
    recent: List<Sticker>?,
    savedHashes: Set<String>,
    onSend: (Sticker) -> Unit,
    onSave: (Sticker) -> Unit,
    onRemove: (plainHash: String) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: StickerTab? = null,
) {
    var chosen by rememberSaveable { mutableStateOf(initialTab) }
    val tab = chosen ?: if (saved.isNullOrEmpty() && !recent.isNullOrEmpty()) StickerTab.Recent else StickerTab.Saved
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .fillMaxHeight(TRAY_HEIGHT_FRACTION),
    ) {
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            StickerTab.entries.forEach { entry ->
                Tab(
                    selected = entry == tab,
                    onClick = { chosen = entry },
                    text = { Text(stringResource(entry.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("sticker_tab_${entry.name.lowercase()}"),
                )
            }
        }
        // Nothing until the first read lands: an empty state that flashed before the list would be a lie.
        val shown = (if (tab == StickerTab.Saved) saved else recent) ?: return@Column
        when {
            shown.isEmpty() -> {
                Text(
                    text = stringResource(if (tab == StickerTab.Saved) R.string.sticker_saved_empty else R.string.sticker_recent_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 48.dp)
                            .testTag("sticker_empty"),
                )
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(CELL_MIN),
                    modifier = Modifier.fillMaxSize().testTag("sticker_grid"),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(shown, key = { it.plainHash }) { sticker ->
                        StickerCell(
                            sticker = sticker,
                            saved = sticker.plainHash in savedHashes,
                            onSend = { onSend(sticker) },
                            onSave = { onSave(sticker) },
                            onRemove = { onRemove(sticker.plainHash) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerCell(
    sticker: Sticker,
    saved: Boolean,
    onSend: () -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val keepLabel = stringResource(if (saved) R.string.chat_action_remove_sticker else R.string.chat_action_save_sticker)
    Box(
        modifier =
            Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.sticker_send),
                    onClick = onSend,
                    onLongClickLabel = keepLabel,
                    onLongClick = { menuOpen = true },
                ).padding(6.dp)
                .testTag("sticker_cell"),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = BlobImage(sticker.blobHash, sticker.mime, sticker.blobKey),
            contentDescription = stringResource(R.string.sticker_label),
            contentScale = ContentScale.Fit,
            // The tray's sheet is dark in a dark theme too: the same outline the chat draws.
            modifier = Modifier.fillMaxSize().stickerOutline(stickerOutlineColor()),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(keepLabel) },
                onClick = {
                    menuOpen = false
                    if (saved) onRemove() else onSave()
                },
                modifier = Modifier.testTag("sticker_cell_keep"),
            )
        }
    }
}

/** The smallest grid cell: four across a 360 dp phone. */
private val CELL_MIN = 80.dp

/** As the emoji sheet: tall enough for a few rows over the keyboard. */
private const val TRAY_HEIGHT_FRACTION = 0.6f

// The previews carry no blob bytes, so each cell draws empty: they show the tray's frame, tabs and empty states.
private val PREVIEW_STICKERS =
    List(6) { Sticker(plainHash = "p$it", blobHash = "b$it", blobKey = null, mime = "image/webp", animated = false) }

@Preview
@Composable
fun StickerTrayEmptyPreview() =
    KnitPreview {
        StickerTraySheetContent(
            saved = emptyList(),
            recent = emptyList(),
            savedHashes = emptySet(),
            onSend = {},
            onSave = {},
            onRemove = {},
        )
    }

@Preview
@Composable
fun StickerTraySavedPreview() =
    KnitPreview {
        StickerTraySheetContent(
            saved = PREVIEW_STICKERS,
            recent = emptyList(),
            savedHashes = PREVIEW_STICKERS.map { it.plainHash }.toSet(),
            onSend = {},
            onSave = {},
            onRemove = {},
        )
    }
