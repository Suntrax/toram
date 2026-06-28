package com.blissless.toram

data class TorrentFileEntry(
    val index: Int,
    val name: String,
    val size: Long,
    val path: String
)

data class TorrentMeta(
    val name: String,
    val files: List<TorrentFileEntry>
)

enum class TorrentStatus {
    ADDING, DOWNLOADING_METADATA, METADATA_RECEIVED, DOWNLOADING, FINISHED, ERROR
}
