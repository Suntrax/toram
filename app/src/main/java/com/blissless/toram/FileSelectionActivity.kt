package com.blissless.toram

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blissless.toram.ui.theme.ToramTheme

class FileSelectionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val torrentName = intent?.getStringExtra("torrent_name") ?: "Unknown"
        val fileCount = intent?.getIntExtra("file_count", 0) ?: 0
        val files = rememberFiles(fileCount)

        setContent {
            ToramTheme {
                FileSelectionScreen(
                    torrentName = torrentName,
                    files = files,
                    onFileSelected = { entry ->
                        val result = Intent().apply {
                            putExtra("selected_index", entry.index)
                            putExtra("selected_name", entry.name)
                            putExtra("selected_path", entry.path)
                            putExtra("selected_size", entry.size)
                        }
                        setResult(RESULT_OK, result)
                        finish()
                    },
                    onCancel = { finish() }
                )
            }
        }
    }

    private fun rememberFiles(count: Int): List<TorrentFileEntry> {
        val b = intent ?: return emptyList()
        return (0 until count).map { i ->
            TorrentFileEntry(
                index = b.getIntExtra("file_${i}_index", i),
                name = b.getStringExtra("file_${i}_name") ?: "Unknown",
                size = b.getLongExtra("file_${i}_size", 0),
                path = b.getStringExtra("file_${i}_path") ?: ""
            )
        }
    }
}

@Composable
fun FileSelectionScreen(
    torrentName: String,
    files: List<TorrentFileEntry>,
    onFileSelected: (TorrentFileEntry) -> Unit,
    onCancel: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Text(
                text = torrentName,
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                text = "Select a file to stream:",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(files) { file ->
                    FileItem(
                        entry = file,
                        onClick = { onFileSelected(file) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun FileItem(entry: TorrentFileEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatSize(entry.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "%.2f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
    }
}
