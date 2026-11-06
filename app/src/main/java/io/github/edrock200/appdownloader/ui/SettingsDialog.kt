package io.github.edrock200.appdownloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.edrock200.appdownloader.BuildConfig
import io.github.edrock200.appdownloader.data.Repeat
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.data.SettingsState
import io.github.edrock200.appdownloader.data.ThemeMode

@Composable
fun SettingsDialog(
    current: SettingsState,
    updatingAll: Boolean,
    onCheckSelfUpdate: () -> Unit,
    onScheduleChange: (at: Long, repeat: Repeat) -> Unit,
    onOnlyIfChangedChange: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onUpdateAllNow: () -> Unit,
    onSaveBackup: (includeToken: Boolean) -> Unit,
    onRestoreBackup: () -> Unit,
    onShowQr: () -> Unit,
    onScanQr: (() -> Unit)?,
    onRestoreFromLink: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (SettingsState) -> Unit,
) {
    val context = LocalContext.current
    var token by rememberSaveable { mutableStateOf(current.githubToken) }
    var interval by rememberSaveable { mutableStateOf(current.checkIntervalHours) }
    var deleteAfter by rememberSaveable { mutableStateOf(current.deleteApkAfterInstall) }
    var selfUpdates by rememberSaveable { mutableStateOf(current.checkSelfUpdates) }
    var editingSchedule by rememberSaveable { mutableStateOf(false) }
    var backupToken by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleSmall)
                ThemeMode.entries.forEach { mode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .focusHighlight(FocusShapes.Row, scaleUp = false)
                            .selectable(selected = current.themeMode == mode, onClick = { onThemeModeChange(mode) }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current.themeMode == mode, onClick = null)
                        Text(mode.label, Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
                    }
                }
                HorizontalDivider()
                // The schedule is saved as soon as it is set, so it isn't lost if Settings is cancelled.
                Text("Scheduled updates", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (current.hasSchedule) {
                        "Next: ${formatDateTime(context, current.scheduleAt)} · ${current.scheduleRepeat.label}"
                    } else {
                        "Not scheduled"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editingSchedule = true }, modifier = Modifier.focusHighlight()) {
                        Text(if (current.hasSchedule) "Change" else "Set date & time")
                    }
                    if (current.hasSchedule) {
                        TextButton(onClick = { onScheduleChange(0, Repeat.ONCE) }, modifier = Modifier.focusHighlight()) {
                            Text("Turn off")
                        }
                    }
                }
                SwitchRow("Only update links whose file has changed", current.updateOnlyIfChanged, onOnlyIfChangedChange)
                Text(
                    "Asks the website whether the file changed before downloading it. If the website " +
                        "doesn't say, the file is downloaded and its version compared, so no update is missed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onUpdateAllNow, enabled = !updatingAll, modifier = Modifier.focusHighlight()) {
                    Text(if (updatingAll) "Updating…" else "Update all now")
                }
                Text(
                    "Updates the apps with \"Auto-update\" ticked. If Android asks to confirm an install, " +
                        "the update is downloaded and waits for you the next time you open App Downloader.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text("Check GitHub for updates", style = MaterialTheme.typography.titleSmall)
                Settings.INTERVAL_OPTIONS.forEach { hours ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .focusHighlight(FocusShapes.Row, scaleUp = false)
                            .selectable(selected = interval == hours, onClick = { interval = hours }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = interval == hours, onClick = null)
                        Text(
                            if (hours == 0) "Never (manual only)" else "Every $hours hours",
                            modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                        )
                    }
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("GitHub token (optional)") },
                    supportingText = {
                        Text("Raises the API limit from 60 to 5,000 checks/hour and allows private repos.")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow("Delete APK after installing", deleteAfter) { deleteAfter = it }
                HorizontalDivider()
                Text("Back up & restore", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Saves your list of apps and your settings, to restore later or copy to another device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (current.githubToken.isNotBlank()) {
                    CheckboxRow(
                        label = "Include GitHub token in backup files",
                        detail = "Leave this off if you'll share the file. QR codes never include it.",
                        checked = backupToken,
                    ) { backupToken = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSaveBackup(backupToken) }, modifier = Modifier.focusHighlight()) {
                        Text("Save backup file")
                    }
                    OutlinedButton(onClick = onRestoreBackup, modifier = Modifier.focusHighlight()) {
                        Text("Restore from file")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onShowQr, modifier = Modifier.focusHighlight()) { Text("Show QR code") }
                    if (onScanQr != null) {
                        OutlinedButton(onClick = onScanQr, modifier = Modifier.focusHighlight()) { Text("Scan QR code") }
                    }
                }
                OutlinedButton(onClick = onRestoreFromLink, modifier = Modifier.focusHighlight()) {
                    Text("Restore from link or aftv.news code")
                }
                HorizontalDivider()
                Text("App Downloader ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                CheckboxRow(
                    label = "Check for App Downloader updates each time it's opened",
                    detail = "If a new version is available, you'll be asked whether to install it.",
                    checked = selfUpdates,
                ) { selfUpdates = it }
                OutlinedButton(onClick = onCheckSelfUpdate, modifier = Modifier.focusHighlight()) {
                    Text("Check for app update now")
                }
                HorizontalDivider()
                val canInstall = SystemActions.canInstallPackages(context)
                Text(
                    if (canInstall) "Permission to install apps: granted" else "Permission to install apps: not granted",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!canInstall) {
                    Text(
                        Device.installPermissionHelp(context),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { SystemActions.openInstallPermissionSettings(context) },
                    modifier = Modifier.focusHighlight(),
                ) {
                    Text("Open install permission settings")
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.focusHighlight(), onClick = {
                onSave(
                    current.copy(
                        githubToken = token,
                        checkIntervalHours = interval,
                        deleteApkAfterInstall = deleteAfter,
                        checkSelfUpdates = selfUpdates,
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight()) { Text("Cancel") } },
    )

    if (editingSchedule) {
        ScheduleDialog(
            initialAt = current.scheduleAt,
            initialRepeat = current.scheduleRepeat,
            onDismiss = { editingSchedule = false },
            onSave = { at, repeat ->
                editingSchedule = false
                onScheduleChange(at, repeat)
            },
        )
    }
}

/** A tick box + label (+ optional explanation) where the whole row is one focusable, toggleable target. */
@Composable
private fun CheckboxRow(label: String, detail: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(FocusShapes.Row, scaleUp = false)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(label)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A label + switch where the whole row is one focusable, toggleable target (easier with a remote). */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(FocusShapes.Row, scaleUp = false)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f).padding(start = 4.dp, end = 8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
