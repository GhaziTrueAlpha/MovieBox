package com.moviebox.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moviebox.android.model.LiveChannel

@Composable
fun LiveTvScreen(
    playlistUrl: String,
    onPlaylistUrlChange: (String) -> Unit,
    channels: List<LiveChannel>,
    isLoading: Boolean,
    onLoadPlaylist: () -> Unit,
    onPlayChannel: (LiveChannel) -> Unit
) {
    var filter by remember { mutableStateOf("") }
    val filtered = remember(channels, filter) {
        if (filter.isBlank()) channels.take(200)
        else channels.filter {
            it.name.contains(filter, ignoreCase = true) || it.group.contains(filter, ignoreCase = true)
        }.take(200)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = playlistUrl,
                onValueChange = onPlaylistUrlChange,
                label = { Text("M3U Playlist URL") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onLoadPlaylist) { Text("Load") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            placeholder = { Text("Filter channels or categories...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Tap Load to import Live TV channels.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered) { channel ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onPlayChannel(channel) }) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.LiveTv, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(channel.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    channel.group,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
