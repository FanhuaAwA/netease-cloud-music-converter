package com.fanhua.yuncheng

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioTagWriter {
    fun write(raw: File, target: File, info: NcmData) = when (info.kind) {
        AudioKind.MP3 -> writeMp3(raw, target, info)
        AudioKind.FLAC -> writeFlac(raw, target, info)
        else -> raw.copyTo(target, overwrite = true)
    }

    private fun writeMp3(raw: File, target: File, info: NcmData) {
        val frames = buildList {
            textFrame("TIT2", info.title)?.let(::add)
            textFrame("TPE1", info.artist)?.let(::add)
            textFrame("TALB", info.album)?.let(::add)
            info.cover?.let { cover ->
                val mime = imageMime(cover) ?: "image/jpeg"
                val body = byteArrayOf(0) + mime.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + cover
                add(frame("APIC", body))
            }
        }
        BufferedInputStream(FileInputStream(raw)).use { input ->
            BufferedOutputStream(FileOutputStream(target)).use { output ->
                val bodySize = frames.sumOf { it.size }
                output.write("ID3".toByteArray()); output.write(byteArrayOf(3, 0, 0)); output.write(syncSafe(bodySize))
                frames.forEach(output::write)
                skipId3(input)
                input.copyTo(output, 1024 * 1024)
            }
        }
    }

    private fun writeFlac(raw: File, target: File, info: NcmData) {
        DataInputStream(BufferedInputStream(FileInputStream(raw))).use { input ->
            BufferedOutputStream(FileOutputStream(target)).use { output ->
                val signature = ByteArray(4).also(input::readFully)
                require(String(signature, Charsets.US_ASCII) == "fLaC") { "FLAC 文件头无效" }
                output.write("fLaC".toByteArray())
                val kept = mutableListOf<Pair<Int, ByteArray>>()
                var metadataSize = 0
                var blockCount = 0
                var last: Boolean
                do {
                    require(++blockCount <= 256) { "FLAC 元数据块数量异常" }
                    val header = input.readUnsignedByte()
                    last = header and 0x80 != 0
                    val type = header and 0x7f
                    val length = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                    metadataSize += length
                    require(metadataSize <= 32 * 1024 * 1024) { "FLAC 元数据区过大" }
                    val data = ByteArray(length).also(input::readFully)
                    if (type != 4 && type != 6) kept += type to data
                } while (!last)
                val blocks = kept + listOf(4 to vorbis(info)) + listOfNotNull(info.cover?.let { 6 to picture(it) })
                blocks.forEachIndexed { index, (type, data) ->
                    require(data.size <= 0xFFFFFF) { "FLAC 标签块过大" }
                    output.write(type or if (index == blocks.lastIndex) 0x80 else 0)
                    output.write(data.size shr 16); output.write(data.size shr 8); output.write(data.size)
                    output.write(data)
                }
                input.copyTo(output, 1024 * 1024)
            }
        }
    }

    private fun vorbis(info: NcmData): ByteArray {
        val vendor = "云澄 Android".toByteArray()
        val comments = buildList {
            if (info.title.isNotBlank()) add("TITLE=${info.title}")
            if (info.artist.isNotBlank()) add("ARTIST=${info.artist}")
            if (info.album.isNotBlank()) add("ALBUM=${info.album}")
        }.map(String::toByteArray)
        return ByteBuffer.allocate(4 + vendor.size + 4 + comments.sumOf { 4 + it.size }).order(ByteOrder.LITTLE_ENDIAN).run {
            putInt(vendor.size); put(vendor); putInt(comments.size); comments.forEach { putInt(it.size); put(it) }; array()
        }
    }

    private fun picture(data: ByteArray): ByteArray {
        val mime = (imageMime(data) ?: "image/jpeg").toByteArray()
        val desc = "Cover".toByteArray()
        return ByteBuffer.allocate(4 + 4 + mime.size + 4 + desc.size + 20 + data.size).order(ByteOrder.BIG_ENDIAN).run {
            putInt(3); putInt(mime.size); put(mime); putInt(desc.size); put(desc)
            putInt(0); putInt(0); putInt(0); putInt(0); putInt(data.size); put(data); array()
        }
    }

    private fun textFrame(id: String, text: String): ByteArray? = text.takeIf(String::isNotBlank)?.let {
        val encoded = it.toByteArray(Charsets.UTF_16LE)
        frame(id, byteArrayOf(1, 0xff.toByte(), 0xfe.toByte()) + encoded)
    }

    private fun frame(id: String, body: ByteArray): ByteArray = id.toByteArray() + byteArrayOf(
        (body.size shr 24).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte(), 0, 0
    ) + body

    private fun syncSafe(value: Int) = byteArrayOf((value shr 21).toByte(), ((value shr 14) and 0x7f).toByte(), ((value shr 7) and 0x7f).toByte(), (value and 0x7f).toByte())

    private fun skipId3(input: InputStream) {
        input.mark(10)
        val head = input.readUpTo(10)
        if (head.size == 10 && String(head, 0, 3) == "ID3") {
            val size = ((head[6].toInt() and 0x7f) shl 21) or ((head[7].toInt() and 0x7f) shl 14) or ((head[8].toInt() and 0x7f) shl 7) or (head[9].toInt() and 0x7f)
            var left = size.toLong(); while (left > 0) { val n = input.skip(left); if (n <= 0) break; left -= n }
        } else input.reset()
    }

    private fun InputStream.readUpTo(size: Int): ByteArray {
        val data = ByteArray(size)
        var position = 0
        while (position < size) {
            val count = read(data, position, size - position)
            if (count < 0) break
            position += count
        }
        return data.copyOf(position)
    }

    private fun imageMime(data: ByteArray): String? = when {
        data.size >= 8 && data.copyOf(8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
        data.size >= 3 && data[0] == 0xff.toByte() && data[1] == 0xd8.toByte() -> "image/jpeg"
        data.size >= 12 && String(data, 0, 4) == "RIFF" && String(data, 8, 4) == "WEBP" -> "image/webp"
        else -> null
    }
}
