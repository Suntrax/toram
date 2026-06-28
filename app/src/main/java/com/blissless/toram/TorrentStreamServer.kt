package com.blissless.toram

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

class TorrentStreamServer(private val saveDir: File) {

    @Volatile var running = false
    private var serverSocket: ServerSocket? = null
    private var safeBytes: () -> Long = { 0L }
    private var pieceChecker: ((Int) -> Boolean)? = null
    private var pieceSize: Long = 4L * 1024 * 1024

    @Volatile
    private var totalFileSize: Long = 0L

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "stream-client").also { it.isDaemon = true }
    }

    fun setSafeBytesProvider(provider: () -> Long) {
        safeBytes = provider
    }

    fun setPieceChecker(checker: (Int) -> Boolean) {
        pieceChecker = checker
    }

    fun setPieceSize(size: Long) {
        pieceSize = size
    }

    fun setTotalFileSize(size: Long) {
        totalFileSize = size
    }

    fun start(port: Int = 0): Int {
        serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        running = true
        val actualPort = serverSocket!!.localPort
        Log.d(TAG, "Server started on port $actualPort")
        thread(name = "stream-server") {
            while (running) {
                try {
                    val client = serverSocket!!.accept()
                    executor.execute { handleClient(client) }
                } catch (e: java.io.IOException) {
                    if (running) Log.e(TAG, "Accept error", e)
                }
            }
        }
        return actualPort
    }

    fun stop() {
        running = false
        executor.shutdownNow()
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 120000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), "UTF-8"))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val requestPath = URLDecoder.decode(parts[1], "UTF-8")

            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val idx = line!!.indexOf(':')
                if (idx > 0) {
                    headers[line!!.substring(0, idx).trim().lowercase()] =
                        line!!.substring(idx + 1).trim()
                }
            }

            val relativePath = requestPath.trimStart('/')
            val file = File(saveDir, relativePath)

            Log.d(TAG, "$method $requestPath, Range: ${headers["range"] ?: "none"}")

            // Wait for the file to appear on disk (up to 60 seconds)
            if (!file.exists()) {
                val waitDeadline = System.nanoTime() + 60_000_000_000L
                while (System.nanoTime() < waitDeadline && running) {
                    if (file.exists()) break
                    try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                }
                if (!file.exists()) {
                    Log.w(TAG, "File not found after waiting: $relativePath (base=$saveDir)")
                    sendError(client, 404, "Not Found")
                    return
                }
                Log.d(TAG, "File appeared after waiting: $relativePath")
            }

            // Use totalFileSize from metadata if available
            val fileLength = if (totalFileSize > 0) totalFileSize else file.length()
            if (fileLength <= 0) {
                Log.w(TAG, "Empty file: $relativePath (disk=${file.length()}, meta=$totalFileSize)")
                sendError(client, 500, "Empty file")
                return
            }

            val rangeHeader = headers["range"]
            var startOffset = 0L
            var endOffset = fileLength - 1
            val isRange: Boolean

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val range = rangeHeader.substring(6).trim()
                val dashIdx = range.indexOf('-')
                if (dashIdx > 0) {
                    startOffset = range.substring(0, dashIdx).toLongOrNull() ?: 0L
                    val endStr = range.substring(dashIdx + 1)
                    if (endStr.isNotEmpty()) {
                        endOffset = endStr.toLongOrNull() ?: (fileLength - 1)
                    }
                    startOffset = startOffset.coerceIn(0, fileLength - 1)
                    endOffset = endOffset.coerceIn(startOffset, fileLength - 1)
                    isRange = true
                } else if (dashIdx == 0) {
                    val suffixLen = range.substring(1).toLongOrNull() ?: fileLength
                    startOffset = maxOf(0L, fileLength - suffixLen)
                    isRange = true
                } else {
                    isRange = false
                }
            } else {
                isRange = false
            }

            if (method == "HEAD") {
                val resp = buildHeaders(if (isRange) 206 else 200, endOffset - startOffset + 1, file.name, isRange, startOffset, endOffset, fileLength)
                client.getOutputStream().write(resp.toByteArray())
                client.getOutputStream().flush()
                client.close()
                return
            }

            val safeNow = minOf(safeBytes(), fileLength)
            if (startOffset >= safeNow) {
                val startPiece = (startOffset / pieceSize).toInt()
                val endPiece = (minOf(endOffset, fileLength - 1) / pieceSize).toInt()
                if (pieceChecker?.invoke(startPiece) != true || (startPiece != endPiece && pieceChecker?.invoke(endPiece) != true)) {
                    Log.w(TAG, "Piece not available for range $startOffset-$endOffset, blocking for up to 30s")
                    val deadline = System.nanoTime() + 30_000_000_000L
                    var available = false
                    while (System.nanoTime() < deadline && running) {
                        val p = pieceChecker
                        if (p != null && p(startPiece) && (startPiece == endPiece || p(endPiece))) {
                            available = true
                            break
                        }
                        try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                    }
                    if (!available) {
                        sendError(client, 503, "Not yet available")
                        return
                    }
                    Log.d(TAG, "Piece became available after waiting")
                }
            }

            val actualEnd = minOf(endOffset, fileLength - 1)
            val sendLength = actualEnd - startOffset + 1

            val resp = buildHeaders(if (isRange) 206 else 200, sendLength, file.name, isRange, startOffset, actualEnd, fileLength)
            val out = client.getOutputStream()
            out.write(resp.toByteArray())

            val raf = RandomAccessFile(file, "r")
            try {
                raf.seek(startOffset)
                val buf = ByteArray(262144)
                var remaining = sendLength
                var pos = startOffset
                var waitStart = System.nanoTime()

                while (remaining > 0 && running) {
                    val currentSafe = minOf(safeBytes(), fileLength)
                    var canRead: Long
                    if (pos < currentSafe) {
                        canRead = minOf(remaining, currentSafe - pos)
                    } else {
                        val p = (pos / pieceSize).toInt()
                        if (pieceChecker?.invoke(p) == true) {
                            val pieceEnd = ((p + 1) * pieceSize).coerceAtMost(fileLength)
                            canRead = minOf(remaining, pieceEnd - pos)
                        } else {
                            canRead = 0L
                        }
                    }
                    if (canRead <= 0) {
                        if (System.nanoTime() - waitStart > 30_000_000_000L) {
                            Log.w(TAG, "Stream timed out waiting for data at pos=$pos")
                            break
                        }
                        try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                        continue
                    }
                    waitStart = System.nanoTime()

                    val toRead = minOf(buf.size.toLong(), canRead).toInt()
                    val read = raf.read(buf, 0, toRead)
                    if (read < 0) break
                    out.write(buf, 0, read)
                    remaining -= read
                    pos += read
                }
            } finally {
                raf.close()
            }

            out.flush()
            client.close()
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Client error", e)
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun buildHeaders(status: Int, contentLen: Long, fileName: String, isRange: Boolean, start: Long, end: Long, fileLen: Long): String {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status ${if (status == 206) "Partial Content" else "OK"}\r\n")
        sb.append("Content-Type: ${getMimeType(fileName)}\r\n")
        sb.append("Content-Length: $contentLen\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Connection: close\r\n")
        if (isRange) {
            sb.append("Content-Range: bytes $start-$end/$fileLen\r\n")
        }
        sb.append("\r\n")
        return sb.toString()
    }

    private fun sendError(client: Socket, code: Int, msg: String) {
        Log.w(TAG, "Sending error $code $msg")
        val resp = "HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        try {
            client.getOutputStream().write(resp.toByteArray())
            client.getOutputStream().flush()
        } catch (_: Exception) {}
        try { client.close() } catch (_: Exception) {}
    }

    private fun thread(name: String, action: () -> Unit): Thread {
        return Thread(action, name).also { it.isDaemon = true; it.start() }
    }

    companion object {
        private const val TAG = "StreamServer"

        fun getMimeType(filename: String): String {
            val ext = filename.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "mkv" -> "video/x-matroska"
                "mp4" -> "video/mp4"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                "ts" -> "video/mp2t"
                "m4v" -> "video/x-m4v"
                else -> "application/octet-stream"
            }
        }
    }
}