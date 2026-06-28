package com.blissless.toram

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blissless.toram.ui.theme.ToramTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var engine: TorrentEngine

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleTorrentFile(it) }
    }

    private val fileSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            val index = data.getIntExtra("selected_index", -1)
            val name = data.getStringExtra("selected_name") ?: ""
            val path = data.getStringExtra("selected_path") ?: ""
            val size = data.getLongExtra("selected_size", 0)
            if (index >= 0) {
                val entry = TorrentFileEntry(index, name, size, path)
                startPlayer(entry)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = (application as ToramApplication).engine
        enableEdgeToEdge()
        setContent {
            ToramTheme {
                MainScreen(
                    onMagnetSubmit = { magnet -> handleMagnet(magnet) },
                    onPickFile = { filePickerLauncher.launch("*/*") },
                    onClearCache = {
                        engine.clearCache()
                        Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    private fun handleMagnet(magnet: String) {
        engine.removeCurrentTorrent()
        engine.start()
        engine.addListener(object : TorrentEngine.EngineListener {
            override fun onMetadataReceived(meta: TorrentMeta) {
                runOnUiThread { onTorrentReady(meta) }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: $message", Toast.LENGTH_LONG).show()
                }
            }
        })
        engine.addTorrentFromMagnet(magnet)
        Toast.makeText(this, "Adding torrent...", Toast.LENGTH_SHORT).show()
    }

    private fun handleTorrentFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val tempFile = File(cacheDir, "torrent_${System.nanoTime()}.torrent")
            inputStream?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            engine.removeCurrentTorrent()
            engine.start()
            engine.addListener(object : TorrentEngine.EngineListener {
                override fun onError(message: String) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Error: $message", Toast.LENGTH_LONG).show()
                    }
                }
            })
            val meta = engine.addTorrentFromFile(tempFile)
            if (meta != null) {
                onTorrentReady(meta)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onTorrentReady(meta: TorrentMeta) {
        if (meta.files.size == 1) {
            startPlayer(meta.files.first())
        } else {
            val intent = Intent(this, FileSelectionActivity::class.java).apply {
                putExtra("torrent_name", meta.name)
                putExtra("file_count", meta.files.size)
                meta.files.forEachIndexed { i, f ->
                    putExtra("file_${i}_name", f.name)
                    putExtra("file_${i}_size", f.size)
                    putExtra("file_${i}_index", f.index)
                    putExtra("file_${i}_path", f.path)
                }
            }
            fileSelectionLauncher.launch(intent)
        }
    }

    private fun startPlayer(entry: TorrentFileEntry) {
        engine.startDownload(entry.index)
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("file_index", entry.index)
            putExtra("file_name", entry.name)
            putExtra("file_path", entry.path)
            putExtra("file_size", entry.size)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
fun MainScreen(
    onMagnetSubmit: (String) -> Unit,
    onPickFile: () -> Unit,
    onClearCache: () -> Unit = {}
) {
    var magnetLink by remember { mutableStateOf("") }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Torrent Player",
                style = MaterialTheme.typography.headlineLarge
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = magnetLink,
                onValueChange = { magnetLink = it },
                label = { Text("Magnet Link") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (magnetLink.isNotBlank()) {
                            onMagnetSubmit(magnetLink.trim())
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = magnetLink.isNotBlank()
                ) {
                    Text("Stream Magnet")
                }

                OutlinedButton(
                    onClick = onPickFile,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Pick .torrent")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = onClearCache,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Delete Cache")
            }
        }
    }
}
