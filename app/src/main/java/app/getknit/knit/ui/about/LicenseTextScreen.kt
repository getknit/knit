package app.getknit.knit.ui.about

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.legal.License
import app.getknit.knit.legal.licenseParagraphs
import app.getknit.knit.legal.readLicenseText
import app.getknit.knit.ui.preview.KnitPreview

/**
 * One license's full text, read from `assets/legal/` off the main thread and reflowed into paragraphs. Works
 * with no Internet — the point of bundling the texts — so a phone that got Knit over Bluetooth can read them.
 */
@Composable
fun LicenseTextScreen(
    license: License,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val paragraphs by
        produceState<List<String>?>(initialValue = null, license) {
            value = readLicenseText(context.assets, license)?.let(::licenseParagraphs).orEmpty()
        }
    LicenseTextScreenContent(license = license, paragraphs = paragraphs, onBack = onBack)
}

/** Null [paragraphs] is still loading; empty means the asset could not be read. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LicenseTextScreenContent(
    license: License,
    paragraphs: List<String>?,
    onBack: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag("screen_license_text"),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(license.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        },
    ) { padding ->
        when {
            paragraphs == null -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.testTag("license_text_loading"))
                }
            }

            paragraphs.isEmpty() -> {
                Text(
                    text = stringResource(R.string.license_text_error),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(padding).padding(16.dp).testTag("license_text_error"),
                )
            }

            else -> {
                // Paragraph items rather than one 35 KB Text: a cheaper first frame, and TalkBack reads a
                // paragraph at a time instead of the whole license as one utterance.
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding).testTag("license_text_body"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        itemsIndexed(paragraphs) { index, paragraph ->
                            Text(
                                text = paragraph,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = if (index == paragraphs.lastIndex) 0.dp else 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LicenseTextScreenPreview() =
    KnitPreview {
        LicenseTextScreenContent(
            license = License.MIT,
            paragraphs =
                listOf(
                    "MIT License",
                    "Copyright (c) 2020 The nsfw_model Developers",
                    "Permission is hereby granted, free of charge, to any person obtaining a copy of this software " +
                        "and associated documentation files (the \"Software\"), to deal in the Software without " +
                        "restriction.",
                ),
            onBack = {},
        )
    }
