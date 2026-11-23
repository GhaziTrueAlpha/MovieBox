package com.moviebox.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moviebox.android.model.EpisodeItem
import com.moviebox.android.model.StreamSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamPickerSheet(
    title: String,
    providerLabel: String,
    episodes: List<EpisodeItem>,
    selectedEpisode: EpisodeItem?,
    onSelectEpisode: (EpisodeItem) -> Unit,
    streams: List<StreamSource>,
    isLoading: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onPlayInApp: (StreamSource) -> Unit,
    onPlayExternal: (StreamSource) -> Unit,
    onDownload: (StreamSource) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                AssistChip(onClick = {}, label = { Text(providerLabel) })
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (episodes.isNotEmpty()) {
                Text(
                    text = "Episodes",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                    items(episodes) { ep ->
                        FilterChip(
                            selected = ep.id == selectedEpisode?.id,
                            onClick = { onSelectEpisode(ep) },
                            label = { Text("S${ep.season}E${ep.episode} • ${ep.title}") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = "Available Streams",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (streams.isEmpty()) {
                Text(
                    text = errorMessage ?: "No streams available from $providerLabel for this title. Try switching provider (MovieBox / 4KHDHub / Dramachi) in Search.",
                    modifier = Modifier.padding(vertical = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    items(streams) { stream ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPlayInApp(stream) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AssistChip(
                                    onClick = { onPlayInApp(stream) },
                                    label = { Text(stream.quality) }
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stream.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2
                                    )
                                    if (stream.sizeText.isNotBlank()) {
                                        Text(
                                            text = stream.sizeText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                        )
                                    }
                                }
                                IconButton(onClick = { onPlayInApp(stream) }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Play in app")
                                }
                                IconButton(onClick = { onPlayExternal(stream) }) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = "External player")
                                }
                                IconButton(onClick = { onDownload(stream) }) {
                                    Icon(Icons.Default.Download, contentDescription = "Download")
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
