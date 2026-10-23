package app.getknit.knit.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.legal.License
import app.getknit.knit.legal.NoticeKind
import app.getknit.knit.legal.ThirdPartyNotice
import app.getknit.knit.legal.ThirdPartyNotices
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.components.SectionHeader
import app.getknit.knit.ui.preview.KnitPreview

/**
 * Every open-source component Knit ships, Knit itself first, each row opening its license text. The list is
 * [ThirdPartyNotices.ALL] — the same rows as `THIRD-PARTY-NOTICES.md`, which the sync tests keep honest.
 */
@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    onOpenLicense: (License) -> Unit,
) {
    LicensesScreenContent(notices = ThirdPartyNotices.ALL, onBack = onBack, onOpenLicense = onOpenLicense)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LicensesScreenContent(
    notices: List<ThirdPartyNotice>,
    onBack: () -> Unit,
    onOpenLicense: (License) -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag("screen_licenses"),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { BarTitle(stringResource(R.string.licenses_title)) },
            )
        },
    ) { padding ->
        val libraries = notices.filter { it.kind == NoticeKind.RUNTIME }
        val data = notices.filter { it.kind == NoticeKind.DATA }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("licenses_list"),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item(key = "intro") {
                Text(
                    text = stringResource(R.string.licenses_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "knit") {
                NoticeRow(
                    name = "Knit",
                    subtitle =
                        stringResource(
                            R.string.licenses_row_subtitle,
                            stringResource(R.string.about_author),
                            License.GPL_3_0_OR_LATER.spdx,
                        ),
                    onClick = { onOpenLicense(License.GPL_3_0_OR_LATER) },
                    modifier = Modifier.testTag("licenses_row_knit"),
                )
            }
            item(key = "section_runtime") { SectionHeader(stringResource(R.string.licenses_section_runtime)) }
            items(libraries, key = { it.key }) { notice -> NoticeRow(notice, onOpenLicense) }
            item(key = "section_data") { SectionHeader(stringResource(R.string.licenses_section_data)) }
            items(data, key = { it.key }) { notice -> NoticeRow(notice, onOpenLicense) }
        }
    }
}

@Composable
private fun NoticeRow(
    notice: ThirdPartyNotice,
    onOpenLicense: (License) -> Unit,
) {
    NoticeRow(
        name = notice.name,
        subtitle = stringResource(R.string.licenses_row_subtitle, notice.project, notice.license.spdx),
        onClick = { onOpenLicense(notice.license) },
        // Keyed on the notice, never its index, so a reordered list cannot move a test's anchor.
        modifier = Modifier.testTag("licenses_row_${notice.key}"),
    )
}

/** Component name over `project · license`, with a chevron; the whole row is the button. */
@Composable
private fun NoticeRow(
    name: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick, role = Role.Button)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
fun LicensesScreenPreview() =
    KnitPreview {
        LicensesScreenContent(notices = ThirdPartyNotices.ALL, onBack = {}, onOpenLicense = {})
    }
