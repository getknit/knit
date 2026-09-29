package app.getknit.knit.ui.about

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.crash.CrashEnvironment
import app.getknit.knit.legal.InstallSource
import app.getknit.knit.legal.License
import app.getknit.knit.ui.ISSUES_URL
import app.getknit.knit.ui.REPO_URL
import app.getknit.knit.ui.WEBSITE_URL
import app.getknit.knit.ui.components.DetailCard
import app.getknit.knit.ui.components.DetailRow
import app.getknit.knit.ui.components.SectionHeader
import app.getknit.knit.ui.openUrl
import app.getknit.knit.ui.preview.KnitPreview
import kotlinx.coroutines.launch

/** The launcher mark at the top of the screen. */
private val BRAND_MARK_SIZE = 72.dp

/** The label the clipboard shows for the copied build block. */
private const val CLIP_LABEL = "Knit build info"

/**
 * About Knit: the license notice GPL §5 asks a program to carry, the way to the source, and the build facts a
 * bug report starts with. Reached from Settings' overflow menu. Purely static — no ViewModel: everything here
 * is a `BuildConfig` constant, an `android.os.Build` field or one `PackageManager` read, taken once.
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenLicense: (License) -> Unit,
) {
    val context = LocalContext.current
    val info = remember { currentAboutBuildInfo(context) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // Android 13+ shows its own copy confirmation, so the snackbar only fires below it.
    val copiedMessage = stringResource(R.string.action_copied)

    AboutScreenContent(
        info = info,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onOpenUrl = { openUrl(context, it) },
        onOpenLicense = onOpenLicense,
        onOpenLicenses = onOpenLicenses,
        onCopyBuildInfo = {
            scope.launch {
                clipboard.setClipEntry(ClipData.newPlainText(CLIP_LABEL, info.asText()).toClipEntry())
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbarHostState.showSnackbar(copiedMessage)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutScreenContent(
    info: AboutBuildInfo,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenLicense: (License) -> Unit,
    onOpenLicenses: () -> Unit,
    onCopyBuildInfo: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag("screen_about"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.about_title)) },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Header(info.environment)
            Notice()
            LinkRows(onOpenUrl = onOpenUrl, onOpenLicense = onOpenLicense, onOpenLicenses = onOpenLicenses)
            BuildSection(info = info, onCopy = onCopyBuildInfo)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The mark, the name, the version and the tagline — one TalkBack stop, since none of it is actionable. */
@Composable
private fun Header(environment: CrashEnvironment) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .testTag("about_header"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The launcher's themed-icon layer, tinted to the scheme's primary the way onboarding draws it.
        Icon(
            painter = painterResource(R.drawable.ic_launcher_monochrome),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(BRAND_MARK_SIZE),
        )
        Text(text = "Knit", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(R.string.about_version, environment.versionName, environment.versionCode),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("about_version"),
        )
        Text(
            text = stringResource(R.string.onboarding_tagline),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** The GPL notice in two sentences; the full text is one row down. */
@Composable
private fun Notice() {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp).testTag("about_notice"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.about_copyright, stringResource(R.string.about_author)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.about_license_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LinkRows(
    onOpenUrl: (String) -> Unit,
    onOpenLicense: (License) -> Unit,
    onOpenLicenses: () -> Unit,
) {
    Column {
        LinkRow(
            icon = Icons.Filled.Code,
            title = stringResource(R.string.about_source_code),
            subtitle = stringResource(R.string.about_source_code_subtitle),
            onClick = { onOpenUrl(REPO_URL) },
            modifier = Modifier.testTag("about_source"),
        )
        LinkRow(
            icon = Icons.Filled.BugReport,
            title = stringResource(R.string.about_report_problem),
            subtitle = stringResource(R.string.about_report_problem_subtitle),
            onClick = { onOpenUrl(ISSUES_URL) },
            modifier = Modifier.testTag("about_report"),
        )
        LinkRow(
            icon = Icons.Filled.Language,
            title = stringResource(R.string.about_website),
            subtitle = stringResource(R.string.about_website_subtitle),
            onClick = { onOpenUrl(WEBSITE_URL) },
            modifier = Modifier.testTag("about_website"),
        )
        LinkRow(
            icon = Icons.Outlined.Description,
            title = stringResource(R.string.about_license),
            subtitle = License.GPL_3_0_OR_LATER.displayName,
            onClick = { onOpenLicense(License.GPL_3_0_OR_LATER) },
            modifier = Modifier.testTag("about_license"),
        )
        LinkRow(
            icon = Icons.AutoMirrored.Outlined.Article,
            title = stringResource(R.string.about_licenses),
            subtitle = stringResource(R.string.about_licenses_subtitle),
            onClick = onOpenLicenses,
            modifier = Modifier.testTag("about_licenses"),
        )
    }
}

/**
 * The Donate screen's link row with a second line: an icon in a tinted disc, a title, where the row goes,
 * and a chevron. One button to a screen reader.
 */
@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
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
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
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

/**
 * The facts a bug report opens with, as label/value rows, and one button that copies them all in the crash
 * header's shape. "minified" is R8's word for it, read off the running artifact rather than the build type
 * (see `CrashEnvironment.obfuscated`).
 */
@Composable
private fun BuildSection(
    info: AboutBuildInfo,
    onCopy: () -> Unit,
) {
    val environment = info.environment
    SectionHeader(stringResource(R.string.about_section_build))
    DetailCard {
        DetailRow(
            label = stringResource(R.string.about_build_version),
            value = "${environment.versionName} (${environment.versionCode})",
            modifier = Modifier.testTag("about_build_version"),
        )
        info.commit?.let { commit ->
            DetailRow(
                label = stringResource(R.string.about_build_commit),
                value = commit,
                modifier = Modifier.testTag("about_build_commit"),
            )
        }
        DetailRow(
            label = stringResource(R.string.about_build_type),
            value =
                if (environment.obfuscated) {
                    stringResource(R.string.about_build_type_minified, environment.buildType)
                } else {
                    environment.buildType
                },
            modifier = Modifier.testTag("about_build_type"),
        )
        DetailRow(
            label = stringResource(R.string.about_build_installed_from),
            value = stringResource(info.installSource.labelRes()),
            modifier = Modifier.testTag("about_build_installed_from"),
        )
        DetailRow(
            label = stringResource(R.string.about_build_android),
            value = stringResource(R.string.about_build_android_value, environment.release, environment.sdkInt),
            modifier = Modifier.testTag("about_build_android"),
        )
        DetailRow(
            label = stringResource(R.string.about_build_device),
            value = "${environment.manufacturer} ${environment.model}",
            modifier = Modifier.testTag("about_build_device"),
        )
    }
    TextButton(
        onClick = onCopy,
        modifier = Modifier.padding(horizontal = 16.dp).testTag("about_copy_build_info"),
    ) {
        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.about_copy_build_info))
    }
}

private fun InstallSource.labelRes(): Int =
    when (this) {
        InstallSource.PLAY -> R.string.about_install_source_play
        InstallSource.FDROID -> R.string.about_install_source_fdroid
        InstallSource.SIDELOADED -> R.string.about_install_source_sideloaded
    }

internal fun previewEnvironment(
    buildType: String = "release",
    obfuscated: Boolean = true,
) = CrashEnvironment(
    versionName = "2.5.1",
    versionCode = 21,
    buildType = buildType,
    obfuscated = obfuscated,
    manufacturer = "Google",
    model = "Pixel 8",
    device = "shiba",
    board = "shiba",
    hardware = "shiba",
    soc = "Google Tensor G3",
    sdkInt = 36,
    release = "16",
    abis = "arm64-v8a",
    fingerprint = "google/shiba/shiba:16/BP2A/0:user/release-keys",
)

@Preview(showBackground = true)
@Composable
fun AboutScreenPreview() =
    KnitPreview {
        AboutScreenContent(
            info = AboutBuildInfo(environment = previewEnvironment(), installSource = InstallSource.FDROID),
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onOpenUrl = {},
            onOpenLicense = {},
            onOpenLicenses = {},
            onCopyBuildInfo = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun AboutScreenDebugPreview() =
    KnitPreview {
        AboutScreenContent(
            info =
                AboutBuildInfo(
                    environment = previewEnvironment(buildType = "debug", obfuscated = false),
                    installSource = InstallSource.SIDELOADED,
                    commit = "06dd410d",
                ),
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onOpenUrl = {},
            onOpenLicense = {},
            onOpenLicenses = {},
            onCopyBuildInfo = {},
        )
    }
