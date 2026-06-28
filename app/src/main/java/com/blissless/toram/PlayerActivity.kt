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

        val metaFileSize = app.engine.getFileSize(fileIndex)
        totalBytes = if (metaFileSize > 0) metaFileSize else (intent?.getLongExtra("file_size", 0) ?: 0)

        val saveFile = File(app.engine.getFileSavePath(fileIndex) ?: "")

        engineListener = object : TorrentEngine.EngineListener {
            override fun onProgress(downloaded: Long, total: Long) {
                runOnUiThread {
                    if (total > 0) totalBytes = total
                    downloadedBytes = downloaded
                }
            }
            override fun onFinished() {
                runOnUiThread {
                    if (!playerReady) {
                        playerReady = true
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

        // Use CONTIGUOUS bytes from the start, not total downloaded bytes.
        // ExoPlayer needs consecutive bytes from position 0.
        server.setSafeBytesProvider { app.engine.getContiguousDownloadedBytes() }
        server.setPieceSize(app.engine.getPieceSize())
        server.setPieceChecker { pieceIndex -> app.engine.havePiece(pieceIndex) }

        if (totalBytes > 0) {
            server.setTotalFileSize(totalBytes)
        }

        val port = server.start(0)
        streamServer = server

        val httpUrl = "http://127.0.0.1:$port/$encodedPath"
        Log.d("PlayerActivity", "Stream URL: $httpUrl, totalSize=$totalBytes")

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

        // Start playback once we have 2MB contiguous from the beginning
        pollHandler.post(object : Runnable {
            override fun run() {
                if (playerReady) return

                val contiguous = app.engine.getContiguousDownloadedBytes()
                val headReady = contiguous >= 2L * 1024 * 1024

                if (headReady) {
                    playerReady = true
                    Toast.makeText(this@PlayerActivity, "Starting playback", Toast.LENGTH_SHORT).show()
                } else {
                    pollHandler.postDelayed(this, 400)
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