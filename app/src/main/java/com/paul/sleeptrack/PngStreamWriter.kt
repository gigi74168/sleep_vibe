package com.paul.sleeptrack

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Écrit un PNG (RVB, 8 bits) ligne par ligne, sans jamais tenir l'image entière en mémoire.
 * Sert à l'image 16K : 15 360 px de large, soit près de 600 Mo en bitmap, qu'Android refuserait.
 * Les lignes arrivent en ARGB opaque, comme les rend `Bitmap.getPixels`.
 */
class PngStreamWriter(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    level: Int = Deflater.BEST_SPEED,
) {
    private val idat = IdatStream(out)
    private val deflater = Deflater(level)
    private val zip = DeflaterOutputStream(idat, deflater, 1 shl 16)
    private val row = ByteArray(1 + width * 3)
    private var written = 0

    init {
        require(width > 0 && height > 0)
        out.write(SIGNATURE)
        val header = ByteArray(13)
        putInt(header, 0, width)
        putInt(header, 4, height)
        header[8] = 8 // bits par canal
        header[9] = 2 // couleur RVB, sans alpha
        // compression, filtre et entrelacement : 0
        writeChunk(out, "IHDR", header, header.size)
    }

    /** Une ligne de [width] pixels, lue dans [argb] à partir de [offset]. */
    fun writeRow(argb: IntArray, offset: Int = 0) {
        check(written < height) { "trop de lignes" }
        row[0] = 0 // pas de filtre : les aplats se compressent déjà très bien
        var i = 1
        for (x in 0 until width) {
            val p = argb[offset + x]
            row[i++] = (p shr 16).toByte()
            row[i++] = (p shr 8).toByte()
            row[i++] = p.toByte()
        }
        zip.write(row)
        written++
    }

    fun finish() {
        check(written == height) { "$written lignes sur $height" }
        zip.finish()
        idat.flush()
        writeChunk(out, "IEND", ByteArray(0), 0)
        deflater.end()
        out.flush()
    }

    /** Regroupe le flux compressé en blocs IDAT de 64 Ko. */
    private class IdatStream(private val out: OutputStream) : OutputStream() {
        private val buffer = ByteArray(1 shl 16)
        private var size = 0

        override fun write(b: Int) {
            if (size == buffer.size) flush()
            buffer[size++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var remaining = len
            while (remaining > 0) {
                if (size == buffer.size) flush()
                val n = minOf(remaining, buffer.size - size)
                System.arraycopy(b, o, buffer, size, n)
                size += n
                o += n
                remaining -= n
            }
        }

        override fun flush() {
            if (size == 0) return
            writeChunk(out, "IDAT", buffer, size)
            size = 0
        }
    }

    private companion object {
        val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        fun putInt(b: ByteArray, at: Int, v: Int) {
            b[at] = (v ushr 24).toByte()
            b[at + 1] = (v ushr 16).toByte()
            b[at + 2] = (v ushr 8).toByte()
            b[at + 3] = v.toByte()
        }

        fun writeChunk(out: OutputStream, type: String, data: ByteArray, length: Int) {
            val len = ByteArray(4).also { putInt(it, 0, length) }
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply {
                update(typeBytes)
                update(data, 0, length)
            }
            out.write(len)
            out.write(typeBytes)
            out.write(data, 0, length)
            out.write(ByteArray(4).also { putInt(it, 0, crc.value.toInt()) })
        }
    }
}
