package com.blissless.toram

import android.content.Context
import android.util.Log
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class TorrentEngine(private val context: Context) {

    private val sessionManager = SessionManager()
    private var rawHandle: org.libtorrent4j.swig.torrent_handle? = null
    private var handle: TorrentHandle? = null
    private val listeners = CopyOnWriteArrayList<EngineListener>()
    private var pollThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    // Streaming priority state
    private var streamingFirstPiece = 0
    private var streamingLastPiece = 0
    private var streamingWindowStart = 0
    private var streamingWindowEnd = 0
    private var lastAdvancedPiece = -1
    private var streamingPrioritiesSet = false
    private var pendingStreamFileIndex = -1

    fun getSaveDir(): File {
        val dir = File(context.cacheDir, "torrents")
        dir.mkdirs()
        return dir
    }

    private var pendingTi: TorrentInfo? = null

    interface EngineListener {
        fun onMetadataReceived(meta: TorrentMeta) {}
        fun onProgress(downloaded: Long, total: Long) {}
        fun onFinished() {}
        fun onError(message: String) {}
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        val sp = SettingsPack()
        sp.setEnableDht(true)
        sp.setEnableLsd(true)
        sp.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
        sessionManager.start(SessionParams(sp))
        sessionManager.startDht()
    }

    fun addTorrentFromFile(file: File): TorrentMeta? {
        return try {
            val ti = TorrentInfo(file)
            pendingTi = ti
            buildMeta(ti)
        } catch (e: Exception) {
            listeners.forEach { it.onError("Failed to parse torrent: ${e.message}") }
            null
        }
    }

    fun addTorrentFromMagnet(uri: String) {
        try {
            val atp = AddTorrentParams.parseMagnetUri(uri)
            atp.setSavePath(getSaveDir().absolutePath)
            val ec = error_code()
            Log.d(TAG, "Adding magnet to session: ${uri.take(60)}...")
            val raw = sessionManager.swig().add_torrent(atp.swig(), ec)
            if (ec.failed()) {
                val msg = "Magnet add failed: ${ec.message()}"
                Log.e(TAG, msg)
                listeners.forEach { it.onError(msg) }
                return
            }
            rawHandle = raw
            handle = TorrentHandle(raw)
            Log.d(TAG, "Magnet added, handle valid=${raw.is_valid()}")

            // Enable sequential mode IMMEDIATELY when handle is created.
            // This prevents libtorrent from requesting random pieces before
            // we get a chance to set priorities in startDownload().
            rawHandle?.set_sequential_range(0, Int.MAX_VALUE)
            Log.d(TAG, "Sequential mode enabled on magnet handle")

            startPolling()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add magnet", e)
            listeners.forEach { it.onError("Failed to add magnet: ${e.message}") }
        }
    }

    fun startDownload(fileIndex: Int) {
        Log.d(TAG, "startDownload fileIndex=$fileIndex")

        if (handle == null) {
            val saved = pendingTi
            if (saved != null) {
                Log.d(TAG, "Adding .torrent to session, ${saved.numFiles()} files")
                // Use DEFAULT for the selected file (not TOP_PRIORITY).
                // DEFAULT allows piece-level priorities to take effect for ordering.
                // TOP_PRIORITY would override all piece priorities, making ordering impossible.
                val priorities = Array(saved.numFiles()) { Priority.IGNORE }
                priorities[fileIndex] = Priority.DEFAULT
                val atp = AddTorrentParams()
                atp.setSavePath(getSaveDir().absolutePath)
                atp.setTorrentInfo(saved)
                atp.filePriorities(priorities)
                val ec = error_code()
                val raw = sessionManager.swig().add_torrent(atp.swig(), ec)
                if (ec.failed()) {
                    val msg = "Torrent add failed: ${ec.message()}"
                    Log.e(TAG, msg)
                    listeners.forEach { it.onError(msg) }
                    return
                }
                rawHandle = raw
                handle = TorrentHandle(raw)
                pendingTi = null
                Log.d(TAG, "Torrent added, handle valid=${raw.is_valid()}")
            }
        } else {
            Log.d(TAG, "Using existing handle from magnet, skipping duplicate add")
        }

        val h = handle ?: run { Log.e(TAG, "handle is null in startDownload"); return }

        // Set file priorities: selected file = DEFAULT, rest = IGNORE
        applyFilePriorities(h, fileIndex)

        // Set up streaming-optimized piece priorities and deadlines
        val ti = h.torrentFile()
        if (ti != null) {
            setupStreamingPriorities(h, fileIndex)
        } else {
            // Metadata not available yet (magnet). Will be set up when metadata arrives.
            pendingStreamFileIndex = fileIndex
            Log.d(TAG, "Metadata not yet available, will set streaming priorities later")
        }

        Log.d(TAG, "Starting polling for progress")
        startPolling()
    }

    /**
     * Set up piece priorities and deadlines for streaming playback.
     *
     * Three mechanisms are used together:
     * 1. set_sequential_range - tells libtorrent to prefer sequential ordering
     * 2. Piece priorities - TOP_PRIORITY for the streaming window, DEFAULT for the rest
     * 3. Piece deadlines - forces urgent download of the first N pieces
     *
     * IMPORTANT: The file priority must be DEFAULT (not TOP_PRIORITY) for
     * piece-level priorities to take effect. TOP_PRIORITY file priority
     * overrides all piece priorities, making ordering impossible.
     */
    private fun setupStreamingPriorities(h: TorrentHandle, fileIndex: Int) {
        val ti = h.torrentFile() ?: run {
            Log.w(TAG, "No torrent info yet, deferring streaming priorities")
            pendingStreamFileIndex = fileIndex
            return
        }

        val totalPieces = ti.numPieces()
        if (totalPieces == 0) {
            Log.w(TAG, "No pieces in torrent")
            return
        }

        // Calculate the piece range for the selected file
        val pieceRange = getFilePieceRange(ti, fileIndex)
        if (pieceRange == null) {
            Log.w(TAG, "Could not determine piece range for file $fileIndex, using full range")
            streamingFirstPiece = 0
            streamingLastPiece = totalPieces - 1
        } else {
            streamingFirstPiece = pieceRange.first
            streamingLastPiece = pieceRange.second
        }

        val totalFilePieces = streamingLastPiece - streamingFirstPiece + 1
        streamingWindowStart = streamingFirstPiece
        streamingWindowEnd = minOf(streamingFirstPiece + STREAMING_WINDOW_SIZE, streamingLastPiece + 1)
        lastAdvancedPiece = streamingFirstPiece - 1

        Log.d(TAG, "File $fileIndex piece range: ${streamingFirstPiece}-${streamingLastPiece} " +
                "($totalFilePieces pieces, pieceSize=${ti.pieceLength()} bytes)")

        // 1. Set sequential download mode for the entire file range
        rawHandle?.set_sequential_range(streamingFirstPiece, streamingLastPiece)

        // 2. Set piece priorities: streaming window = TOP_PRIORITY, rest = DEFAULT
        for (i in streamingFirstPiece..streamingLastPiece) {
            val priority = if (i < streamingWindowEnd) Priority.TOP_PRIORITY else Priority.DEFAULT
            try {
                h.piecePriority(i, priority)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set priority for piece $i: ${e.message}")
            }
        }

        // 3. Set deadlines for the first STREAMING_DEADLINE_SIZE pieces.
        //    Deadlines are the STRONGEST mechanism — libtorrent will urgently
        //    request these pieces from all connected peers.
        val headDeadlineCount = minOf(STREAMING_DEADLINE_SIZE, totalFilePieces)
        for (i in 0 until headDeadlineCount) {
            val piece = streamingFirstPiece + i
            try {
                h.setPieceDeadline(piece, i + 1)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set deadline for piece $piece: ${e.message}")
            }
        }

        // 4. Set deadlines for the last 2 pieces (needed for container metadata
        //    in formats like MKV that store index/cues at the end).
        if (totalFilePieces > headDeadlineCount + 2) {
            for (i in maxOf(streamingFirstPiece, streamingLastPiece - 1)..streamingLastPiece) {
                try {
                    h.setPieceDeadline(i, headDeadlineCount + 50)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set deadline for tail piece $i: ${e.message}")
                }
            }
        }

        streamingPrioritiesSet = true
        pendingStreamFileIndex = -1

        Log.d(TAG, "Streaming priorities set: pieces $streamingFirstPiece-${streamingWindowEnd - 1} " +
                "TOP_PRIORITY, ${streamingWindowEnd}-$streamingLastPiece DEFAULT")
        Log.d(TAG, "Deadlines: head pieces $streamingFirstPiece-" +
                "${streamingFirstPiece + headDeadlineCount - 1}, " +
                "tail pieces ${maxOf(streamingFirstPiece, streamingLastPiece - 1)}-$streamingLastPiece")
    }

    /**
     * Advance the streaming priority window as pieces complete.
     * This ensures the download continues sequentially ahead of playback.
     */
    fun advanceStreamingWindow() {
        if (!streamingPrioritiesSet) return
        val h = handle ?: return

        // Find the furthest consecutive completed piece from streamingWindowStart
        var lastConsecutive = streamingWindowStart - 1
        while (lastConsecutive < streamingLastPiece) {
            val nextPiece = lastConsecutive + 1
            if (havePiece(nextPiece)) {
                lastConsecutive = nextPiece
            } else {
                break
            }
        }

        // If we've completed new pieces, advance the window
        if (lastConsecutive > lastAdvancedPiece) {
            lastAdvancedPiece = lastConsecutive
            streamingWindowStart = lastConsecutive + 1

            // Expand the window to maintain STREAMING_WINDOW_SIZE pieces ahead
            val newWindowEnd = minOf(streamingWindowStart + STREAMING_WINDOW_SIZE, streamingLastPiece + 1)
            if (newWindowEnd > streamingWindowEnd) {
                for (i in streamingWindowEnd until newWindowEnd) {
                    try {
                        h.piecePriority(i, Priority.TOP_PRIORITY)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to set priority for piece $i: ${e.message}")
                    }
                }
                streamingWindowEnd = newWindowEnd
            }

            // Set deadlines for the next few uncompleted pieces (most urgent)
            val deadlineStart = streamingWindowStart
            val deadlineEnd = minOf(streamingWindowStart + STREAMING_DEADLINE_SIZE, streamingWindowEnd)
            for (i in deadlineStart until deadlineEnd) {
                try {
                    h.setPieceDeadline(i, (i - streamingWindowStart) + 1)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set deadline for piece $i: ${e.message}")
                }
            }

            Log.d(TAG, "Streaming window advanced: completed up to piece $lastConsecutive, " +
                    "window now $streamingWindowStart-${streamingWindowEnd - 1}")
        }
    }

    /**
     * Calculate the piece range [firstPiece, lastPiece] for a given file index.
     */
    private fun getFilePieceRange(ti: TorrentInfo, fileIndex: Int): Pair<Int, Int>? {
        val fs = ti.files()
        val fileSize = fs.fileSize(fileIndex)
        if (fileSize <= 0) return null

        val pieceLength = ti.pieceLength()

        var offset = 0L
        for (i in 0 until fileIndex) {
            offset += fs.fileSize(i)
        }

        val firstPiece = (offset / pieceLength).toInt()
        val lastPiece = ((offset + fileSize - 1) / pieceLength).toInt()

        return Pair(firstPiece, lastPiece)
    }

    fun getFileSize(fileIndex: Int): Long {
        val h = handle ?: return 0L
        return try {
            h.torrentFile()?.files()?.fileSize(fileIndex) ?: 0L
        } catch (_: Exception) { 0L }
    }

    fun getContiguousDownloadedBytes(): Long {
        val h = handle ?: return 0L
        val ti = h.torrentFile() ?: return 0L

        val fileIndex = findSelectedFileIndex(h, ti) ?: return 0L
        val fileSize = ti.files().fileSize(fileIndex)
        if (fileSize <= 0) return 0L

        val range = getFilePieceRange(ti, fileIndex) ?: return 0L
        val pieceLength = ti.pieceLength().toLong()

        var contiguousPieces = 0
        for (i in range.first..range.second) {
            if (havePiece(i)) {
                contiguousPieces++
            } else {
                break
            }
        }

        if (contiguousPieces == 0) return 0L

        val totalFilePieces = range.second - range.first + 1
        if (contiguousPieces >= totalFilePieces) {
            return fileSize
        }

        return contiguousPieces.toLong() * pieceLength
    }

    private fun findSelectedFileIndex(h: TorrentHandle, ti: TorrentInfo): Int? {
        val fs = ti.files()
        for (i in 0 until fs.numFiles()) {
            try {
                val p = h.filePriority(i)
                if (p != Priority.IGNORE) return i
            } catch (_: Exception) {}
        }
        var maxSize = 0L
        var maxIdx = 0
        for (i in 0 until fs.numFiles()) {
            val size = fs.fileSize(i)
            if (size > maxSize) {
                maxSize = size
                maxIdx = i
            }
        }
        return if (maxSize > 0) maxIdx else null
    }

    fun setFilePriority(fileIndex: Int, priority: Priority) {
        handle?.filePriority(fileIndex, priority)
    }

    fun setSequentialDownload(enabled: Boolean) {
        if (enabled) {
            val totalPieces = try { handle?.torrentFile()?.numPieces() ?: 0 } catch (_: Exception) { 0 }
            if (totalPieces > 0) {
                rawHandle?.set_sequential_range(0, totalPieces - 1)
            } else {
                rawHandle?.set_sequential_range(0, Int.MAX_VALUE)
            }
        } else {
            rawHandle?.set_sequential_range(Int.MAX_VALUE, 0)
        }
    }

    fun setPieceDeadline(piece: Int, deadline: Int) {
        handle?.setPieceDeadline(piece, deadline)
    }

    fun getFileSavePath(fileIndex: Int): String? {
        val h = handle ?: return null
        return try {
            val ti = h.torrentFile() ?: return null
            ti.files().filePath(fileIndex, getSaveDir().absolutePath)
        } catch (_: Exception) { null }
    }

    fun getNumPieces(): Int {
        return try {
            handle?.torrentFile()?.numPieces() ?: 1
        } catch (_: Exception) { 1 }
    }

    fun getPieceSize(): Long {
        val ti = try { handle?.torrentFile() } catch (_: Exception) { null } ?: return 4L * 1024 * 1024
        val total = ti.totalSize()
        val np = ti.numPieces()
        return (total + np - 1) / np
    }

    fun havePiece(pieceIndex: Int): Boolean {
        return try {
            rawHandle?.have_piece(pieceIndex) ?: false
        } catch (_: Exception) { false }
    }

    fun addListener(l: EngineListener) = listeners.add(l)
    fun removeListener(l: EngineListener) = listeners.remove(l)

    fun removeCurrentTorrent() {
        pollThread?.interrupt()
        pollThread = null
        rawHandle?.let {
            try { sessionManager.swig().remove_torrent(it) } catch (_: Exception) {}
        }
        handle = null
        rawHandle = null
        pendingTi = null
        listeners.clear()
        resetStreamingState()
    }

    fun clearCache() {
        val dir = getSaveDir()
        try {
            dir.listFiles()?.forEach { file ->
                file.deleteRecursively()
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        isRunning.set(false)
        pollThread?.interrupt()
        rawHandle?.let {
            try { sessionManager.swig().remove_torrent(it) } catch (_: Exception) {}
        }
        sessionManager.stop()
    }

    private fun resetStreamingState() {
        streamingFirstPiece = 0
        streamingLastPiece = 0
        streamingWindowStart = 0
        streamingWindowEnd = 0
        lastAdvancedPiece = -1
        streamingPrioritiesSet = false
        pendingStreamFileIndex = -1
    }

    private fun applyFilePriorities(h: TorrentHandle, selectedIndex: Int) {
        try {
            val ti = h.torrentFile()
            if (ti != null) {
                val nf = ti.numFiles()
                Log.d(TAG, "Priorities: $nf files, index $selectedIndex = DEFAULT, rest = IGNORE")
                for (i in 0 until nf) {
                    h.filePriority(i, if (i == selectedIndex) Priority.DEFAULT else Priority.IGNORE)
                }
                return
            }
            Log.w(TAG, "torrentFile() returned null, fallback to single priority")
        } catch (e: Exception) {
            Log.e(TAG, "applyFilePriorities error", e)
        }
        h.filePriority(selectedIndex, Priority.DEFAULT)
    }

    private fun startPolling() {
        if (pollThread?.isAlive == true && pollThread?.name == "torrent-poll") {
            Log.d(TAG, "Polling already active, skipping")
            return
        }
        pollThread?.interrupt()
        pollThread = Thread {
            Log.d(TAG, "Polling thread started")
            var metaNotified = false
            var loggedState = -1
            var lastAdvanceTime = 0L
            while (isRunning.get()) {
                try {
                    Thread.sleep(500)
                    val h = handle ?: continue
                    val st = h.status()
                    val state = st.state()
                    if (state.ordinal != loggedState) {
                        loggedState = state.ordinal
                        Log.d(TAG, "Torrent state: ${state.name}, downloaded=${st.totalWantedDone()}/${st.totalWanted()}")
                    }
                    if (!metaNotified && st.hasMetadata()) {
                        metaNotified = true
                        Log.d(TAG, "Metadata received")
                        val ti = h.torrentFile()
                        if (ti != null) {
                            val meta = buildMeta(ti)
                            Log.d(TAG, "Files: ${meta.files.size}")
                            listeners.forEach { it.onMetadataReceived(meta) }

                            if (!streamingPrioritiesSet && pendingStreamFileIndex >= 0) {
                                Log.d(TAG, "Setting up streaming priorities for pending file $pendingStreamFileIndex")
                                setupStreamingPriorities(h, pendingStreamFileIndex)
                            }
                        }
                    }
                    if (metaNotified) {
                        listeners.forEach { it.onProgress(st.totalWantedDone(), st.totalWanted()) }
                        if (st.isFinished() || st.isSeeding()) {
                            Log.d(TAG, "Torrent finished!")
                            listeners.forEach { it.onFinished() }
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastAdvanceTime > 2000) {
                            advanceStreamingWindow()
                            lastAdvanceTime = now
                        }
                    }
                } catch (e: InterruptedException) { break }
                catch (e: Exception) {
                    Log.e(TAG, "Poll error", e)
                }
            }
            Log.d(TAG, "Polling thread exiting")
        }.apply {
            isDaemon = true
            name = "torrent-poll"
            start()
        }
    }

    private fun buildMeta(ti: TorrentInfo): TorrentMeta {
        val fs = ti.files()
        val entries = (0 until fs.numFiles()).map { i ->
            TorrentFileEntry(i, fs.fileName(i), fs.fileSize(i), fs.filePath(i))
        }
        return TorrentMeta(ti.name(), entries)
    }

    companion object {
        private const val TAG = "TorrentEngine"
        private const val STREAMING_WINDOW_SIZE = 30
        private const val STREAMING_DEADLINE_SIZE = 15
    }
}