package com.vanu.comicpage.export

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Writes an RGB PNG row by row, so a huge, tall page never has to exist as one bitmap.
 * Rows are fed as packed ARGB ints (alpha is ignored); uses the PNG "Sub" filter for smaller files.
 */
class PngStreamWriter(private val out: OutputStream, private val width: Int, private val height: Int, level: Int = 4) {
    private val chunker = IdatChunker(out)
    private val deflate = DeflaterOutputStream(chunker, Deflater(level), 64 * 1024)
    private val line = ByteArray(1 + width * 3)
    private var rowsWritten = 0

    init {
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, width)
        putInt(ihdr, 4, height)
        ihdr[8] = 8   // bit depth
        ihdr[9] = 2   // colour type RGB
        writeChunk(out, "IHDR", ihdr, 13)
    }

    /** [argb] holds [rows] rows of [width] pixels, row after row, starting at [offset]. */
    fun writeRows(argb: IntArray, rows: Int, offset: Int = 0) {
        for (r in 0 until rows) {
            val base = offset + r * width
            line[0] = 1 // Sub filter
            var prevR = 0
            var prevG = 0
            var prevB = 0
            var o = 1
            for (x in 0 until width) {
                val p = argb[base + x]
                val cr = (p shr 16) and 0xFF
                val cg = (p shr 8) and 0xFF
                val cb = p and 0xFF
                line[o++] = (cr - prevR).toByte()
                line[o++] = (cg - prevG).toByte()
                line[o++] = (cb - prevB).toByte()
                prevR = cr; prevG = cg; prevB = cb
            }
            deflate.write(line, 0, line.size)
            rowsWritten++
        }
    }

    fun finish() {
        check(rowsWritten == height) { "wrote $rowsWritten rows, expected $height" }
        deflate.finish()
        chunker.flushChunk()
        writeChunk(out, "IEND", ByteArray(0), 0)
        out.flush()
    }

    private class IdatChunker(private val out: OutputStream) : OutputStream() {
        private val buf = ByteArray(256 * 1024)
        private var n = 0
        override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                val c = minOf(left, buf.size - n)
                System.arraycopy(b, o, buf, n, c)
                n += c; o += c; left -= c
                if (n == buf.size) flushChunk()
            }
        }
        fun flushChunk() {
            if (n > 0) {
                writeChunk(out, "IDAT", buf, n)
                n = 0
            }
        }
    }

    companion object {
        private fun putInt(b: ByteArray, o: Int, v: Int) {
            b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
        }

        private fun writeChunk(out: OutputStream, type: String, data: ByteArray, len: Int) {
            val head = ByteArray(8)
            putInt(head, 0, len)
            for (i in 0 until 4) head[4 + i] = type[i].code.toByte()
            out.write(head)
            if (len > 0) out.write(data, 0, len)
            val crc = CRC32()
            crc.update(head, 4, 4)
            if (len > 0) crc.update(data, 0, len)
            val tail = ByteArray(4)
            putInt(tail, 0, crc.value.toInt())
            out.write(tail)
        }
    }
}
