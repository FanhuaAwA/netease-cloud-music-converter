package com.fanhua.yuncheng

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RealSampleTest {
    @Test fun externalNcmCanBeDecodedAndTagged() {
        val sample = System.getProperty("ncm.sample")?.let(::File)
        assumeTrue("未传入 -Dncm.sample，跳过外部样本", sample?.isFile == true)
        val raw = File.createTempFile("ncm_test_raw", ".tmp")
        val tagged = File.createTempFile("ncm_test_tagged", ".flac")
        try {
            val info = sample!!.inputStream().use { input -> raw.outputStream().buffered().use { NcmCodec.decode(input, it) } }
            assertTrue(info.kind != AudioKind.UNKNOWN)
            assertTrue(raw.length() > 4)
            AudioTagWriter.write(raw, tagged, info)
            assertTrue(tagged.length() > 4)
            assertTrue(tagged.inputStream().use { input ->
                val head = ByteArray(4); input.read(head)
                info.kind != AudioKind.FLAC || String(head) == "fLaC"
            })
        } finally { raw.delete(); tagged.delete() }
    }
}
