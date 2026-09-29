package app.getknit.knit.ui.profile

import android.content.ClipData
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.mesh.IntroState
import app.getknit.knit.ui.Reach
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.DetailCard
import app.getknit.knit.ui.components.DetailRow
import app.getknit.knit.ui.components.FullscreenImageViewer
import app.getknit.knit.ui.components.GroupAvatar
import app.getknit.knit.ui.components.PeerNameText
import app.getknit.knit.ui.components.SectionHeader
import app.getknit.knit.ui.image.BlobImage
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.scan.QrScanner
import app.getknit.knit.ui.theme.knitColors
import app.getknit.knit.ui.verify.EncryptionSection
import app.getknit.knit.ui.verify.PeerVerification
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** How faded the presence dot draws for a relay-reachable peer — the same as a Diagnostics relay row. */
private const val RELAY_DOT_ALPHA = 0.45f

/** Groups a remove dialog names outright; the rest collapse into "and N more groups". */
private const val NAMED_GROUPS_MAX = 3

/**
 * Read-only "contact details" view of another peer (keyed by [nodeId]): avatar, display name, live
 * presence (online / reachable via relay / offline), free-text status, node id, and end-to-end key
 * verification (safety number + QR scan). Offers a Message action (accepts any pending request from this
 * peer, then opens/starts a DM via [onMessage]), and Remove contact and Block/Unblock in the overflow menu.
 * Reached by tapping a peer's avatar in a chat, or a sender's avatar in the Message Requests inbox.
 * [onRemoved] runs once a confirmed removal has finished writing — the DM it deleted may be the screen below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDetailsScreen(
    nodeId: String,
    onBack: () -> Unit,
    onMessage: (nodeId: String) -> Unit,
    onOpenGroup: (groupId: String) -> Unit,
    onRemoved: () -> Unit,
    viewModel: ProfileDetailsViewModel = koinViewModel { parametersOf(nodeId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scanResult by viewModel.scanResult.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Outside the scanner branch below, so a removal that lands while the scanner is up still closes.
    LaunchedEffect(Unit) { viewModel.removed.collect { onRemoved() } }

    val matchMessage = stringResource(R.string.verify_match)
    val mismatchMessage = stringResource(R.string.verify_mismatch)
    LaunchedEffect(scanResult) {
        when (scanResult) {
            VerifyScanResult.MATCH -> snackbarHostState.showSnackbar(matchMessage)
            VerifyScanResult.MISMATCH -> snackbarHostState.showSnackbar(mismatchMessage)
            null -> Unit
        }
        if (scanResult != null) viewModel.consumeScanResult()
    }

    // The scanner takes over the whole screen rather than launching an Activity — see [QrScanner].
    var scanning by remember { mutableStateOf(false) }
    if (scanning) {
        QrScanner(
            onResult = {
                scanning = false
                viewModel.onScanned(it)
            },
            onCancel = { scanning = false },
        )
    } else {
        ProfileDetailsScreenContent(
            state = state,
            snackbarHostState = snackbarHostState,
            onBack = onBack,
            // Tapping Message accepts any pending request from this peer (idempotent) before opening the
            // DM, so answering a request from the sender's profile behaves like accepting it in the inbox.
            onMessage = { id ->
                viewModel.accept()
                onMessage(id)
            },
            onOpenGroup = onOpenGroup,
            onScan = { scanning = true },
            onBlock = viewModel::block,
            onUnblock = viewModel::unblock,
            onRemoveContact = viewModel::removeContact,
            onMarkVerified = viewModel::markVerified,
            onClearVerification = viewModel::clearVerification,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileDetailsScreenContent(
    state: ProfileDetailsUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onMessage: (nodeId: String) -> Unit,
    onOpenGroup: (groupId: String) -> Unit,
    onScan: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onRemoveContact: () -> Unit,
    onMarkVerified: () -> Unit,
    onClearVerification: () -> Unit,
    // Whether the LoRa plane is introduced at all in this build — a peer on a debug build can name a board
    // in a profile a release build has no radio for, and a line about a feature that isn't there is noise.
    // A parameter rather than a bare BuildConfig read so the hidden case is previewable and testable.
    showLoraRadio: Boolean = BuildConfig.LORA_PLANE,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showAvatarFullscreen by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    // A standing that drops out from under an open dialog (they were blocked, or removed from elsewhere)
    // closes it, rather than leaving the flag set to reopen it the next time Remove is offered.
    LaunchedEffect(state.removal) { if (state.removal == ContactRemoval.NotOffered) showRemove = false }

    Scaffold(
        modifier = Modifier.testTag("screen_profile_details"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                // The person's name, not the word "Profile". The screen is about one contact, and the
                // name is already the first thing the body repeats at display size.
                title = {
                    Text(
                        text = state.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.chat_more_options),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (state.removal != ContactRemoval.NotOffered) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.profile_details_remove_contact)) },
                                    leadingIcon = { Icon(Icons.Filled.PersonRemove, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        showRemove = true
                                    },
                                    modifier = Modifier.testTag("profile_details_remove_contact"),
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (state.isBlocked) {
                                                R.string.chat_action_unblock
                                            } else {
                                                R.string.chat_action_block
                                            },
                                        ),
                                    )
                                },
                                leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    if (state.isBlocked) onUnblock() else onBlock()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Who this is, and the handful of facts a reader came to check — kept together and centred, so
            // the technical rows below can be a left-aligned list rather than more centred sentences.
            PeerHeader(
                state = state,
                onViewPhoto = { showAvatarFullscreen = true },
            )

            // The reason you opened this screen. It used to sit tenth, under four lines of metadata, as a
            // 48dp icon button that read as weaker than the text above it.
            Button(
                onClick = { onMessage(state.nodeId) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("profile_details_message"),
            ) {
                Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.profile_details_message))
            }

            InCommonSection(state.inCommon.groups, onOpenGroup = onOpenGroup)
            DetailsSection(state = state, showLoraRadio = showLoraRadio, snackbarHostState = snackbarHostState)

            // The heading is drawn here, in the same register as "In common" and "Details", rather than
            // inside EncryptionSection — whose own centred one was the only heading on the screen that
            // looked like a different kind of thing.
            SectionHeader(stringResource(R.string.verify_section_title))
            EncryptionSection(
                myQrPayload = state.myQrPayload,
                peer =
                    PeerVerification(
                        displayName = state.displayName,
                        hasKey = state.hasKey,
                        verified = state.verified,
                        safetyNumber = state.safetyNumber,
                    ),
                onScan = onScan,
                onMarkVerified = onMarkVerified,
                onClearVerification = onClearVerification,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    if (showAvatarFullscreen && state.avatarHash != null) {
        FullscreenImageViewer(
            model = BlobImage(state.avatarHash),
            contentDescription = stringResource(R.string.chat_image_viewer_desc),
            title = state.displayName,
            onDismiss = { showAvatarFullscreen = false },
        )
    }

    if (showRemove) {
        RemoveContactDialog(
            name = state.displayName,
            removal = state.removal,
            onConfirm = {
                showRemove = false
                onRemoveContact()
            },
            onDismiss = { showRemove = false },
        )
    }
}

/**
 * The confirm behind "Remove contact" (ADR 2026-09.adgd). It says what goes — the chat, from this phone only —
 * that the person is not told, and where their next message lands; and it names the groups that keep them a
 * contact anyway, since only their own leave changes a roster. For a contact held only by groups there is
 * nothing to remove, so the dialog explains that and offers OK alone.
 */
@Composable
private fun RemoveContactDialog(
    name: String,
    removal: ContactRemoval,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (removal) {
        // Nothing to confirm: the screen shows this dialog only while Remove is offered.
        ContactRemoval.NotOffered -> {}

        is ContactRemoval.Removes -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.profile_details_remove_title, name)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (removal.keptBy.isEmpty()) {
                            Text(stringResource(R.string.profile_details_remove_body, name))
                        } else {
                            Text(stringResource(R.string.profile_details_remove_body_kept, name))
                            GroupTitles(removal.keptBy)
                        }
                        if (removal.clearsVerification) Text(stringResource(R.string.profile_details_remove_verified))
                    }
                },
                confirmButton = {
                    TextButton(onClick = onConfirm, modifier = Modifier.testTag("profile_details_remove_confirm")) {
                        Text(
                            text = stringResource(R.string.profile_details_remove_action),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }

        is ContactRemoval.GroupsOnly -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.profile_details_remove_kept_title, name)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.profile_details_remove_kept_body, name))
                        GroupTitles(removal.groups)
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
                },
            )
        }
    }
}

/** The groups a remove dialog names, one per line, the ones past [NAMED_GROUPS_MAX] folded into a count. */
@Composable
private fun GroupTitles(groups: List<SharedGroup>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        groups.take(NAMED_GROUPS_MAX).forEach { group ->
            Text(
                text = group.title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val more = groups.size - NAMED_GROUPS_MAX
        if (more > 0) Text(pluralStringResource(R.plurals.profile_details_remove_more_groups, more, more))
    }
}

/**
 * The avatar, the name, the badge row and their status — everything a reader checks before deciding
 * whether to write. Centred, because it is a portrait; everything below it is a list.
 */
@Composable
private fun PeerHeader(
    state: ProfileDetailsUiState,
    onViewPhoto: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Avatar(
            avatarHash = state.avatarHash,
            name = state.displayName,
            nodeId = state.nodeId,
            size = 96.dp,
            textStyle = MaterialTheme.typography.displaySmall,
            // Tappable only when a photo is set: a default (initials) avatar has nothing to enlarge,
            // so onClick stays null and Avatar renders non-interactive (no ripple / no touch target).
            contentDescription = if (state.avatarHash != null) state.displayName else null,
            onClickLabel = stringResource(R.string.profile_details_view_photo),
            onClick = if (state.avatarHash != null) onViewPhoto else null,
        )

        PeerNameText(
            text = state.displayName,
            discriminator = state.discriminator,
            style = MaterialTheme.typography.titleLarge,
            maxLines = Int.MAX_VALUE,
        )

        // Four separate centred rows became one wrapping strip of pills. Each is present only when it has
        // something to say — the verified-badge shape the open-to-chat line already used — so an ordinary
        // contact shows one pill and a noteworthy one shows three.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Live presence. Three strengths of evidence, three dots, the same as Diagnostics' rows:
            // filled = a radio saw this peer itself; faded = something (a LoRa board, an Internet relay)
            // carried its traffic for us; muted = we only hold its profile.
            ProfileBadge(
                label =
                    stringResource(
                        when (state.reach) {
                            Reach.Direct -> R.string.profile_details_online
                            Reach.Relay -> R.string.profile_details_via_relay
                            Reach.Known -> R.string.profile_details_offline
                        },
                    ),
                modifier = Modifier.testTag("profile_details_presence"),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (state.reach) {
                                    Reach.Direct -> MaterialTheme.knitColors.positive
                                    Reach.Relay -> MaterialTheme.knitColors.positive.copy(alpha = RELAY_DOT_ALPHA)
                                    Reach.Known -> MaterialTheme.colorScheme.outline
                                },
                            ),
                )
            }

            // Verification, which until now a reader had to scroll to the foot of the screen to learn.
            // Shown only when verified: "Not verified" is the ordinary case, and the Encryption section
            // below still names it for anyone who looks.
            if (state.verified) {
                ProfileBadge(
                    label = stringResource(R.string.verify_verified),
                    // The same shield the DM header and a signed room post wear, so one mark means
                    // "verified" everywhere in the app.
                    icon = Icons.Filled.VerifiedUser,
                    iconTint = MaterialTheme.knitColors.positive,
                    modifier = Modifier.testTag("profile_details_verified"),
                )
            }

            // Their declared availability. Shown whether or not they are online — it is what they declared.
            if (state.openToChat) {
                ProfileBadge(
                    label = stringResource(R.string.profile_details_open_to_chat),
                    icon = Icons.Outlined.ChatBubbleOutline,
                    iconTint = MaterialTheme.knitColors.positive,
                    modifier = Modifier.testTag("profile_details_open_to_chat"),
                )
            }

            // Blocked was invisible on this screen before — the only tell was the overflow item reading
            // "Unblock user", which you had to open the menu to see.
            if (state.isBlocked) {
                ProfileBadge(
                    label = stringResource(R.string.profile_details_blocked),
                    icon = Icons.Filled.Block,
                    iconTint = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("profile_details_blocked"),
                )
            }
        }

        if (state.status.isNotBlank()) {
            Text(
                text = state.status,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        // A contact added from a link: where the intro handshake stands (docs/CONTACT_CARD.md).
        state.intro?.let { intro ->
            Text(
                text =
                    stringResource(
                        when (intro) {
                            IntroState.AWAITING_PREKEY -> R.string.intro_status_awaiting_key
                            IntroState.SENT -> R.string.intro_status_waiting
                            IntroState.CONNECTED -> R.string.intro_status_connected
                        },
                        state.displayName,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("profile_intro_status"),
            )
        }
    }
}

/**
 * One fact about a peer as a pill: a dot or an icon, then a word. [contentColor] defaults to `onSurface`
 * rather than tracking [iconTint] so the label keeps its contrast against the container — the colour is
 * the mark's job, not the text's — and only the blocked badge, which is a warning, overrides it.
 */
@Composable
private fun ProfileBadge(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    leading: @Composable (() -> Unit)? = null,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> {
                    leading()
                }

                icon != null -> {
                    Icon(
                        imageVector = icon,
                        // Decorative: the label beside it carries the meaning.
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = contentColor)
        }
    }
}

/**
 * The groups you are both in. Absent entirely when you share none — a contact added from a link and never
 * put in a group has no section here at all.
 *
 * The met stamps used to sit under this heading and moved to Details: when your radios first saw each
 * other is a fact about the contact, not something the two of you share.
 */
@Composable
private fun InCommonSection(
    groups: List<SharedGroup>,
    onOpenGroup: (String) -> Unit,
) {
    if (groups.isEmpty()) return
    SectionHeader(stringResource(R.string.profile_details_section_in_common))
    DetailCard {
        // Each shared group is a row you can open, drawn with the avatar the chat list gives it — a
        // comma-joined line of names named the groups without showing them, and an unnamed group
        // contributed nothing but its comma.
        groups.forEach { group ->
            GroupRow(group = group, onClick = { onOpenGroup(group.groupId) })
        }
    }
}

/** A shared group, drawn like its row in the chat list and opening the same screen a tap there does. */
@Composable
private fun GroupRow(
    group: SharedGroup,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {}
                .testTag("profile_details_group_${group.groupId}")
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupAvatar(
            photoHash = group.photoHash,
            groupId = group.groupId,
            faces = group.faces,
            size = 40.dp,
            // Decorative: the row carries the accessible name, and tapping it does the same thing.
            contentDescription = null,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = group.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * When a radio of ours first and last saw a radio of theirs. Short-range evidence only, which is why these
 * are worded "met": a contact reached over LoRa or a spool may have no stamps at all, and the presence
 * badge stays the only claim about now (ADR 2026-09.2ajk).
 */
@Composable
private fun MetRows(inCommon: InCommon) {
    val context = LocalContext.current
    val first = inCommon.firstMetAt ?: return
    val firstText = formatMetDate(context, first)
    DetailRow(
        label = stringResource(R.string.profile_details_first_met),
        value = firstText,
        modifier = Modifier.testTag("profile_details_first_met"),
    )
    // Compare what the reader sees, not the stamps. A first encounter writes `firstMetAt` and `lastMetAt`
    // milliseconds apart, so an equality test on the longs still prints the same day twice.
    inCommon.lastMetAt
        ?.let { formatMetDate(context, it) }
        ?.takeIf { it != firstText }
        ?.let { lastText ->
            DetailRow(
                label = stringResource(R.string.profile_details_last_met),
                value = lastText,
                modifier = Modifier.testTag("profile_details_last_met"),
            )
        }
}

/**
 * An absolute date rather than "3 days ago": the met stamps are history, and a relative phrase next to a
 * live presence badge invites reading it as presence. Also keeps the screen settled — nothing here has to
 * re-tick.
 */
private fun formatMetDate(
    context: android.content.Context,
    epochMillis: Long,
): String = DateUtils.formatDateTime(context, epochMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)

/**
 * When you met, then who they are to the machine: the met stamps lead because they are the only rows here
 * a person reads for their own sake, and the alias, node id and claimed board are lookups.
 */
@Composable
private fun DetailsSection(
    state: ProfileDetailsUiState,
    showLoraRadio: Boolean,
    snackbarHostState: SnackbarHostState,
) {
    val inCommon = state.inCommon
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Android 13+ shows its own copy confirmation, so the snackbar only fires below it.
    val copiedMessage = stringResource(R.string.action_copied)
    val copyLabel = stringResource(R.string.action_copy)
    val aliasLabel = stringResource(R.string.profile_alias_label)
    val nodeIdLabel = stringResource(R.string.profile_node_id_label)

    fun copy(
        label: String,
        value: String,
    ) = scope.launch {
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value)))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbarHostState.showSnackbar(copiedMessage)
    }

    SectionHeader(stringResource(R.string.profile_details_section_details))
    DetailCard {
        MetRows(inCommon)
        DetailRow(
            label = aliasLabel,
            value = state.alias,
            modifier = Modifier.testTag("profile_details_alias"),
            copyLabel = copyLabel,
            onCopy = { copy(aliasLabel, state.alias) },
        )
        DetailRow(
            label = nodeIdLabel,
            value = state.nodeId,
            modifier = Modifier.testTag("profile_details_node_id"),
            copyLabel = copyLabel,
            onCopy = { copy(nodeIdLabel, state.nodeId) },
        )
        // The Meshtastic board they say they hold, in the `!hex` every radio client writes a node number in —
        // so it reads the same here as under a heard post in the radio room, which is what a reader who
        // followed "open their profile" from that room's caveat came to compare. A plain row, not a badge: the
        // claim is self-asserted, and only the room's shield is evidence.
        if (showLoraRadio) {
            state.loraNodeLabel?.let { board ->
                DetailRow(
                    label = stringResource(R.string.profile_details_lora_label),
                    value = board,
                    modifier = Modifier.testTag("profile_details_lora_node"),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ProfileDetailsScreenOnlineVerifiedPreview() =
    KnitPreview {
        ProfileDetailsScreenContent(
            state =
                ProfileDetailsUiState(
                    nodeId = "8f3a2b1c9d4e",
                    displayName = "Ada Lovelace",
                    status = "Hiking this weekend",
                    avatarHash = null,
                    reach = Reach.Direct,
                    isBlocked = false,
                    hasKey = true,
                    verified = true,
                    safetyNumber = "12345 67890 12345 67890 12345 67890",
                    myQrPayload = "knit:verify:ada",
                    openToChat = true,
                    loraNodeLabel = "!1234abcd",
                    inCommon =
                        InCommon(
                            groups =
                                listOf(
                                    SharedGroup("g-trail", "Trail Crew", photoHash = null, faces = PREVIEW_FACES),
                                    // An unnamed group: its title is generated from the members, never the blank column.
                                    SharedGroup("g-book", "Priya & Theo", photoHash = null, faces = PREVIEW_FACES),
                                ),
                            firstMetAt = PREVIEW_FIRST_MET,
                            lastMetAt = PREVIEW_LAST_MET,
                        ),
                    removal =
                        ContactRemoval.Removes(
                            keptBy = listOf(SharedGroup("g-trail", "Trail Crew", photoHash = null, faces = PREVIEW_FACES)),
                            clearsVerification = true,
                        ),
                ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onMessage = {},
            onOpenGroup = {},
            onScan = {},
            onBlock = {},
            onUnblock = {},
            onRemoveContact = {},
            onMarkVerified = {},
            onClearVerification = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun ProfileDetailsScreenViaRelayPreview() =
    KnitPreview {
        ProfileDetailsScreenContent(
            state =
                ProfileDetailsUiState(
                    nodeId = "a1b2c3d4e5f6",
                    displayName = "Grace Hopper",
                    status = "",
                    avatarHash = null,
                    reach = Reach.Relay,
                    isBlocked = false,
                    hasKey = true,
                    verified = false,
                    safetyNumber = "98765 43210 98765 43210 98765 43210",
                    myQrPayload = "knit:verify:grace",
                ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onMessage = {},
            onOpenGroup = {},
            onScan = {},
            onBlock = {},
            onUnblock = {},
            onRemoveContact = {},
            onMarkVerified = {},
            onClearVerification = {},
        )
    }

// Exercises the hasKey = false branch: no safety number / QR, just the "no key yet" notice.
@Preview(showBackground = true)
@Composable
fun ProfileDetailsScreenNoKeyPreview() =
    KnitPreview {
        ProfileDetailsScreenContent(
            state =
                ProfileDetailsUiState(
                    nodeId = "b2c3d4e5f6a1",
                    displayName = "Edsger Dijkstra",
                    status = "",
                    avatarHash = null,
                    reach = Reach.Known,
                    isBlocked = false,
                    hasKey = false,
                    verified = false,
                    safetyNumber = null,
                    myQrPayload = null,
                ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onMessage = {},
            onOpenGroup = {},
            onScan = {},
            onBlock = {},
            onUnblock = {},
            onRemoveContact = {},
            onMarkVerified = {},
            onClearVerification = {},
        )
    }

// A blocked contact — a state the screen used to show only as a flipped overflow label — with nothing in
// common, so the "In common" section is absent entirely.
@Preview(showBackground = true)
@Composable
fun ProfileDetailsScreenBlockedPreview() =
    KnitPreview {
        ProfileDetailsScreenContent(
            state =
                ProfileDetailsUiState(
                    nodeId = "c3d4e5f6a1b2",
                    displayName = "Alan Turing",
                    status = "",
                    avatarHash = null,
                    reach = Reach.Known,
                    isBlocked = true,
                    hasKey = true,
                    verified = false,
                    safetyNumber = "11111 22222 33333 44444 55555 66666",
                    myQrPayload = "knit:verify:alan",
                ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onMessage = {},
            onOpenGroup = {},
            onScan = {},
            onBlock = {},
            onUnblock = {},
            onRemoveContact = {},
            onMarkVerified = {},
            onClearVerification = {},
        )
    }

// The confirm for a verified contact a shared group keeps: the chat goes, the group is named, the check resets.
@Preview(showBackground = true)
@Composable
fun RemoveContactDialogPreview() =
    KnitPreview {
        RemoveContactDialog(
            name = "Ada Lovelace",
            removal =
                ContactRemoval.Removes(
                    keptBy = listOf(SharedGroup("g-trail", "Trail Crew", photoHash = null, faces = PREVIEW_FACES)),
                    clearsVerification = true,
                ),
            onConfirm = {},
            onDismiss = {},
        )
    }

private val PREVIEW_FACES =
    listOf("Sam", "Priya", "Theo").mapIndexed { i, name -> GroupFace(nodeId = "m$i", name = name, avatarHash = null) }

// Fixed stamps so the met rows render the same date on every preview refresh.
private const val PREVIEW_FIRST_MET = 1_755_000_000_000L
private const val PREVIEW_LAST_MET = 1_757_900_000_000L
