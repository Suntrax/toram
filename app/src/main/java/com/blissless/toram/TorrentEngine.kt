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
    @Volatile private var rawHandle: org.libtorrent4j.swig.torrent_handle? = null
    @Volatile private var handle: TorrentHandle? = null
    private val listeners = CopyOnWriteArrayList<EngineListener>()
    private var pollThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

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
                val priorities = Array(saved.numFiles()) { Priority.IGNORE }
                priorities[fileIndex] = Priority.TOP_PRIORITY
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
        applyFilePriorities(h, fileIndex)

        val totalPieces = try { h.torrentFile()?.numPieces() ?: 0 } catch (_: Exception) { 0 }

        if (totalPieces > 0) {
            val lastPiece = totalPieces - 1
            rawHandle?.set_sequential_range(0, lastPiece)

            val headPieceCount = minOf(4, totalPieces)
            for (i in 0 until headPieceCount) {
                h.setPieceDeadline(i, 1)
            }

            val tailPieceCount = minOf(4, totalPieces)
            val tailStart = maxOf(0, totalPieces - tailPieceCount)
            for (i in tailStart until totalPieces) {
                h.setPieceDeadline(i, 100)
            }
            Log.d(TAG, "Set sequential range 0-$lastPiece, head deadlines 0-${headPieceCount - 1}, tail deadlines $tailStart-$lastPiece")
        } else {
            rawHandle?.set_sequential_range(0, Int.MAX_VALUE)
            Log.w(TAG, "Unknown piece count, using Int.MAX_VALUE for sequential range")
        }

        Log.d(TAG, "Starting polling for progress")
        startPolling()
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

    fun getFileSize(fileIndex: Int): Long {
        val h = handle ?: return 0L
        return try {
            val ti = h.torrentFile() ?: return 0L
            ti.files().fileSize(fileIndex)
        } catch (_: Exception) { 0L }
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

    /**
     * Returns the actual torrent piece length.
     * This is different from getPieceSize() which returns an average.
     */
    fun getTorrentPieceLength(): Long {
        val ti = try { handle?.torrentFile() } catch (_: Exception) { null } ?: return 4L * 1024 * 1024
        return ti.pieceLength().toLong()
    }

    fun havePiece(pieceIndex: Int): Boolean {
        return try {
            rawHandle?.have_piece(pieceIndex) ?: false
        } catch (_: Exception) { false }
    }

    /**
     * Returns the byte offset of a file within the torrent.
     * For file at index N, this sums up sizes of files 0..N-1.
     * This is needed to map file-relative offsets to torrent-wide piece indices.
     */
    fun getFileByteOffset(fileIndex: Int): Long {
        val ti = try { handle?.torrentFile() } catch (_: Exception) { null } ?: return 0L
        val fs = ti.files()
        var offset = 0L
        for (i in 0 until fileIndex) {
            offset += fs.fileSize(i)
        }
        return offset
    }

    /**
     * Returns contiguous downloaded bytes from the START of the
     * specified file. Works correctly for both single-file and
     * multi-file torrents by calculating the file's piece range
     * within the torrent.
     *
     * Key: for multi-file torrents, the selected file might not
     * start at piece 0. Pieces before the file's first piece
     * belong to other files (IGNORE priority) and will never be
     * downloaded, so we must start counting from the file's
     * own first piece.
     */
    fun getContiguousDownloadedBytes(fileIndex: Int): Long {
        val h = handle ?: return 0L
        val raw = rawHandle ?: return 0L
        val ti = try { h.torrentFile() } catch (_: Exception) { null } ?: return 0L

        val fs = ti.files()
        val pieceLen = ti.pieceLength().toLong()
        val fileSize = fs.fileSize(fileIndex)

        if (fileSize <= 0) return 0L

        // Calculate file's byte offset in the torrent
        val fileByteOffset = getFileByteOffset(fileIndex)

        // First and last piece that contain data for this file
        val firstPiece = (fileByteOffset / pieceLen).toInt()
        val lastPiece = ((fileByteOffset + fileSize - 1) / pieceLen).toInt()

        // Walk from the file's first piece and check contiguity
        var contiguousPieces = 0
        for (i in firstPiece..lastPiece) {
            try {
                if (raw.have_piece(i)) {
                    contiguousPieces++
                } else {
                    break
                }
            } catch (_: Exception) { break }
        }

        if (contiguousPieces == 0) return 0L

        // Calculate how many file bytes are covered by contiguous pieces
        val lastContiguousPiece = firstPiece + contiguousPieces - 1
        val contiguousEndInTorrent = (lastContiguousPiece + 1).toLong() * pieceLen
        val contiguousEndInFile = contiguousEndInTorrent - fileByteOffset
        return minOf(contiguousEndInFile, fileSize)
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

    private fun applyFilePriorities(h: TorrentHandle, selectedIndex: Int) {
        try {
            val ti = h.torrentFile()
            if (ti != null) {
                val nf = ti.numFiles()
                Log.d(TAG, "Priorities: $nf files, index $selectedIndex = TOP, rest = IGNORE")
                for (i in 0 until nf) {
                    h.filePriority(i, if (i == selectedIndex) Priority.TOP_PRIORITY else Priority.IGNORE)
                }
                return
            }
            Log.w(TAG, "torrentFile() returned null, fallback to single priority")
        } catch (e: Exception) {
            Log.e(TAG, "applyFilePriorities error", e)
        }
        h.filePriority(selectedIndex, Priority.TOP_PRIORITY)
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
                        }
                    }
                    if (metaNotified) {
                        listeners.forEach { it.onProgress(st.totalWantedDone(), st.totalWanted()) }
                        if (st.isFinished() || st.isSeeding()) {
                            Log.d(TAG, "Torrent finished!")
                            listeners.forEach { it.onFinished() }
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
    }
}