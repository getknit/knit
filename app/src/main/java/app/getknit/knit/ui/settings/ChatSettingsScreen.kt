// The file's single top-level class (ChatSettingsForm, the content composable's state holder) rides along with the
// screen composable that is the file's real subject.
@file:Suppress("MatchingDeclarationName")

package app.getknit.knit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import app.getknit.knit.data.settings.BluetoothDownloadLimit
import app.getknit.knit.di.TRANSLATE_WIRED
import app.getknit.knit.translate.TranslateLang
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.rememberWifiAwareSupported
import app.getknit.knit.ui.translate.TranslateIntoForm
import app.getknit.knit.ui.translate.TranslateIntoRow
import org.koin.androidx.compose.koinViewModel

/** UI-local projection of [ChatSettingsViewModel]'s per-setting flows for the stateless content. */
internal data class ChatSettingsForm(
    val typingIndicatorsEnabled: Boolean = true,
    val linkPreviewsEnabled: Boolean = false,
    val contentFilteringEnabled: Boolean = true,
    val bluetoothDownloadLimit: BluetoothDownloadLimit = BluetoothDownloadLimit.DEFAULT,
    // Null where translation is not wired: no rows. [translateInto] shows only while translation is on.
    val translationEnabled: Boolean? = null,
    val translateInto: TranslateIntoForm? = null,
)

/**
 * Settings › Chats: what a chat sends (typing, link previews) and what it lets in (content filtering, large Bluetooth
 * downloads). Like Settings, everything persists the moment it is touched.
 */
@Composable
fun ChatSettingsScreen(
    onBack: () -> Unit,
    viewModel: ChatSettingsViewModel = koinViewModel(),
) {
    val typingIndicatorsEnabled by viewModel.typingIndicatorsEnabled.collectAsStateWithLifecycle()
    val linkPreviewsEnabled by viewModel.linkPreviewsEnabled.collectAsStateWithLifecycle()
    val contentFilteringEnabled by viewModel.contentFilteringEnabled.collectAsStateWithLifecycle()
    val bluetoothDownloadLimit by viewModel.bluetoothDownloadLimit.collectAsStateWithLifecycle()
    val translationEnabled = if (TRANSLATE_WIRED) viewModel.translationEnabled.collectAsStateWithLifecycle().value else null
    val translateInto = if (TRANSLATE_WIRED) viewModel.translateInto.collectAsStateWithLifecycle().value else null
    ChatSettingsScreenContent(
        form =
            ChatSettingsForm(
                typingIndicatorsEnabled = typingIndicatorsEnabled,
                linkPreviewsEnabled = linkPreviewsEnabled,
                contentFilteringEnabled = contentFilteringEnabled,
                bluetoothDownloadLimit = bluetoothDownloadLimit,
                translationEnabled = translationEnabled,
                translateInto = translateInto,
            ),
        onBack = onBack,
        onToggleTypingIndicators = viewModel::setTypingIndicatorsEnabled,
        onToggleLinkPreviews = viewModel::setLinkPreviewsEnabled,
        onToggleContentFiltering = viewModel::setContentFilteringEnabled,
        onSelectBluetoothDownloadLimit = viewModel::setBluetoothDownloadLimit,
        onToggleTranslation = viewModel::setTranslationEnabled,
        onSelectTranslateInto = viewModel::setTranslateInto,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatSettingsScreenContent(
    form: ChatSettingsForm,
    onBack: () -> Unit,
    onToggleTypingIndicators: (Boolean) -> Unit = {},
    onToggleLinkPreviews: (Boolean) -> Unit = {},
    onToggleContentFiltering: (Boolean) -> Unit = {},
    onSelectBluetoothDownloadLimit: (BluetoothDownloadLimit) -> Unit = {},
    onToggleTranslation: (Boolean) -> Unit = {},
    onSelectTranslateInto: (TranslateLang?) -> Unit = {},
    // Link previews are the one other thing that uses the Internet, so they ship under the relay plane's build switch. A
    // parameter rather than a bare BuildConfig read so the hidden case is previewable and testable.
    showLinkPreviews: Boolean = BuildConfig.INTERNET_PLANE,
    // Whether this phone has Wi-Fi Aware at all, so the large-download row never promises a plane it lacks.
    wifiAware: Boolean = rememberWifiAwareSupported(),
) {
    Scaffold(
        modifier = Modifier.testTag("screen_chat_settings"),
        topBar = {
            TopAppBar(
                title = { BarTitle(stringResource(R.string.settings_chats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // What a chat tells the other side while you write. Not under the Internet plane's flag: the radios carry
            // typing cues in every build, and the switch stops them there too.
            ToggleRow(
                title = stringResource(R.string.settings_typing_indicators_title),
                subtitle = stringResource(R.string.settings_typing_indicators_subtitle),
                enabled = form.typingIndicatorsEnabled,
                onToggle = onToggleTypingIndicators,
                modifier = Modifier.testTag("settings_typing_indicators"),
            )

            // The subtitle carries the disclosure that would otherwise need a sheet.
            if (showLinkPreviews) {
                ToggleRow(
                    title = stringResource(R.string.settings_link_previews_title),
                    subtitle = stringResource(R.string.settings_link_previews_subtitle),
                    enabled = form.linkPreviewsEnabled,
                    onToggle = onToggleLinkPreviews,
                    modifier = Modifier.testTag("settings_link_previews"),
                )
            }

            // The two below decide what reaches this phone from others; the two above, what it sends.
            ToggleRow(
                title = stringResource(R.string.settings_content_filtering_title),
                subtitle = stringResource(R.string.settings_content_filtering_subtitle),
                enabled = form.contentFilteringEnabled,
                onToggle = onToggleContentFiltering,
                modifier = Modifier.testTag("settings_content_filtering"),
            )

            // How a large attachment only Bluetooth could bring arrives (ADR 2026-10.js2p).
            ChoiceRow(
                title = stringResource(R.string.settings_bt_download_title),
                subtitle =
                    stringResource(
                        if (wifiAware) R.string.settings_bt_download_subtitle else R.string.settings_bt_download_subtitle_bluetooth_only,
                    ),
                choices = BT_DOWNLOAD_LIMITS,
                selected = form.bluetoothDownloadLimit,
                onSelect = onSelectBluetoothDownloadLimit,
                modifier = Modifier.testTag("settings_bt_download"),
            )

            // Offline translation (ADR 2026-10.t2h6), only where it is wired. The subtitle discloses what goes on the wire:
            // off, no message declares a language. The language it translates into shows only while it is on.
            form.translationEnabled?.let { on ->
                ToggleRow(
                    title = stringResource(R.string.settings_translation_title),
                    subtitle = stringResource(R.string.settings_translation_subtitle),
                    enabled = on,
                    onToggle = onToggleTranslation,
                    modifier = Modifier.testTag("settings_translation"),
                )
                if (on) {
                    form.translateInto?.let { into ->
                        TranslateIntoRow(
                            form = into,
                            onSelect = onSelectTranslateInto,
                            modifier = Modifier.testTag("settings_translate_into"),
                        )
                    }
                }
            }
        }
    }
}

/** The three limits in the order they read, each with the string that names it. */
private val BT_DOWNLOAD_LIMITS =
    listOf(
        BluetoothDownloadLimit.NEVER to R.string.settings_bt_download_never,
        BluetoothDownloadLimit.MB_1 to R.string.settings_bt_download_1mb,
        BluetoothDownloadLimit.MB_5 to R.string.settings_bt_download_5mb,
    )

@Preview(showBackground = true)
@Composable
fun ChatSettingsScreenPreview() =
    KnitPreview {
        ChatSettingsScreenContent(
            form = ChatSettingsForm(linkPreviewsEnabled = true, bluetoothDownloadLimit = BluetoothDownloadLimit.MB_5),
            onBack = {},
            wifiAware = true,
        )
    }

@Preview(showBackground = true)
@Composable
fun ChatSettingsTranslationPreview() =
    KnitPreview {
        ChatSettingsScreenContent(
            form =
                ChatSettingsForm(
                    translationEnabled = true,
                    translateInto =
                        TranslateIntoForm(
                            selected = null,
                            automatic = TranslateLang.EN,
                            choices = listOf(TranslateLang.EN, TranslateLang("es")),
                        ),
                ),
            onBack = {},
            wifiAware = true,
        )
    }
