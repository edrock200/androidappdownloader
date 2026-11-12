package io.github.edrock200.appdownloader.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import io.github.edrock200.appdownloader.data.Backup
import io.github.edrock200.appdownloader.data.BackupFile

/** Shows the user's apps and settings as a QR code for another copy of the app to scan. */
@Composable
fun ShowQrDialog(text: String, appCount: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy settings to another device") },
        text = {
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Sized from the screen height too, so on a TV the explanation below isn't cut off.
                QrCode(text, Modifier.widthIn(max = minOf(360.dp, screenHeight * 0.55f)).fillMaxWidth())
                Text(
                    "On the other device, open App Downloader › Settings › Scan QR code and point the camera here. " +
                        "Contains $appCount app${if (appCount == 1) "" else "s"} and your settings " +
                        "(not your GitHub token).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight()) { Text("Done") }
        },
    )
}

/**
 * Asks how to restore a backup that was read from a file, QR code or link, showing exactly what it
 * would add and change. Backups from a link or QR code (possibly someone else's) don't restore
 * settings unless ticked, and never the GitHub token unless separately ticked.
 */
@Composable
fun RestoreDialog(
    backup: BackupFile,
    origin: BackupOrigin,
    currentCount: Int,
    onDismiss: () -> Unit,
    onRestore: (replace: Boolean, withSettings: Boolean, withToken: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val trusted = origin == BackupOrigin.OWN_FILE
    var replace by rememberSaveable { mutableStateOf(false) }
    var withSettings by rememberSaveable { mutableStateOf(trusted) }
    var withToken by rememberSaveable { mutableStateOf(trusted) }
    val count = backup.apps.size
    val hasToken = backup.settings.githubToken != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore backup?") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    buildString {
                        append("$count app${if (count == 1) "" else "s"}")
                        if (backup.createdAt > 0) append(", saved ${formatDateTime(context, backup.createdAt)}")
                        backup.appVersion?.let { append(" by App Downloader $it") }
                        append(":")
                    },
                )
                // Each app and where it downloads from, so nothing unexpected is added. Focusable rows scroll with a remote.
                backup.apps.take(Backup.MAX_APPS).forEach { app ->
                    Column(
                        Modifier.fillMaxWidth().focusHighlight(FocusShapes.Row, scaleUp = false).focusable().padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text(app.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (app.type == io.github.edrock200.appdownloader.data.SourceType.GITHUB) "github.com/${app.source}"
                            else app.source,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (!trusted) {
                    Text(
                        "Only restore backups from people you trust: these apps will download from the addresses shown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (currentCount > 0) {
                    ChoiceRow("Add to my list (skip apps already in it)", selected = !replace) { replace = false }
                    ChoiceRow("Replace my list ($currentCount app${if (currentCount == 1) "" else "s"})", selected = replace) { replace = true }
                }
                TickRow(
                    "Also restore settings" + buildString {
                        val schedule = backup.settings.scheduleAt > 0
                        if (schedule) append(" (includes a ${backup.settings.scheduleRepeat.label.lowercase()} schedule)")
                    },
                    withSettings,
                ) { withSettings = it }
                if (withSettings && hasToken) {
                    TickRow("Also replace my GitHub token with the one in the backup", withToken) { withToken = it }
                }
                Text(
                    "Apps aren't installed automatically. You'll be offered \"Install all\" afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onRestore(replace, withSettings, withSettings && withToken) }, modifier = Modifier.focusHighlight()) { Text("Restore") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight()) { Text("Cancel") }
        },
    )
}

@Composable
private fun TickRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(FocusShapes.Row, scaleUp = false)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
    }
}

/** Restores from a link or aftv.news code pointing at a backup file. */
@Composable
fun RestoreLinkDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore from a link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Link or aftv.news code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Upload a backup file somewhere that gives a direct download link (for example a GitHub " +
                        "Gist's \"Raw\" link), and optionally make an aftv.news code for it to type on a TV.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(input) },
                enabled = input.isNotBlank(),
                modifier = Modifier.focusHighlight(),
            ) { Text("Load") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight()) { Text("Cancel") }
        },
    )
}

/** Lists backups in the Downloads folder (used on TVs without a file picker). */
@Composable
fun DownloadsBackupDialog(
    backups: List<StoredBackup>,
    onPick: (StoredBackup) -> Unit,
    onDelete: (StoredBackup) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Backups in the Downloads folder:", style = MaterialTheme.typography.bodyMedium)
                backups.forEach { backup ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier
                                .weight(1f)
                                .focusHighlight(FocusShapes.Row, scaleUp = false)
                                .selectable(selected = false, onClick = { onPick(backup) }, role = Role.Button)
                                .padding(8.dp),
                        ) {
                            Text(backup.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                formatDateTime(context, backup.modified),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onDelete(backup) }, modifier = Modifier.focusHighlight()) { Text("Delete") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight()) { Text("Cancel") }
        },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(FocusShapes.Row, scaleUp = false)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
    }
}
