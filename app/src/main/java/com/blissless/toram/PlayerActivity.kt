package com.blissless.toram

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blissless.toram.ui.theme.ToramTheme
import java.io.File
import java.net.URLEncoder

class PlayerActivity : ComponentActivity() {

    private var playerReady by mutableStateOf(false)
    private var downloadedBytes by mutableLongStateOf(0L)
    private var totalBytes by mutableLongStateOf(0L)

    private val pollHandler = Handler(Looper.getMainLooper())
    private var engineListener: TorrentEngine.EngineListener? = null
    private var streamServer: TorrentStreamServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val fileIndex = intent?.getIntExtra("file_index", 0) ?: 0
        val fileName = intent?.getStringExtra("file_name") ?: "Unknown"

        val app = application as ToramApplication
        app.engine.setSequentialDownload(true)
        app.engine.setFilePriority(fileIndex, org.libtorrent4j.Priority.TOP_PRIORITY)

        val saveFile = File(app.engine.getFileSavePath(fileIndex) ?: "")
        totalBytes = intent?.getLongExtra("file_size", 0) ?: 0

        if (totalBytes <= 0) {
            totalBytes = app.engine.getFileSize(fileIndex)
        }

        engineListener = object : TorrentEngine.EngineListener {
            override fun onProgress(downloaded: Long, total: Long) {
                runOnUiThread {
                    if (total > 0) totalBytes = total
                    downloadedBytes = downloaded
                }
            }
            override fun onFinished() {
                // CRITICAL: When download finishes, start playback immediately.
                // This is the fallback that ensures playback always starts
                // once the file is fully downloaded.
                runOnUiThread {
                    if (!playerReady) {
                        Log.d("PlayerActivity", "Download finished, starting playback")
                        playerReady = true
                        Toast.makeText(this@PlayerActivity, "Starting playback", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        app.engine.addListener(engineListener!!)

        val saveDir = app.engine.getSaveDir()
        val relativePath = saveFile.absolutePath.substring(
            saveDir.absolutePath.trimEnd(File.separatorChar).length + 1
        )
        val encodedPath = relativePath.split(File.separatorChar).joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

        val server = TorrentStreamServer(saveDir)
        server.setTotalFileSize(totalBytes)

        // CRITICAL: Set the file's byte offset within the torrent.
        // For multi-file torrents, the selected file doesn't start at
        // piece 0. The server needs this to correctly map file positions
        // to torrent-wide piece indices when checking piece availability.
        server.setFileByteOffset(app.engine.getFileByteOffset(fileIndex))

        server.setSafeBytesProvider { app.engine.getContiguousDownloadedBytes(fileIndex) }
        server.setPieceSize(app.engine.getTorrentPieceLength())
        server.setPieceChecker { pieceIndex -> app.engine.havePiece(pieceIndex) }
        val port = server.start(0)
        streamServer = server

        val httpUrl = "http://127.0.0.1:$port/$encodedPath"
        Log.d("PlayerActivity", "Stream URL: $httpUrl, totalSize: $totalBytes, fileByteOffset: ${app.engine.getFileByteOffset(fileIndex)}")

        setContent {
            ToramTheme {
                PlayerScreen(
                    fileName = fileName,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    playerReady = playerReady,
                    streamUrl = httpUrl
                )
            }
        }

        // Start playback as soon as we have ~1MB of contiguous data
        // for the selected file. The onFinished() callback above
        // serves as a fallback when the download completes.
        val initialBufferBytes = 1L * 1024 * 1024 // 1MB

        pollHandler.post(object : Runnable {
            override fun run() {
                if (playerReady) return // Already started

                val contiguousBytes = app.engine.getContiguousDownloadedBytes(fileIndex)
                Log.d("PlayerActivity", "Contiguous: $contiguousBytes bytes for file $fileIndex, threshold: $initialBufferBytes")

                if (contiguousBytes >= initialBufferBytes || (totalBytes > 0 && contiguousBytes >= totalBytes)) {
                    playerReady = true
                    Toast.makeText(this@PlayerActivity, "Starting playback", Toast.LENGTH_SHORT).show()
                } else {
                    pollHandler.postDelayed(this, 300)
                }
            }
        })
    }

    override fun onDestroy() {
        pollHandler.removeCallbacksAndMessages(null)
        engineListener?.let { (application as ToramApplication).engine.removeListener(it) }
        streamServer?.stop()
        streamServer = null
        super.onDestroy()
    }
}

@Composable
fun PlayerScreen(
    fileName: String,
    downloadedBytes: Long,
    totalBytes: Long,
    playerReady: Boolean,
    streamUrl: String
) {
    val progressPct = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes) else 0

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                playerReady -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ExoPlayerVideoPlayer(
                            url = streamUrl,
                            modifier = Modifier.fillMaxSize()
                        )

                        if (downloadedBytes < totalBytes) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Downloading: $progressPct%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${formatSize(downloadedBytes)} / ${formatSize(totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()

                            Text(
                                text = "Buffering: $fileName",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 24.dp)
                            )

                            Text(
                                text = if (totalBytes > 0) "${formatSize(downloadedBytes)} / ${formatSize(totalBytes)} ($progressPct%)"
                                else "Fetching metadata...",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
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