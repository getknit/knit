package app.getknit.knit.ui.media

import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.mesh.protocol.LinkCard
import app.getknit.knit.ui.chat.DurationBadge
import app.getknit.knit.ui.chat.FileOpenerDialog
import app.getknit.knit.ui.chat.FullscreenImage
import app.getknit.knit.ui.chat.FullscreenImageViewer
import app.getknit.knit.ui.chat.FullscreenVideoPlayer
import app.getknit.knit.ui.chat.PendingSave
import app.getknit.knit.ui.chat.fileIconFor
import app.getknit.knit.ui.chat.formatVideoDuration
import app.getknit.knit.ui.chat.rememberFileOpener
import app.getknit.knit.ui.chat.rememberPoster
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.components.leftToRight
import app.getknit.knit.ui.image.BlobImage
import app.getknit.knit.ui.image.LinkCardImage
import app.getknit.knit.ui.openUrl
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import app.getknit.knit.ui.util.rememberCurrentTimeMillis
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.ZoneId

/**
 * "Media, links and files" for one thread (ADR 2026-11.dgvf), opened from the chat's overflow menu. A photo or video
 * opens in the chat's own viewer; a file opens through the chat's own opener; a link opens in the browser, as the
 * bubble's does. [onShowInChat] closes the screen and scrolls the thread to the message (ADR 2026-10.4cyt's
 * hand-back).
 */
@Composable
fun ChatMediaScreen(
    conversationId: String,
    onBack: () -> Unit,
    onShowInChat: (messageId: String) -> Unit,
    viewModel: ChatMediaViewModel = koinViewModel { parametersOf(conversationId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // A toast shows over the viewer's Dialog, which a Scaffold snackbar would sit under.
    LaunchedEffect(Unit) {
        viewModel.events.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val fileOpener =
        rememberFileOpener(
            saveNeeded = viewModel.saveNeeded,
            savedFiles = viewModel.savedFiles,
            onOpen = viewModel::openFile,
            onWriteTo = viewModel::writeFile,
        )
    ChatMediaScreenContent(
        state = state,
        onBack = onBack,
        onShowInChat = onShowInChat,
        onSelectTab = viewModel::selectTab,
        onLoadMore = viewModel::loadMore,
        onOpenFile = fileOpener::open,
        onOpenLink = { url -> openUrl(context, url) },
        onLoadVideo = viewModel::videoBytes,
        onSavePicture = viewModel::savePicture,
        onSaveVideo = viewModel::saveVideo,
    )
    FileOpenerDialog(fileOpener)
}

@Composable
internal fun ChatMediaScreenContent(
    state: ChatMediaUiState,
    onBack: () -> Unit = {},
    onShowInChat: (messageId: String) -> Unit = {},
    onSelectTab: (MediaTab) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onOpenFile: (PendingSave) -> Unit = {},
    onOpenLink: (url: String) -> Unit = {},
    onLoadVideo: suspend (hash: String, key: String?) -> ByteArray? = { _, _ -> null },
    onSavePicture: (hash: String, key: String?, mime: String?) -> Unit = { _, _, _ -> },
    onSaveVideo: (hash: String, key: String?, mime: String?) -> Unit = { _, _, _ -> },
) {
    val tab = state.tab
    var viewing by remember { mutableStateOf<MediaTile?>(null) }
    val zone = ZoneId.systemDefault()
    Scaffold(
        modifier = Modifier.testTag("screen_chat_media"),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { BarTitle(stringResource(R.string.media_title)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                MediaTab.entries.forEach { entry ->
                    Tab(
                        selected = entry == tab,
                        onClick = { if (entry != tab) onSelectTab(entry) },
                        text = { Text(stringResource(entry.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.testTag("media_tab_${entry.name.lowercase()}"),
                    )
                }
            }
            if (state.loaded) {
                when (tab) {
                    MediaTab.Media -> {
                        if (state.media.isEmpty()) {
                            EmptyState(stringResource(R.string.media_empty_media), "media_empty_media")
                        } else {
                            MediaGrid(
                                sections = remember(state.media, zone) { monthSections(state.media, zone) { it.sentAt } },
                                zone = zone,
                                more = state.more,
                                onLoadMore = onLoadMore,
                                onOpen = { viewing = it },
                                onShowInChat = onShowInChat,
                            )
                        }
                    }

                    MediaTab.Files -> {
                        if (state.files.isEmpty()) {
                            EmptyState(stringResource(R.string.media_empty_files), "media_empty_files")
                        } else {
                            FileList(
                                sections = remember(state.files, zone) { monthSections(state.files, zone) { it.sentAt } },
                                zone = zone,
                                more = state.more,
                                onLoadMore = onLoadMore,
                                onOpenFile = onOpenFile,
                                onShowInChat = onShowInChat,
                            )
                        }
                    }

                    MediaTab.Links -> {
                        if (state.links.isEmpty()) {
                            EmptyState(stringResource(R.string.media_empty_links), "media_empty_links")
                        } else {
                            LinkList(
                                sections = remember(state.links, zone) { monthSections(state.links, zone) { it.sentAt } },
                                zone = zone,
                                more = state.more,
                                onLoadMore = onLoadMore,
                                onOpenLink = onOpenLink,
                                onShowInChat = onShowInChat,
                            )
                        }
                    }
                }
            }
        }
    }

    viewing?.let { tile ->
        val showInChat = {
            viewing = null
            onShowInChat(tile.messageId)
        }
        if (tile.video) {
            FullscreenVideoPlayer(
                load = { onLoadVideo(tile.hash, tile.key) },
                width = tile.width,
                height = tile.height,
                onDismiss = { viewing = null },
                onSave = { onSaveVideo(tile.hash, tile.key, tile.mime) },
                onShowInChat = showInChat,
            )
        } else {
            val now by rememberCurrentTimeMillis()
            FullscreenImageViewer(
                fullscreen = FullscreenImage(BlobImage(tile.hash, tile.mime, tile.key), tile.mine, tile.senderName, tile.sentAt),
                now = now,
                onDismiss = { viewing = null },
                onSave = { onSavePicture(tile.hash, tile.key, tile.mime) },
                onShowInChat = showInChat,
            )
        }
    }
}

/** Reaching the last few items of a full window asks for the next page; nothing while there is none. */
@Composable
private fun LoadMoreWhenNearEnd(
    lastVisible: () -> Int?,
    total: Int,
    more: Boolean,
    onLoadMore: () -> Unit,
) {
    val currentLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(total, more) {
        if (!more) return@LaunchedEffect
        snapshotFlow { lastVisible() ?: -1 }
            .distinctUntilChanged()
            .filter { it >= total - NEAR_END }
            .collect { currentLoadMore() }
    }
}

@Composable
private fun MediaGrid(
    sections: List<MonthSection<MediaTile>>,
    zone: ZoneId,
    more: Boolean,
    onLoadMore: () -> Unit,
    onOpen: (MediaTile) -> Unit,
    onShowInChat: (messageId: String) -> Unit,
) {
    val gridState: LazyGridState = rememberLazyGridState()
    // A covered tile shows after one tap, as a flagged picture in the chat does, until the screen closes.
    val revealed = remember { mutableStateListOf<String>() }
    val total = sections.sumOf { it.items.size + 1 }
    LoadMoreWhenNearEnd({
        gridState.layoutInfo.visibleItemsInfo
            .lastOrNull()
            ?.index
    }, total, more, onLoadMore)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(TILE_MIN.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize().testTag("media_grid"),
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        sections.forEach { section ->
            item(key = section.key, span = { GridItemSpan(maxLineSpan) }, contentType = "month") {
                MonthHeader(section, zone)
            }
            items(section.items, key = { it.messageId }, contentType = { "tile" }) { tile ->
                val hidden = tile.covered && tile.messageId !in revealed
                MediaTileView(
                    tile = tile,
                    hidden = hidden,
                    onClick = {
                        when {
                            hidden -> revealed += tile.messageId
                            !tile.held -> onShowInChat(tile.messageId)
                            else -> onOpen(tile)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MediaTileView(
    tile: MediaTile,
    hidden: Boolean,
    onClick: () -> Unit,
) {
    val date = describedDate(tile.sentAt)
    val description =
        when {
            hidden -> stringResource(R.string.media_hidden_desc, tile.senderName, date)
            !tile.held -> stringResource(R.string.media_not_here_desc, tile.senderName, date)
            tile.video -> stringResource(R.string.media_video_desc, tile.senderName, date, formatVideoDuration(tile.durationMs ?: 0L))
            else -> stringResource(R.string.media_photo_desc, tile.senderName, date)
        }
    val clickLabel =
        when {
            hidden -> stringResource(R.string.media_reveal)
            !tile.held -> stringResource(R.string.media_show_in_chat)
            tile.video -> stringResource(R.string.chat_video_play, formatVideoDuration(tile.durationMs ?: 0L))
            else -> stringResource(R.string.chat_view_photo)
        }
    val poster = rememberPoster(tile.poster)
    Box(
        modifier =
            Modifier
                .testTag("media_tile_${tile.messageId}")
                .aspectRatio(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // A covered tile draws nothing of its picture, poster included: a blur would not hide it before Android 12.
            tile.video && poster != null && !hidden -> {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().testTag("media_tile_picture"),
                )
            }

            !tile.video && tile.held && !hidden -> {
                AsyncImage(
                    model = BlobImage(tile.hash, tile.mime, tile.key),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().testTag("media_tile_picture"),
                )
            }
        }
        val glyph =
            when {
                hidden -> Icons.Filled.VisibilityOff
                !tile.held -> Icons.Filled.Download
                tile.video -> Icons.Filled.PlayArrow
                else -> null
            }
        glyph?.let {
            val onPicture = tile.video && poster != null && !hidden
            Icon(
                it,
                contentDescription = null,
                tint = if (onPicture) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .size(GLYPH.dp)
                        .then(if (onPicture) Modifier.background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(50)) else Modifier)
                        .padding(4.dp),
            )
        }
        if (tile.video && !hidden && tile.durationMs != null) {
            // The length is already in the tile's description; the badge is for the eye.
            DurationBadge(tile.durationMs, Modifier.align(Alignment.BottomStart).clearAndSetSemantics {})
        }
    }
}

@Composable
private fun FileList(
    sections: List<MonthSection<FileItem>>,
    zone: ZoneId,
    more: Boolean,
    onLoadMore: () -> Unit,
    onOpenFile: (PendingSave) -> Unit,
    onShowInChat: (messageId: String) -> Unit,
) {
    val listState = rememberLazyListState()
    val total = sections.sumOf { it.items.size + 1 }
    LoadMoreWhenNearEnd({
        listState.layoutInfo.visibleItemsInfo
            .lastOrNull()
            ?.index
    }, total, more, onLoadMore)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("media_files"),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        monthItems(sections, zone, key = { it.messageId }) { file ->
            FileRow(
                file = file,
                onClick = { file.pending?.let(onOpenFile) ?: onShowInChat(file.messageId) },
                onShowInChat = { onShowInChat(file.messageId) },
            )
        }
    }
}

@Composable
private fun FileRow(
    file: FileItem,
    onClick: () -> Unit,
    onShowInChat: () -> Unit,
) {
    val context = LocalContext.current
    val title =
        file.name ?: stringResource(if (file.voice) R.string.media_voice_message else R.string.chat_file_unnamed)
    val details =
        listOfNotNull(
            file.size?.let { Formatter.formatShortFileSize(context, it) },
            file.voiceDurationMs?.takeIf { file.voice }?.let(::formatVideoDuration),
            file.senderName.takeIf { it.isNotBlank() },
            shortDate(file.sentAt),
        )
    ListRow(
        tag = "media_file_${file.messageId}",
        clickLabel = stringResource(if (file.pending != null) R.string.chat_file_open else R.string.media_show_in_chat),
        onClick = onClick,
        onShowInChat = onShowInChat,
        leading = {
            Icon(
                if (file.voice) Icons.Filled.Mic else fileIconFor(file.mime, file.name),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(LEADING.dp).padding(8.dp),
            )
        },
        title = title,
        lines = listOfNotNull(joinedWithDots(details), stringResource(R.string.chat_file_flagged).takeIf { file.flagged }),
    )
}

@Composable
private fun LinkList(
    sections: List<MonthSection<LinkItem>>,
    zone: ZoneId,
    more: Boolean,
    onLoadMore: () -> Unit,
    onOpenLink: (url: String) -> Unit,
    onShowInChat: (messageId: String) -> Unit,
) {
    val listState = rememberLazyListState()
    val total = sections.sumOf { it.items.size + 1 }
    LoadMoreWhenNearEnd({
        listState.layoutInfo.visibleItemsInfo
            .lastOrNull()
            ?.index
    }, total, more, onLoadMore)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("media_links"),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        monthItems(sections, zone, key = { it.key }) { link ->
            LinkRow(link = link, onClick = { onOpenLink(link.url) }, onShowInChat = { onShowInChat(link.messageId) })
        }
    }
}

@Composable
private fun LinkRow(
    link: LinkItem,
    onClick: () -> Unit,
    onShowInChat: () -> Unit,
) {
    val card: LinkCard? = link.card
    val details =
        listOfNotNull(
            card?.host?.takeIf { it.isNotBlank() },
            link.senderName.takeIf { it.isNotBlank() },
            shortDate(link.sentAt),
        )
    ListRow(
        tag = "media_link_${link.key}",
        clickLabel = stringResource(R.string.chat_open_link),
        onClick = onClick,
        onShowInChat = onShowInChat,
        leading = {
            val hash = link.cardHash
            if (card != null && card.hasImage && hash != null) {
                AsyncImage(
                    model = LinkCardImage(hash, link.cardKey),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(LEADING.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            } else {
                Icon(
                    Icons.Filled.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(LEADING.dp).padding(8.dp),
                )
            }
        },
        // A card's title is cleaned at decode (`LinkText`); a bare link is shown as written, minus anything that
        // could reorder it, and always left to right.
        title = card?.title?.takeIf { it.isNotBlank() } ?: displayUrl(link.url),
        titleIsUrl = card?.title.isNullOrBlank(),
        lines = listOfNotNull(joinedWithDots(details)),
    )
}

/** The month headings and their rows, for a list. */
private fun <T> LazyListScope.monthItems(
    sections: List<MonthSection<T>>,
    zone: ZoneId,
    key: (T) -> String,
    row: @Composable (T) -> Unit,
) {
    sections.forEach { section ->
        item(key = section.key, contentType = "month") { MonthHeader(section, zone) }
        items(section.items, key = key, contentType = { "row" }) { row(it) }
    }
}

@Composable
private fun MonthHeader(
    section: MonthSection<*>,
    zone: ZoneId,
) {
    Text(
        text = monthLabel(section.month, zone),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 6.dp)
                .semantics { heading() }
                .testTag("media_${section.key}"),
    )
}

/** A Files or Links row: a tap opens it, and its trailing button shows the message in the chat. */
@Composable
private fun ListRow(
    tag: String,
    clickLabel: String,
    onClick: () -> Unit,
    onShowInChat: () -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    lines: List<String>,
    titleIsUrl: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClickLabel = clickLabel, onClick = onClick)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.let { if (titleIsUrl) it.leftToRight() else it },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onShowInChat, modifier = Modifier.size(48.dp).testTag("${tag}_show")) {
            Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = stringResource(R.string.media_show_in_chat))
        }
    }
}

@Composable
private fun EmptyState(
    text: String,
    tag: String,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** [parts] joined pairwise with the house separator ("a · b · c"), or null with none. */
@Composable
private fun joinedWithDots(parts: List<String>): String? {
    if (parts.isEmpty()) return null
    var joined = parts.first()
    for (part in parts.drop(1)) joined = stringResource(R.string.joined_with_dot, joined, part)
    return joined
}

/** A tile's date read aloud: the day and the time, so two tiles from one sender a day apart never sound alike. */
@Composable
private fun describedDate(sentAt: Long): String =
    DateUtils.formatDateTime(
        LocalContext.current,
        sentAt,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
    )

/**
 * A peer's link as shown in a row: without control and format characters, the Unicode category the bidi overrides
 * live in, which would let a link read as another host. What opens is the link itself, as the bubble opens it.
 */
internal fun displayUrl(url: String): String = url.filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }

private const val TILE_MIN = 96
private const val GLYPH = 32
private const val LEADING = 48
private const val NEAR_END = 12

private val previewMedia =
    listOf(
        MediaTile(
            "m1",
            "h1",
            "image/jpeg",
            null,
            video = false,
            held = true,
            covered = false,
            mine = true,
            senderName = "You",
            sentAt = PREVIEW_NOW,
        ),
        MediaTile(
            "m2",
            "h2",
            "video/mp4",
            null,
            video = true,
            durationMs = 42_000,
            held = true,
            covered = false,
            mine = false,
            senderName = "Ana",
            sentAt = PREVIEW_NOW - DAY_MS,
        ),
        MediaTile(
            "m3",
            "h3",
            "image/jpeg",
            null,
            video = false,
            held = false,
            covered = false,
            mine = false,
            senderName = "Ana",
            sentAt =
                PREVIEW_NOW - 2 * DAY_MS,
        ),
        MediaTile(
            "m4",
            "h4",
            "image/webp",
            null,
            video = false,
            held = true,
            covered = true,
            mine = false,
            senderName = "Ana",
            sentAt =
                PREVIEW_NOW - 40 * DAY_MS,
        ),
        MediaTile(
            "m5",
            "h5",
            "image/jpeg",
            null,
            video = false,
            held = true,
            covered = false,
            mine = true,
            senderName = "You",
            sentAt =
                PREVIEW_NOW - 41 * DAY_MS,
        ),
    )

private val previewFiles =
    listOf(
        FileItem(
            "f1",
            "h6",
            null,
            "trail-map-2026.pdf",
            "application/pdf",
            482_133,
            voice = false,
            held = true,
            flagged = false,
            mine = false,
            senderName = "Ana",
            sentAt = PREVIEW_NOW,
        ),
        FileItem(
            "f2",
            "h7",
            null,
            null,
            "audio/aac",
            null,
            voice = true,
            voiceDurationMs = 12_000,
            held = true,
            flagged = false,
            mine = true,
            senderName = "You",
            sentAt = PREVIEW_NOW - DAY_MS,
        ),
        FileItem(
            "f3",
            "h8",
            null,
            "photos.zip",
            "application/zip",
            7_340_032,
            voice = false,
            held = false,
            flagged = false,
            mine = false,
            senderName = "Ana",
            sentAt =
                PREVIEW_NOW - 35 * DAY_MS,
        ),
    )

private val previewLinks =
    listOf(
        LinkItem(
            key = "l1#0",
            messageId = "l1",
            url = "https://getknit.app/blog/mesh",
            card =
                LinkCard(
                    "https://getknit.app/blog/mesh",
                    "getknit.app",
                    "How Knit carries a message with no signal",
                    null,
                    hasImage = false,
                ),
            cardHash = "h9",
            mine = false,
            senderName = "Ana",
            sentAt = PREVIEW_NOW,
        ),
        LinkItem(
            key = "l2#0",
            messageId = "l2",
            url = "https://example.org/a/very/long/path?with=query",
            mine = true,
            senderName = "You",
            sentAt =
                PREVIEW_NOW - 33 * DAY_MS,
        ),
    )

private const val DAY_MS = 24L * 60 * 60 * 1000

@Preview(showBackground = true)
@Composable
fun ChatMediaScreenMediaPreview() =
    KnitPreview {
        ChatMediaScreenContent(state = ChatMediaUiState(loaded = true, media = previewMedia))
    }

@Preview(showBackground = true)
@Composable
fun ChatMediaScreenFilesPreview() =
    KnitPreview {
        ChatMediaScreenContent(state = ChatMediaUiState(tab = MediaTab.Files, loaded = true, files = previewFiles))
    }

@Preview(showBackground = true)
@Composable
fun ChatMediaScreenLinksPreview() =
    KnitPreview {
        ChatMediaScreenContent(state = ChatMediaUiState(tab = MediaTab.Links, loaded = true, links = previewLinks))
    }

@Preview(showBackground = true)
@Composable
fun ChatMediaScreenEmptyPreview() =
    KnitPreview {
        ChatMediaScreenContent(state = ChatMediaUiState(loaded = true))
    }
