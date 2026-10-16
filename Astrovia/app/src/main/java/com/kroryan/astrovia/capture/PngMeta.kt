package com.kroryan.astrovia.capture

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/** Writes textual metadata (tEXt chunks) into a PNG without re-encoding it. */
object PngMeta {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** tEXt is Latin-1 only: keep printable ASCII and replace anything else. */
    private fun clean(s: String): String =
        s.map { if (it.code in 32..126) it else '?' }.joinToString("")

    fun textChunk(key: String, value: String): ByteArray {
        val k = clean(key).take(79).ifEmpty { "Comment" }
        val data = k.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + clean(value).toByteArray(Charsets.ISO_8859_1)
        val type = "tEXt".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(data) }.value
        val out = java.io.ByteArrayOutputStream(data.size + 12)
        writeInt(out, data.size)
        out.write(type)
        out.write(data)
        writeInt(out, crc.toInt())
        return out.toByteArray()
    }

    /**
     * Copies the PNG in [src] to [dst], inserting the [entries] right after the IHDR chunk.
     * Streams the data, so even a very large export is never held twice in memory.
     */
    fun copyWithText(src: File, dst: OutputStream, entries: Map<String, String>) {
        src.inputStream().buffered(1 shl 16).use { copyWithText(it, dst, entries) }
    }

    fun copyWithText(src: InputStream, dst: OutputStream, entries: Map<String, String>) {
        val head = ByteArray(8 + 25) // signature + IHDR chunk (4 length + 4 type + 13 data + 4 crc)
        var read = 0
        while (read < head.size) {
            val n = src.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        require(read == head.size && head.copyOfRange(0, 8).contentEquals(SIGNATURE)) { "not a PNG" }
        dst.write(head)
        for ((k, v) in entries) dst.write(textChunk(k, v))
        src.copyTo(dst, 1 shl 16)
    }

    private fun writeInt(o: OutputStream, v: Int) {
        o.write(v ushr 24)
        o.write(v ushr 16)
        o.write(v ushr 8)
        o.write(v)
    }
}
