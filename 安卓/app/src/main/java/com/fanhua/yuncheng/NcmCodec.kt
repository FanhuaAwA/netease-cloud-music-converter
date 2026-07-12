package com.fanhua.yuncheng

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class NcmData(
    val kind: AudioKind,
    val title: String,
    val artist: String,
    val album: String,
    val cover: ByteArray?,
    internal val streamKey: ByteArray,
)

object NcmCodec {
    private val coreKey = "687a4852416d736f356b496e62617857".hex()
    private val metaKey = "2331346c6a6b5f215c5d2630553c2728".hex()
    private const val MAX_KEY = 64 * 1024
    private const val MAX_META = 4 * 1024 * 1024
    private const val MAX_COVER = 20 * 1024 * 1024

    fun inspect(input: InputStream): NcmData = parse(BufferedInputStream(input), null, false)

    fun decode(input: InputStream, output: OutputStream): NcmData =
        parse(BufferedInputStream(input), output, true)

    private fun parse(source: BufferedInputStream, output: OutputStream?, readCover: Boolean): NcmData {
        require(source.readN(8).contentEquals("CTENFDAM".toByteArray())) { "不是标准 NCM 文件" }
        source.skipFully(2)
        val keyLength = source.readLeInt()
        require(keyLength in 16..MAX_KEY && keyLength % 16 == 0) { "NCM 密钥区无效" }
        val encryptedKey = source.readN(keyLength).also { bytes -> bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor 0x64).toByte() } }
        val plainKey = aes(encryptedKey, coreKey)
        val prefix = "neteasecloudmusic".toByteArray()
        require(plainKey.size > prefix.size && plainKey.copyOf(prefix.size).contentEquals(prefix)) { "NCM 音频密钥无效" }
        val streamKey = makeStream(plainKey.copyOfRange(prefix.size, plainKey.size))

        val metaLength = source.readLeInt()
        require(metaLength in 0..MAX_META) { "NCM 元数据区过大或损坏" }
        val metadata = decodeMetadata(source.readN(metaLength))
        source.skipFully(9)
        val coverLength = source.readLeInt()
        require(coverLength in 0..MAX_COVER) { "NCM 封面区过大或损坏" }
        val cover = if (readCover) source.readN(coverLength).takeIf { it.isNotEmpty() }
        else null.also { source.skipFully(coverLength.toLong()) }

        val first = source.readNOrLess(64 * 1024)
        xor(first, streamKey, 0)
        val kind = detect(first)
        require(kind != AudioKind.UNKNOWN) { "无法识别解密后的音频格式" }
        if (output != null) {
            output.write(first)
            var position = first.size.toLong()
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                xor(buffer, streamKey, position, count)
                output.write(buffer, 0, count)
                position += count
            }
        }
        return NcmData(kind, metadata.title, metadata.artist, metadata.album, cover, streamKey)
    }

    private data class Metadata(val title: String = "", val artist: String = "", val album: String = "")

    private fun decodeMetadata(bytes: ByteArray): Metadata = runCatching {
        bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor 0x63).toByte() }
        val marker = "163 key(Don't modify):".toByteArray()
        require(bytes.size > marker.size && bytes.copyOf(marker.size).contentEquals(marker))
        val decoded = Base64.getMimeDecoder().decode(bytes.copyOfRange(marker.size, bytes.size))
        val plain = aes(decoded, metaKey)
        require(String(plain, 0, 6) == "music:")
        val json = JSONObject(String(plain, 6, plain.size - 6, Charsets.UTF_8))
        Metadata(json.optString("musicName"), artists(json.opt("artist")), json.optString("album"))
    }.getOrDefault(Metadata())

    private fun artists(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildList {
            for (i in 0 until value.length()) when (val item = value.opt(i)) {
                is JSONArray -> item.optString(0).takeIf(String::isNotBlank)?.let(::add)
                is JSONObject -> item.optString("name").takeIf(String::isNotBlank)?.let(::add)
                is String -> item.takeIf(String::isNotBlank)?.let(::add)
            }
        }.joinToString("; ")
        else -> ""
    }

    private fun makeStream(key: ByteArray): ByteArray {
        require(key.isNotEmpty())
        val box = IntArray(256) { it }
        var j = 0
        for (i in box.indices) {
            j = (box[i] + j + (key[i % key.size].toInt() and 0xff)) and 0xff
            val t = box[i]; box[i] = box[j]; box[j] = t
        }
        return ByteArray(256) { i ->
            val n = (i + 1) and 0xff
            box[(box[n] + box[(box[n] + n) and 0xff]) and 0xff].toByte()
        }
    }

    private fun xor(data: ByteArray, key: ByteArray, position: Long, count: Int = data.size) {
        for (i in 0 until count) data[i] = (data[i].toInt() xor key[((position + i) and 0xff).toInt()].toInt()).toByte()
    }

    private fun detect(head: ByteArray): AudioKind = when {
        head.starts("fLaC") -> AudioKind.FLAC
        head.starts("ID3") || (head.size > 2 && head[0] == 0xff.toByte() && (head[1].toInt() and 0xe0) == 0xe0) -> AudioKind.MP3
        head.starts("RIFF") && head.size >= 12 && String(head, 8, 4) == "WAVE" -> AudioKind.WAV
        head.starts("OggS") && head.indexOf("OpusHead".toByteArray()) in 0..64 -> AudioKind.OPUS
        head.starts("OggS") -> AudioKind.OGG
        head.starts("MAC ") -> AudioKind.APE
        head.size >= 12 && String(head, 4, 4) == "ftyp" -> AudioKind.M4A
        else -> AudioKind.UNKNOWN
    }

    private fun aes(data: ByteArray, key: ByteArray): ByteArray = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(data)
    }

    private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.starts(value: String) = size >= value.length && String(this, 0, value.length, Charsets.US_ASCII) == value
    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) { for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer; return i }
        return -1
    }
    private fun InputStream.readLeInt(): Int { val b = readN(4); return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24) }
    private fun InputStream.readN(size: Int): ByteArray = ByteArray(size).also { b -> var p = 0; while (p < size) { val n = read(b, p, size - p); if (n < 0) throw EOFException("文件提前结束"); p += n } }
    private fun InputStream.readNOrLess(size: Int): ByteArray { val b = ByteArray(size); var p = 0; while (p < size) { val n = read(b, p, size - p); if (n < 0) break; p += n }; return b.copyOf(p) }
    private fun InputStream.skipFully(size: Long) { var left = size; while (left > 0) { val n = skip(left); if (n <= 0) { if (read() < 0) throw EOFException(); left-- } else left -= n } }
}
