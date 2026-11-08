package io.github.edrock200.appdownloader.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.edrock200.appdownloader.OpState
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.net.AftvResolver
import io.github.edrock200.appdownloader.net.ChangeReason
import io.github.edrock200.appdownloader.net.RemoteChange

@Composable
fun EntryCard(
    item: EntryUi,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit,
    onAutoUpdateChange: (Boolean) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val entry = item.entry
    val installed = item.installed
    val op = item.op
    val busy = op != null && op !is OpState.Failed
    val hasBrowser = remember { SystemActions.hasBrowser(context) }
    // Outline the whole card while any of its buttons is selected, so TV users can see which app they're on.
    var cardFocused by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { cardFocused = it.hasFocus }
            .then(
                if (cardFocused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CardDefaults.shape)
                else Modifier
            ),
        colors = if (item.hasUpdate || item.waiting) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        TypeBadge(entry.type)
                        if (item.hasUpdate || item.waiting) {
                            Spacer(Modifier.width(6.dp))
                            UpdateStar()
                        }
                    }
                    Text(
                        when (entry.type) {
                            SourceType.URL -> entry.source
                            SourceType.AFTV_CODE -> AftvResolver.shortUrl(entry.source).removePrefix("https://")
                            SourceType.GITHUB -> "github.com/${entry.source}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OverflowMenu(item, onCheck, onEdit, onDelete, onHistory, onMessage)
            }

            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(end = 12.dp)) {
                InfoLine(
                    "Installed",
                    when {
                        installed != null -> versionLabel(installed.versionName, installed.versionCode)
                        entry.packageName != null -> "Not installed"
                        else -> "Unknown (install once to track)"
                    },
                )
                if (entry.type == SourceType.GITHUB) {
                    when {
                        entry.lastCheckError != null -> Text(
                            entry.lastCheckError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        entry.latestTag != null -> InfoLine(
                            "Latest",
                            buildString {
                                append(entry.latestTag)
                                entry.latestPublishedAt?.take(10)?.let { append(" · $it") }
                                entry.latestAssetName?.let { append(" · $it") }
                            },
                            highlight = item.hasUpdate,
                        )
                        else -> InfoLine("Latest", "Not checked yet")
                    }
                } else if (installed != null) {
                    // Links: has the file on the website changed since the installed copy was downloaded?
                    val checked = entry.remoteCheckedAt?.let { " · checked " + relativeTime(it) }.orEmpty()
                    when {
                        entry.lastCheckError != null -> Text(
                            entry.lastCheckError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        entry.remoteChange == RemoteChange.CHANGED ->
                            InfoLine("File", "Newer file on the website$checked", highlight = true)
                        entry.remoteChange == RemoteChange.UNCHANGED ->
                            InfoLine(
                                "File",
                                (if (entry.remoteReason == ChangeReason.CONTENT) "Same as your installed copy"
                                else "No change since you installed it") + checked,
                            )
                        entry.remoteChange == RemoteChange.UNKNOWN && entry.remoteReason == ChangeReason.NO_BASELINE ->
                            InfoLine("File", "Can't compare yet: update or reinstall it once from here$checked")
                        entry.remoteChange == RemoteChange.UNKNOWN ->
                            InfoLine("File", "This website doesn't give file details, so changes can't be seen$checked")
                        else -> InfoLine("File", "Not checked yet")
                    }
                }

                if (item.waiting) {
                    InfoLine(
                        "Downloaded",
                        listOfNotNull(entry.readyVersionName ?: entry.readyTag, "ready to install").joinToString(" · "),
                        highlight = true,
                    )
                }

                when (op) {
                    null -> Unit
                    is OpState.Failed -> {
                        Spacer(Modifier.height(4.dp))
                        Text(op.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when (op) {
                                OpState.Resolving -> if (entry.type == SourceType.GITHUB) "Checking release…" else "Resolving link…"
                                is OpState.Downloading -> op.progress?.let { "Downloading… ${(it * 100).toInt()}%" } ?: "Downloading…"
                                OpState.Installing -> "Waiting for installer…"
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        val progress = (op as? OpState.Downloading)?.progress
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        busy -> OutlinedButton(onClick = onCancel, modifier = Modifier.focusHighlight()) { Text(if (op == OpState.Installing) "Dismiss" else "Cancel") }
                        else -> Button(onClick = onInstall, modifier = Modifier.focusHighlight()) {
                            Text(
                                when {
                                    item.waiting -> "Install update" + (entry.readyVersionName?.let { " $it" } ?: "")
                                    item.hasUpdate -> "Update"
                                    installed == null -> "Install"
                                    entry.type == SourceType.GITHUB -> "Reinstall"
                                    else -> "Re-download"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (installed != null && entry.packageName != null && !busy &&
                        SystemActions.canLaunch(context, entry.packageName)
                    ) {
                        OutlinedButton(
                            onClick = { SystemActions.launchApp(context, entry.packageName) },
                            modifier = Modifier.focusHighlight(),
                        ) { Text("Open") }
                    }
                    if (op is OpState.Failed) {
                        op.openUrl?.takeIf { hasBrowser }?.let { url ->
                            TextButton(modifier = Modifier.focusHighlight(), onClick = {
                                if (!SystemActions.openUrl(context, url)) onMessage("No browser available")
                            }) { Text("Open in browser") }
                        }
                        TextButton(onClick = onCancel, modifier = Modifier.focusHighlight()) { Text("Dismiss") }
                    }
                }

                Row(
                    Modifier
                        .padding(top = 4.dp)
                        .focusHighlight(FocusShapes.Row, scaleUp = false)
                        .toggleable(value = entry.autoUpdate, role = Role.Checkbox, onValueChange = onAutoUpdateChange)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = entry.autoUpdate, onCheckedChange = null)
                    Text(
                        if (installed == null) "Auto-update (once installed)" else "Auto-update",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OverflowMenu(
    item: EntryUi,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val entry = item.entry
    var open by remember { mutableStateOf(false) }
    val hasBrowser = remember { SystemActions.hasBrowser(context) }
    val itemModifier = Modifier.focusHighlight(FocusShapes.Row, scaleUp = false)
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.focusHighlight(FocusShapes.Icon)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (entry.type == SourceType.GITHUB || item.installed != null) {
                DropdownMenuItem(text = { Text("Check for update") }, modifier = itemModifier, onClick = { open = false; onCheck() })
            }
            if (entry.type == SourceType.GITHUB && hasBrowser) {
                DropdownMenuItem(text = { Text("Open release page") }, modifier = itemModifier, onClick = {
                    open = false
                    val url = entry.latestReleaseUrl ?: "https://github.com/${entry.source}/releases"
                    if (!SystemActions.openUrl(context, url)) onMessage("No browser available")
                })
            } else if (hasBrowser) {
                DropdownMenuItem(text = { Text("Open link in browser") }, modifier = itemModifier, onClick = {
                    open = false
                    val url = if (entry.type == SourceType.AFTV_CODE) AftvResolver.shortUrl(entry.source) else entry.source
                    if (!SystemActions.openUrl(context, url)) onMessage("No browser available")
                })
            }
            DropdownMenuItem(text = { Text("Update history") }, modifier = itemModifier, onClick = { open = false; onHistory() })
            DropdownMenuItem(text = { Text("Edit") }, modifier = itemModifier, onClick = { open = false; onEdit() })
            if (item.installed != null && entry.packageName != null) {
                DropdownMenuItem(text = { Text("Uninstall app") }, modifier = itemModifier, onClick = {
                    open = false
                    if (!SystemActions.uninstall(context, entry.packageName)) onMessage("Could not open uninstaller")
                })
            }
            DropdownMenuItem(text = { Text("Remove from list") }, modifier = itemModifier, onClick = { open = false; onDelete() })
        }
    }
}

/** Gold star shown next to apps that have an update. */
@Composable
fun UpdateStar(modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.Star,
        contentDescription = "Update available",
        tint = Color(0xFFFFB300),
        modifier = modifier.size(20.dp),
    )
}

@Composable
private fun TypeBadge(type: SourceType) {
    val (label, color) = when (type) {
        SourceType.URL -> "Link" to MaterialTheme.colorScheme.secondaryContainer
        SourceType.AFTV_CODE -> "aftv.news" to MaterialTheme.colorScheme.primaryContainer
        SourceType.GITHUB -> "GitHub" to Color(0xFF24292F)
    }
    val textColor = if (type == SourceType.GITHUB) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String, highlight: Boolean = false) {
    Row {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun relativeTime(millis: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString().replaceFirstChar { it.lowercase() }

private fun versionLabel(name: String?, code: Long) = if (name.isNullOrBlank()) "build $code" else "$name ($code)"
