package com.paul.sleeptrack

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.random.Random

class PngStreamWriterTest {
    private class Decoded(val width: Int, val height: Int, val pixels: IntArray, val idatChunks: Int)

    /** Relit le PNG à la main : signature, CRC de chaque bloc, en-tête, puis les lignes. */
    private fun decode(bytes: ByteArray): Decoded {
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        assertArrayEquals(signature, bytes.copyOfRange(0, 8))
        val buf = ByteBuffer.wrap(bytes, 8, bytes.size - 8)
        var width = 0
        var height = 0
        val compressed = ByteArrayOutputStream()
        var idat = 0
        var ended = false
        while (!ended) {
            val length = buf.int
            val type = ByteArray(4).also { buf.get(it) }
            val data = ByteArray(length).also { buf.get(it) }
            val crc = buf.int
            val expected = CRC32().apply { update(type); update(data) }.value.toInt()
            assertEquals("CRC du bloc ${String(type)}", expected, crc)
            when (String(type, Charsets.US_ASCII)) {
                "IHDR" -> {
                    val h = ByteBuffer.wrap(data)
                    width = h.int
                    height = h.int
                    assertEquals(8, data[8].toInt())
                    assertEquals(2, data[9].toInt())
                }
                "IDAT" -> {
                    compressed.write(data)
                    idat++
                }
                "IEND" -> ended = true
            }
        }
        val raw = ByteArray(height * (1 + width * 3))
        val inflater = Inflater().apply { setInput(compressed.toByteArray()) }
        var read = 0
        while (read < raw.size) read += inflater.inflate(raw, read, raw.size - read)
        assertTrue(inflater.finished())
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val start = y * (1 + width * 3)
            assertEquals("filtre de la ligne $y", 0, raw[start].toInt())
            for (x in 0 until width) {
                val i = start + 1 + x * 3
                pixels[y * width + x] = ((raw[i].toInt() and 0xFF) shl 16) or
                    ((raw[i + 1].toInt() and 0xFF) shl 8) or (raw[i + 2].toInt() and 0xFF)
            }
        }
        return Decoded(width, height, pixels, idat)
    }

    private fun roundTrip(width: Int, height: Int, pixel: (Int, Int) -> Int): Decoded {
        val out = ByteArrayOutputStream()
        val png = PngStreamWriter(out, width, height)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) row[x] = pixel(x, y)
            png.writeRow(row)
        }
        png.finish()

        val decoded = decode(out.toByteArray())
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                assertEquals("pixel $x,$y", pixel(x, y) and 0xFFFFFF, decoded.pixels[y * width + x])
            }
        }
        return decoded
    }

    @Test
    fun aSmallImageReadsBackPixelForPixel() {
        roundTrip(37, 5) { x, y -> (0xFF shl 24) or (x * 7 shl 16) or (y * 50 shl 8) or (x + y) }
    }

    @Test
    fun noiseSpreadsOverSeveralIdatChunks() {
        // 300 × 300 pixels de bruit : bien plus que les 64 Ko d'un bloc IDAT une fois compressés.
        val rnd = Random(7)
        val pixels = IntArray(300 * 300) { rnd.nextInt() or (0xFF shl 24) }
        val decoded = roundTrip(300, 300) { x, y -> pixels[y * 300 + x] }
        assertTrue(decoded.idatChunks > 1)
    }
}
