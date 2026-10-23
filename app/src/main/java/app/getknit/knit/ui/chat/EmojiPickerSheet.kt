package app.getknit.knit.ui.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.EmojiFlags
import androidx.compose.material.icons.outlined.EmojiFoodBeverage
import androidx.compose.material.icons.outlined.EmojiNature
import androidx.compose.material.icons.outlined.EmojiObjects
import androidx.compose.material.icons.outlined.EmojiPeople
import androidx.compose.material.icons.outlined.EmojiSymbols
import androidx.compose.material.icons.outlined.EmojiTransportation
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.EmojiSupportMatch
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.data.emoji.EmojiCatalog
import app.getknit.knit.data.emoji.EmojiCatalogLoader
import app.getknit.knit.data.emoji.EmojiEntry
import app.getknit.knit.data.emoji.EmojiGroup
import app.getknit.knit.ui.components.noAutofillMenu
import app.getknit.knit.ui.components.skeletonBlockColor
import app.getknit.knit.ui.components.skeletonPulseAlphaState
import app.getknit.knit.ui.preview.KnitPreview
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The "more reactions" sheet: every emoji this device can draw, browsable by Unicode group behind sticky
 * headers and a tab strip, or filtered by name. Opened from the long-press menu's "+" (see `ReactionPicker`);
 * picking an emoji reacts and dismisses. Hosted at `ChatScreenContent` level rather than inside the
 * message row, because a sheet composed in a lazy item dies the moment its row scrolls off. The catalog is
 * parsed on first open (a skeleton meanwhile) and cached by the injected [loader] for the process.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmojiPickerSheet(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    loader: EmojiCatalogLoader = koinInject(),
) {
    val catalog by produceState<EmojiCatalog?>(initialValue = null, loader) { value = loader.load() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("emoji_sheet"),
    ) {
        EmojiPickerSheetContent(catalog = catalog, onPick = onPick)
    }
}

/**
 * The sheet's body, stateless apart from the search draft: a skeleton while [catalog] is null, the
 * search results (or an empty state) while a query is typed, else the grouped grid with its tabs.
 * [initialQuery] exists for the previews.
 */
@Composable
internal fun EmojiPickerSheetContent(
    catalog: EmojiCatalog?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val gridState = rememberLazyListState()
    val glyphs = rememberEmojiGlyphs(onPick)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .fillMaxHeight(SHEET_HEIGHT_FRACTION)
                .imePadding(),
    ) {
        EmojiSearchField(query = query, onQueryChange = { query = it })
        when {
            catalog == null -> {
                EmojiGridSkeleton()
            }

            query.isNotBlank() -> {
                val results = remember(catalog, query) { catalog.search(query) }
                if (results.isEmpty()) {
                    EmojiEmptyState(stringResource(R.string.emoji_search_empty, query.trim()))
                } else {
                    EmojiSearchGrid(results = results, glyphs = glyphs, onPick = onPick)
                }
            }

            catalog.browse.isEmpty() -> {
                EmojiEmptyState(stringResource(R.string.emoji_empty_catalog))
            }

            else -> {
                EmojiBrowse(catalog = catalog, listState = gridState, glyphs = glyphs, onPick = onPick)
            }
        }
    }
}

@Composable
private fun EmojiSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.emoji_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon =
            if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.testTag("emoji_search_clear")) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.emoji_search_clear))
                    }
                }
            } else {
                null
            },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("emoji_search")
                .noAutofillMenu(),
    )
}

/** One row of the picker: a header, or up to `columns` emoji laid out and drawn as a unit. */
private sealed interface PickerItem {
    val key: String

    class Header(
        val group: EmojiGroup,
    ) : PickerItem {
        override val key: String = HEADER_KEY_PREFIX + group.name
    }

    class EmojiRow(
        val entries: List<EmojiEntry>,
    ) : PickerItem {
        override val key: String = entries.first().emoji
    }
}

/**
 * The grouped grid plus its tab strip: tabs jump to a group's header, and follow the scroll. Rows, not
 * cells, are the lazy items (see [EmojiRow]): the column count comes from the width, like an adaptive grid.
 */
@Composable
private fun EmojiBrowse(
    catalog: EmojiCatalog,
    listState: LazyListState,
    glyphs: EmojiGlyphs,
    onPick: (String) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = (maxWidth / CELL_SIZE).toInt().coerceAtLeast(1)
        val items =
            remember(catalog, columns) {
                buildList {
                    EmojiGroup.entries.forEach { group ->
                        val entries = catalog.byGroup[group] ?: return@forEach
                        add(PickerItem.Header(group))
                        entries.chunked(columns).forEach { add(PickerItem.EmojiRow(it)) }
                    }
                }
            }
        val groups = remember(items) { items.filterIsInstance<PickerItem.Header>().map { it.group } }
        // Each group's header index in the list — the tab-jump target and the scroll→tab map.
        val headerIndex =
            remember(items) {
                items.withIndex().filter { it.value is PickerItem.Header }.associate {
                    (it.value as PickerItem.Header).group to
                        it.index
                }
            }
        val scope = rememberCoroutineScope()
        Column(modifier = Modifier.fillMaxSize()) {
            EmojiGroupTabs(
                groups = groups,
                headerIndex = headerIndex,
                listState = listState,
                // An instant jump, not animateScrollToItem: the animated form scrolls up to 2,500 dp of content
                // before it teleports, composing ~4 sheet-heights per tap (measured: a 1.3 s frame).
                onSelect = { group -> scope.launch { listState.scrollToItem(headerIndex.getValue(group)) } },
            )
            EmojiList(items = items, columns = columns, listState = listState, glyphs = glyphs, onPick = onPick)
        }
    }
}

@Composable
private fun EmojiSearchGrid(
    results: List<EmojiEntry>,
    glyphs: EmojiGlyphs,
    onPick: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = (maxWidth / CELL_SIZE).toInt().coerceAtLeast(1)
        val items = remember(results, columns) { results.chunked(columns).map { PickerItem.EmojiRow(it) } }
        EmojiList(items = items, columns = columns, listState = listState, glyphs = glyphs, onPick = onPick)
    }
}

@Composable
private fun EmojiList(
    items: List<PickerItem>,
    columns: Int,
    listState: LazyListState,
    glyphs: EmojiGlyphs,
    onPick: (String) -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
        modifier =
            Modifier
                .fillMaxSize()
                .pickOnTap(listState, items, columns, LocalLayoutDirection.current == LayoutDirection.Rtl, onPick)
                .testTag("emoji_grid"),
    ) {
        items.forEach { item ->
            when (item) {
                is PickerItem.Header -> stickyHeader(key = item.key) { EmojiGroupHeader(item.group) }
                is PickerItem.EmojiRow -> item(key = item.key) { EmojiRow(item.entries, columns, glyphs) }
            }
        }
    }
}

/**
 * One tap detector for the whole list instead of a `clickable` per cell: the tapped row is looked up in the
 * list's layout info and the column from the x position. Every modifier node on a cell would be paid ~120
 * times per screen and ~9 times per new row while flinging; this keeps a cell at one semantics node, which
 * still carries the TalkBack click. A row's first emoji sits at the layout's start, on the right when [rtl].
 */
private fun Modifier.pickOnTap(
    listState: LazyListState,
    items: List<PickerItem>,
    columns: Int,
    rtl: Boolean,
    onPick: (String) -> Unit,
): Modifier =
    pointerInput(listState, items, columns, rtl, onPick) {
        detectTapGestures { pos ->
            val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull { pos.y >= it.offset && pos.y < it.offset + it.size }
            val row = hit?.let { items.getOrNull(it.index) } as? PickerItem.EmojiRow ?: return@detectTapGestures
            val col = columnAt(pos.x, size.width.toFloat(), columns, rtl)
            row.entries.getOrNull(col)?.let { onPick(it.emoji) }
        }
    }

/** The column a tap [x] pixels from the grid's left edge lands in, counted from the layout's start. */
internal fun columnAt(
    x: Float,
    width: Float,
    columns: Int,
    rtl: Boolean,
): Int {
    val fromLeft = (x / (width / columns)).toInt().coerceIn(0, columns - 1)
    return if (rtl) columns - 1 - fromLeft else fromLeft
}

/**
 * A row of emoji as **one** lazy item: one draw pass painting every glyph from the shared [EmojiGlyphs]
 * cache, plus a childless semantics box per emoji so accessibility and tests still see each cell (its CLDR
 * name, a button role, the click, the test tag). A `Text` per cell cost ~1.4 ms each on a Pixel 9 debug
 * build — ~200 ms for the first screen and 13–20 ms per new row while flinging, past the 8 ms a 120 Hz
 * frame allows; a row of nine is a small fraction of that.
 */
@Composable
private fun EmojiRow(
    entries: List<EmojiEntry>,
    columns: Int,
    glyphs: EmojiGlyphs,
) {
    val labels = entries.map { stringResource(R.string.chat_react_with, it.name) }
    Layout(
        content = {
            entries.forEachIndexed { i, entry ->
                val label = labels[i]
                Box(
                    modifier =
                        Modifier.semantics {
                            contentDescription = label
                            role = Role.Button
                            testTag = "emoji_cell_${entry.emoji}"
                            onClick {
                                glyphs.onPick(entry.emoji)
                                true
                            }
                        },
                )
            }
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .height(CELL_SIZE)
                .drawBehind {
                    val cellWidth = size.width / columns
                    entries.forEachIndexed { i, entry ->
                        val layout = glyphs.layout(entry.emoji)
                        // The cell placeRelative puts below: the start's first, on the right in a right-to-left layout.
                        val cell = if (layoutDirection == LayoutDirection.Rtl) columns - 1 - i else i
                        val x = cell * cellWidth + (cellWidth - layout.size.width) / 2
                        val y = (size.height - layout.size.height) / 2
                        drawText(layout, topLeft = Offset(x, y))
                    }
                },
    ) { measurables, constraints ->
        val cellWidth = constraints.maxWidth / columns
        val cellHeight = CELL_SIZE.roundToPx()
        val placeables = measurables.map { it.measure(Constraints.fixed(cellWidth, cellHeight)) }
        layout(constraints.maxWidth, cellHeight) {
            placeables.forEachIndexed { i, placeable -> placeable.placeRelative(i * cellWidth, 0) }
        }
    }
}

/**
 * The per-emoji text layouts the rows draw from, measured once per emoji per process and shared by every
 * row — a fling then re-draws cached layouts instead of measuring nine texts per new row. Also carries the
 * pick callback so a row's semantics closures capture one stable object.
 */
private class EmojiGlyphs(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    val onPick: (String) -> Unit,
) {
    private val cache = HashMap<String, TextLayoutResult>()

    fun layout(emoji: String): TextLayoutResult = cache.getOrPut(emoji) { measurer.measure(emoji, style) }
}

@Composable
private fun rememberEmojiGlyphs(onPick: (String) -> Unit): EmojiGlyphs {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val style = cellTextStyle()
    return remember(measurer, style, onPick) { EmojiGlyphs(measurer, style, onPick) }
}

/**
 * Hand-rolled rather than `PrimaryScrollableTabRow`, whose 90 dp minimum tab width would push nine icon
 * tabs to 810 dp; nine 48 dp targets scroll comfortably on a phone. The selected tab is derived from the
 * grid's scroll position **here**, in the tab row's own scope: reading it in the grid's parent recomposed
 * the whole visible grid every time the scroll crossed a group header (measured: 164 recompositions in one
 * fling frame).
 */
@Composable
private fun EmojiGroupTabs(
    groups: List<EmojiGroup>,
    headerIndex: Map<EmojiGroup, Int>,
    listState: LazyListState,
    onSelect: (EmojiGroup) -> Unit,
) {
    val selected by remember(headerIndex) {
        derivedStateOf {
            val first = listState.firstVisibleItemIndex
            groups.lastOrNull { headerIndex.getValue(it) <= first } ?: groups.first()
        }
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
                .selectableGroup(),
        horizontalArrangement = Arrangement.Center,
    ) {
        groups.forEach { group ->
            val label = stringResource(group.labelRes())
            val isSelected = group == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(group) })
                        .minimumInteractiveComponentSize()
                        .semantics { contentDescription = label }
                        .testTag("emoji_group_${group.name}"),
            ) {
                Icon(
                    imageVector = group.icon(),
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiGroupHeader(group: EmojiGroup) {
    // Opaque, so cells scrolling underneath a stuck header don't bleed through it.
    Surface(color = BottomSheetDefaults.ContainerColor, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(group.labelRes()),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .semantics { heading() },
        )
    }
}

@Composable
private fun cellTextStyle(): TextStyle {
    val base = MaterialTheme.typography.headlineSmall
    val color = MaterialTheme.colorScheme.onSurface
    return remember(base, color) {
        base.copy(
            color = color,
            textAlign = TextAlign.Center,
            platformStyle = PlatformTextStyle(emojiSupportMatch = EmojiSupportMatch.None),
        )
    }
}

@Composable
private fun EmojiEmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("emoji_search_empty"),
        )
    }
}

/**
 * Placeholder while the catalog parses on first open — transient, so the infinite pulse is allowed. One
 * draw node painting every dot, with the pulse read inside the draw lambda: a `Box` per dot recomposed 49
 * scopes and re-laid-out on every animation frame (measured: 26–33 ms frames through the sheet's slide-in).
 */
@Composable
private fun EmojiGridSkeleton() {
    val pulse = skeletonPulseAlphaState("emojiCatalog")
    val onSurface = MaterialTheme.colorScheme.onSurface
    val label = stringResource(R.string.emoji_loading)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = label }
                .testTag("emoji_loading")
                .drawBehind {
                    val cell = CELL_SIZE.toPx()
                    val radius = SKELETON_DOT.toPx() / 2
                    val columns = (size.width / cell).toInt().coerceAtLeast(1)
                    val rows = (size.height / cell).toInt() + 1
                    val color = skeletonBlockColor(onSurface, pulse.value)
                    for (row in 0 until rows) {
                        for (col in 0 until columns) {
                            drawCircle(color, radius, Offset(col * cell + cell / 2, row * cell + cell / 2))
                        }
                    }
                },
    )
}

private fun EmojiGroup.icon(): ImageVector =
    when (this) {
        EmojiGroup.SMILEYS -> Icons.Outlined.EmojiEmotions
        EmojiGroup.PEOPLE -> Icons.Outlined.EmojiPeople
        EmojiGroup.ANIMALS -> Icons.Outlined.EmojiNature
        EmojiGroup.FOOD -> Icons.Outlined.EmojiFoodBeverage
        EmojiGroup.TRAVEL -> Icons.Outlined.EmojiTransportation
        EmojiGroup.ACTIVITIES -> Icons.Outlined.EmojiEvents
        EmojiGroup.OBJECTS -> Icons.Outlined.EmojiObjects
        EmojiGroup.SYMBOLS -> Icons.Outlined.EmojiSymbols
        EmojiGroup.FLAGS -> Icons.Outlined.EmojiFlags
    }

private fun EmojiGroup.labelRes(): Int =
    when (this) {
        EmojiGroup.SMILEYS -> R.string.emoji_group_smileys
        EmojiGroup.PEOPLE -> R.string.emoji_group_people
        EmojiGroup.ANIMALS -> R.string.emoji_group_animals
        EmojiGroup.FOOD -> R.string.emoji_group_food
        EmojiGroup.TRAVEL -> R.string.emoji_group_travel
        EmojiGroup.ACTIVITIES -> R.string.emoji_group_activities
        EmojiGroup.OBJECTS -> R.string.emoji_group_objects
        EmojiGroup.SYMBOLS -> R.string.emoji_group_symbols
        EmojiGroup.FLAGS -> R.string.emoji_group_flags
    }

private val CELL_SIZE = 48.dp
private val SKELETON_DOT = 32.dp
private const val HEADER_KEY_PREFIX = "h_"
private const val SHEET_HEIGHT_FRACTION = 0.8f

/** A small three-group catalog for the previews. */
private fun previewCatalog(): EmojiCatalog =
    EmojiCatalog(
        listOf(
            EmojiEntry("😀", EmojiGroup.SMILEYS, "grinning face", toneVariant = false),
            EmojiEntry("😂", EmojiGroup.SMILEYS, "face with tears of joy", toneVariant = false),
            EmojiEntry("❤️", EmojiGroup.SMILEYS, "red heart", toneVariant = false),
            EmojiEntry("👍", EmojiGroup.PEOPLE, "thumbs up", toneVariant = false),
            EmojiEntry("👍🏽", EmojiGroup.PEOPLE, "thumbs up: medium skin tone", toneVariant = true),
            EmojiEntry("🙏", EmojiGroup.PEOPLE, "folded hands", toneVariant = false),
            EmojiEntry("🦄", EmojiGroup.ANIMALS, "unicorn", toneVariant = false),
            EmojiEntry("🐙", EmojiGroup.ANIMALS, "octopus", toneVariant = false),
        ),
    )

@Preview(showBackground = true)
@Composable
fun EmojiPickerSheetPreview() =
    KnitPreview {
        EmojiPickerSheetContent(catalog = previewCatalog(), onPick = {})
    }

@Preview(showBackground = true)
@Composable
fun EmojiPickerSheetLoadingPreview() =
    KnitPreview {
        EmojiPickerSheetContent(catalog = null, onPick = {})
    }

@Preview(showBackground = true)
@Composable
fun EmojiPickerSheetEmptySearchPreview() =
    KnitPreview {
        EmojiPickerSheetContent(catalog = previewCatalog(), onPick = {}, initialQuery = "zzz")
    }
