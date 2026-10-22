package app.getknit.knit.ui.chat

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.location.GeoPoint
import app.getknit.knit.location.GeoUri
import app.getknit.knit.location.LocationFix
import app.getknit.knit.location.LocationPrecision
import app.getknit.knit.ui.preview.KnitPreview
import java.util.Date

/**
 * A shared position inside a bubble, drawn in place of the `geo:` line the body carried: the coordinates,
 * the radius the sender's phone claimed, and two things to do with it — tap the card to hand it to a maps
 * app, or copy the coordinates for one that wants them typed. Two nodes for TalkBack, on purpose: the card
 * ("Location: 37.42…, accurate to 12 m", a button) and the copy button beside it. Long-press reaches the
 * bubble's reaction picker like every other attachment.
 *
 * No map tile: the phone that draws this has, as a rule, no Internet. A maps app with offline maps is the
 * right place for the picture, and the `geo:` intent is the standard way to reach whichever one is installed.
 */
@Composable
fun LocationCard(
    point: GeoPoint,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coordinates = GeoUri.coordinates(point)
    val accuracy = accuracyLine(point.accuracyM, coarse = false)
    val description = stringResource(R.string.chat_location_card_desc, coordinates, accuracy)
    // The card paints its own container, so it has to carry the matching content colour too. Without
    // this the ripple and the "Location" label inherit the *bubble's* content colour — which on an
    // outgoing message is onPrimaryContainer, a role belonging to a surface this card doesn't draw.
    // CoordinatesLine already spelled onSurface out by hand; this is the same answer for the rest of it.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Row(
            modifier =
                modifier
                    .padding(vertical = 2.dp)
                    .width(LINK_CARD_WIDTH)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClickLabel = stringResource(R.string.chat_location_open_maps),
                            onClick = onOpen,
                            onLongClick = onLongClick,
                        )
                        // After the clickable, so the action survives and the texts merge into one sentence.
                        .clearAndSetSemantics {
                            contentDescription = description
                            role = Role.Button
                            testTag = "chat_location_card"
                        }.padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_location_card_label),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(2.dp))
                CoordinatesLine(coordinates)
                Text(
                    text = accuracy,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCopy, modifier = Modifier.testTag("chat_location_copy")) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.chat_location_copy),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The composer's staged position: what the pin found so far, how sure it is, who will see it, and the way
 * out of each state that is not a reading — retry after a failure, refresh once a reading has aged, the
 * system toggle when location is off. The ✕ beside it drops the position and stops listening.
 *
 * The one spinner lives in [ChatViewModel.StagedLocation.Status.Acquiring], which the ViewModel bounds by
 * the refine window, so a composer that has settled never animates (`rules/coding.md`).
 */
@Composable
fun StagedLocationTile(
    staged: ChatViewModel.StagedLocation,
    scope: String,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    onTurnOn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fix = staged.fix
    val coordinates = fix?.let { GeoUri.coordinates(it.toPoint()) }
    val detail =
        when (staged.status) {
            ChatViewModel.StagedLocation.Status.Acquiring -> {
                stringResource(R.string.chat_location_finding)
            }

            ChatViewModel.StagedLocation.Status.Failed -> {
                stringResource(R.string.chat_location_failed)
            }

            ChatViewModel.StagedLocation.Status.ServicesOff -> {
                stringResource(R.string.chat_location_off)
            }

            ChatViewModel.StagedLocation.Status.Refining, ChatViewModel.StagedLocation.Status.Ready -> {
                val accuracy = accuracyLine(fix?.toPoint()?.accuracyM, coarse = staged.precision == LocationPrecision.Coarse)
                if (staged.status == ChatViewModel.StagedLocation.Status.Ready && fix != null) {
                    val at = DateFormat.getTimeFormat(context).format(Date(fix.timeMs))
                    stringResource(R.string.chat_location_as_of, accuracy, at)
                } else {
                    accuracy
                }
            }
        }
    // One sentence for TalkBack: the coordinates, the state and who will see it, since the texts below sit
    // inside a node whose semantics are replaced by this description.
    val summary = listOfNotNull(coordinates, detail, scope).joinToString(", ")
    val description = stringResource(R.string.chat_location_staged_desc, summary)
    Column(
        modifier =
            modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
    ) {
        Box {
            Row(
                modifier =
                    Modifier
                        .heightIn(min = 72.dp)
                        .padding(start = 12.dp, end = 48.dp, top = 12.dp, bottom = 4.dp)
                        .clearAndSetSemantics {
                            contentDescription = description
                            testTag = "chat_location_staged"
                        },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    when (staged.status) {
                        ChatViewModel.StagedLocation.Status.Acquiring -> {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }

                        ChatViewModel.StagedLocation.Status.ServicesOff -> {
                            Icon(Icons.Filled.LocationOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        else -> {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    if (coordinates != null) CoordinatesLine(coordinates)
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = scope,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 48dp touch target (a11y) with the small visible badge kept flush in the corner, as every staged attachment has.
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClear, role = Role.Button)
                        .testTag("chat_location_clear"),
                contentAlignment = Alignment.TopEnd,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(2.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.chat_location_remove),
                        modifier = Modifier.padding(4.dp).size(16.dp),
                    )
                }
            }
        }
        val action: Pair<Int, () -> Unit>? =
            when (staged.status) {
                ChatViewModel.StagedLocation.Status.Failed -> R.string.chat_location_retry to onRefresh
                ChatViewModel.StagedLocation.Status.Ready -> R.string.chat_location_refresh to onRefresh
                ChatViewModel.StagedLocation.Status.ServicesOff -> R.string.chat_location_turn_on to onTurnOn
                ChatViewModel.StagedLocation.Status.Acquiring, ChatViewModel.StagedLocation.Status.Refining -> null
            }
        if (action != null) {
            Row(modifier = Modifier.padding(start = 4.dp, bottom = 2.dp), horizontalArrangement = Arrangement.Start) {
                TextButton(onClick = action.second, modifier = Modifier.testTag("chat_location_action")) {
                    Text(stringResource(action.first))
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Coordinates always read left-to-right, whatever the UI language, so a minus sign never wanders. */
@Composable
private fun CoordinatesLine(coordinates: String) {
    Text(
        text = coordinates,
        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** "Accurate to 12 m", or the honest form for an approximate-only grant; a radius the fix never had says only "Location". */
@Composable
private fun accuracyLine(
    accuracyM: Int?,
    coarse: Boolean,
): String =
    when {
        accuracyM == null -> stringResource(R.string.chat_location_card_label)
        coarse -> stringResource(R.string.chat_location_approximate, distanceLabel(accuracyM))
        else -> stringResource(R.string.chat_location_accuracy, distanceLabel(accuracyM))
    }

/** Metres under a kilometre, kilometres to one decimal above it. */
@Composable
private fun distanceLabel(metres: Int): String =
    if (metres < METRES_PER_KM) {
        stringResource(R.string.distance_metres, metres)
    } else {
        stringResource(R.string.distance_kilometres, metres / METRES_PER_KM.toDouble())
    }

private const val METRES_PER_KM = 1000

private val previewPoint = GeoPoint(37.421998, -122.084, 12)

@Preview(showBackground = true)
@Composable
fun LocationCardPreview() {
    KnitPreview { LocationCard(point = previewPoint, onOpen = {}, onCopy = {}, onLongClick = {}) }
}

@Preview(showBackground = true)
@Composable
fun StagedLocationTileReadyPreview() {
    KnitPreview {
        StagedLocationTile(
            staged =
                ChatViewModel.StagedLocation(
                    fix = LocationFix(37.421998, -122.084, 12f, timeMs = 1_700_000_000_000L, elapsedRealtimeMs = 0L),
                    status = ChatViewModel.StagedLocation.Status.Ready,
                    precision = LocationPrecision.Fine,
                ),
            scope = "Sent only to this chat",
            onClear = {},
            onRefresh = {},
            onTurnOn = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
fun StagedLocationTileFailedPreview() {
    KnitPreview {
        StagedLocationTile(
            staged =
                ChatViewModel.StagedLocation(
                    fix = null,
                    status = ChatViewModel.StagedLocation.Status.Failed,
                    precision = LocationPrecision.Fine,
                ),
            scope = "Everyone nearby will see your exact location",
            onClear = {},
            onRefresh = {},
            onTurnOn = {},
        )
    }
}
