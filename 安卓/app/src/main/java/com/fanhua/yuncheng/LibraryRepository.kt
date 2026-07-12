package com.fanhua.yuncheng

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LibraryRepository(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val supported = setOf("ncm", "flac", "mp3", "wav", "m4a", "ogg", "opus", "aac", "ape")

    suspend fun scan(rootUri: Uri): List<Song> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: error("无法读取所选目录")
        val files = sequence {
            val pending = ArrayDeque<DocumentFile>(); pending.add(root)
            while (pending.isNotEmpty()) for (file in pending.removeFirst().listFiles()) {
                if (file.isDirectory) pending.add(file) else yield(file)
            }
        }.toList()
        val lyrics = files.filter { it.extension() == "lrc" }.associateBy { it.name.orEmpty().substringBeforeLast('.').lowercase() }
        files.filter { it.extension() in supported }.map { file ->
            val name = file.name ?: "未命名"
            val ext = file.extension()
            val lyric = lyrics[name.substringBeforeLast('.').lowercase()]?.uri
            if (ext == "ncm") runCatching {
                resolver.openInputStream(file.uri)!!.use(NcmCodec::inspect).let {
                    Song(file.uri, name, file.length(), true, it.kind, it.title.ifBlank { name.substringBeforeLast('.') }, it.artist, it.album, lyric)
                }
            }.getOrElse { Song(file.uri, name, file.length(), true, lyricUri = lyric, selected = false, state = WorkState.Failed(it.message ?: "检测失败")) }
            else Song(file.uri, name, file.length(), false, kindFromExtension(ext), lyricUri = lyric)
        }.sortedWith(compareByDescending<Song> { it.isNcm }.thenBy { it.fileName.lowercase() })
    }

    suspend fun convert(song: Song, outputUri: Uri, overwrite: Boolean = true) = withContext(Dispatchers.IO) {
        val outputDir = DocumentFile.fromTreeUri(context, outputUri) ?: error("无法访问输出目录")
        var outputStem = song.fileName.substringBeforeLast('.')
        if (!song.isNcm) {
            copyToTree(song.uri, outputDir, song.fileName, mime(song.kind), overwrite)
        } else {
            val raw = File.createTempFile("ncm_raw_", ".tmp", context.cacheDir)
            val tagged = File.createTempFile("ncm_tag_", ".${song.kind.extension}", context.cacheDir)
            try {
                val info = resolver.openInputStream(song.uri)!!.use { input -> raw.outputStream().buffered().use { NcmCodec.decode(input, it) } }
                AudioTagWriter.write(raw, tagged, info)
                val safeName = sanitize("${info.artist.takeIf(String::isNotBlank)?.plus(" - ") ?: ""}${info.title.ifBlank { song.fileName.substringBeforeLast('.') }}.${info.kind.extension}")
                outputStem = safeName.substringBeforeLast('.')
                writeFile(outputDir, safeName, mime(info.kind), overwrite) { target -> tagged.inputStream().use { it.copyTo(target, 1024 * 1024) } }
            } finally { raw.delete(); tagged.delete() }
        }
        song.lyricUri?.let { lyric -> copyToTree(lyric, outputDir, sanitize("$outputStem.lrc"), "text/plain", overwrite) }
    }

    private fun copyToTree(uri: Uri, dir: DocumentFile, name: String, mime: String, overwrite: Boolean) {
        if (dir.findFile(name)?.uri == uri) return
        writeFile(dir, name, mime, overwrite) { out -> resolver.openInputStream(uri)!!.use { it.copyTo(out, 1024 * 1024) } }
    }

    private fun writeFile(dir: DocumentFile, name: String, mime: String, overwrite: Boolean, writer: (java.io.OutputStream) -> Unit) {
        val old = dir.findFile(name)
        if (old != null && !overwrite) return
        val target = dir.createFile(mime, if (old == null) name else "$name.partial-${System.nanoTime()}")
            ?: error("无法创建输出文件：$name")
        try {
            resolver.openOutputStream(target.uri, "w")!!.buffered().use(writer)
            if (old != null) {
                check(old.delete()) { "无法替换已有文件：$name" }
                check(target.renameTo(name)) { "转换成功，但输出保留为临时文件：${target.name}" }
            }
        } catch (e: Exception) {
            if (old == null || old.exists()) target.delete()
            throw e
        }
    }

    private fun DocumentFile.extension() = name.orEmpty().substringAfterLast('.', "").lowercase()
    private fun kindFromExtension(ext: String) = AudioKind.entries.firstOrNull { it.extension == ext } ?: AudioKind.UNKNOWN
    private fun mime(kind: AudioKind) = when (kind) { AudioKind.FLAC -> "audio/flac"; AudioKind.MP3 -> "audio/mpeg"; AudioKind.WAV -> "audio/wav"; AudioKind.M4A -> "audio/mp4"; AudioKind.OGG, AudioKind.OPUS -> "audio/ogg"; AudioKind.AAC -> "audio/aac"; else -> "application/octet-stream" }
    private fun sanitize(name: String) = name.replace(Regex("[\\/:*?\"<>|]"), "_").trim().take(180)
}
