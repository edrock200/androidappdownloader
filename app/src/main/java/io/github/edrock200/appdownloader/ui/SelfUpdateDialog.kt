package io.github.edrock200.appdownloader.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.edrock200.appdownloader.BuildConfig
import io.github.edrock200.appdownloader.OpState
import io.github.edrock200.appdownloader.SelfUpdate

/** Offers to update App Downloader itself. */
@Composable
fun SelfUpdateDialog(
    update: SelfUpdate,
    op: OpState?,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    val busy = op != null && op !is OpState.Failed
    AlertDialog(
        onDismissRequest = { if (!busy) onLater() },
        title = { Text("Update available") },
        text = {
            // One scrolling column; status first so it's never pushed off screen, and focusable
            // paragraphs so a TV remote can scroll long release notes.
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "App Downloader ${update.version} is available. You have version ${BuildConfig.VERSION_NAME}.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                when (op) {
                    null -> Text(
                        "The app will close once the update is installed. Just open it again afterwards.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is OpState.Failed -> Text(op.message, color = MaterialTheme.colorScheme.error)
                    is OpState.Downloading -> {
                        Text(op.progress?.let { "Downloading… ${(it * 100).toInt()}%" } ?: "Downloading…")
                        val progress = op.progress
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    else -> {
                        Text("Waiting for installer…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                update.notes?.let { notes ->
                    plainText(notes).split("\n\n").filter { it.isNotBlank() }.forEach { paragraph ->
                        Text(
                            paragraph,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().focusHighlight(FocusShapes.Row, scaleUp = false).focusable(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            // Stays in place while downloading so a TV remote's focus isn't lost; presses are ignored then.
            Button(onClick = { if (!busy) onUpdate() }, modifier = Modifier.focusHighlight()) {
                Text(
                    when {
                        busy -> "Updating…"
                        op is OpState.Failed -> "Try again"
                        else -> "Update now"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, modifier = Modifier.focusHighlight()) {
                Text(if (busy) "Cancel" else "Later")
            }
        },
    )
}

/** Turns simple Markdown release notes into readable plain text. */
internal fun plainText(markdown: String): String = markdown
    .lines()
    .joinToString("\n") { line ->
        line.trimEnd()
            .replace(Regex("^#+\\s*"), "")
            .replace(Regex("^\\s*[-*]\\s+"), "• ")
            .replace("**", "")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
    }
    .trim()
