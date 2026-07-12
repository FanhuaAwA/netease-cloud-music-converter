package com.fanhua.yuncheng

import android.net.Uri

enum class AudioKind(val label: String, val extension: String, val lossless: Boolean) {
    FLAC("FLAC", "flac", true), MP3("MP3", "mp3", false), WAV("WAV", "wav", true),
    M4A("M4A", "m4a", false), OGG("OGG", "ogg", false), OPUS("OPUS", "opus", false),
    AAC("AAC", "aac", false), APE("APE", "ape", true), UNKNOWN("未知", "bin", false)
}

data class Song(
    val uri: Uri,
    val fileName: String,
    val size: Long,
    val isNcm: Boolean,
    val kind: AudioKind = AudioKind.UNKNOWN,
    val title: String = fileName.substringBeforeLast('.'),
    val artist: String = "",
    val album: String = "",
    val lyricUri: Uri? = null,
    val selected: Boolean = true,
    val state: WorkState = WorkState.Ready,
)

sealed interface WorkState {
    data object Ready : WorkState
    data object Running : WorkState
    data object Done : WorkState
    data class Failed(val reason: String) : WorkState
}

data class UiState(
    val sourceUri: Uri? = null,
    val outputUri: Uri? = null,
    val songs: List<Song> = emptyList(),
    val scanning: Boolean = false,
    val converting: Boolean = false,
    val concurrency: Int = 2,
    val message: String = "请选择网易云音乐目录",
) {
    val selectedCount get() = songs.count { it.selected }
    val ncmCount get() = songs.count { it.isNcm }
    val flacCount get() = songs.count { it.kind == AudioKind.FLAC }
    val mp3Count get() = songs.count { it.kind == AudioKind.MP3 }
    val otherCount get() = songs.count { it.kind != AudioKind.FLAC && it.kind != AudioKind.MP3 && it.kind != AudioKind.UNKNOWN }
}
