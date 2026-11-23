package com.moviebox.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moviebox.android.model.AppTheme
import com.moviebox.android.model.DownloadStatus
import com.moviebox.android.model.DownloadTask

@Composable
fun DownloadsSettingsScreen(
    tasks: List<DownloadTask>,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    streamAddonUrl: String,
    onStreamAddonUrlChange: (String) -> Unit,
    useExternalPlayerByDefault: Boolean,
    onExternalPlayerToggle: (Boolean) -> Unit,
    selectedTheme: AppTheme,
    onThemeSelect: (AppTheme) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Prefer External Player", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Launch streams in VLC, mpv, or MX Player by default",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
                Switch(checked = useExternalPlayerByDefault, onCheckedChange = onExternalPlayerToggle)
            }
        }

        item {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = selectedTheme == theme,
                        onClick = { onThemeSelect(theme) },
                        label = { Text(theme.label) }
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = streamAddonUrl,
                onValueChange = onStreamAddonUrlChange,
                label = { Text("Optional Stremio Stream Addon URLs") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))
            Text("Downloads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        if (tasks.isEmpty()) {
            item {
                Text("No active downloads.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        } else {
            items(tasks) { task ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(task.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            if (task.status == DownloadStatus.DOWNLOADING) {
                                IconButton(onClick = { onPauseDownload(task.id) }) {
                                    Icon(Icons.Default.Pause, contentDescription = "Pause")
                                }
                            } else if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
                                IconButton(onClick = { onResumeDownload(task.id) }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${task.status.name} • ${(task.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
