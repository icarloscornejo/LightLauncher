package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * The encoder is checked with a reader written here from the standard's own tables (not from the encoder's code): it
 * finds the mask in the format bits, takes the codewords out, checks every Reed-Solomon block and reads the text back.
 * What is left to a phone is scanning one on a TV, which is how it was finally checked.
 */
class QrCodeTest {

    // From the standard, level L, versions 1 to 10: alignment pattern centres, all codewords, data codewords,
    // error correction codewords per block, blocks.
    private val alignment = listOf(
        listOf(), listOf(6, 18), listOf(6, 22), listOf(6, 26), listOf(6, 30), listOf(6, 34),
        listOf(6, 22, 38), listOf(6, 24, 42), listOf(6, 26, 46), listOf(6, 28, 50),
    )
    private val totalCodewords = listOf(26, 44, 70, 100, 134, 172, 196, 242, 292, 346)
    private val dataCodewords = listOf(19, 34, 55, 80, 108, 136, 156, 194, 232, 274)
    private val eccPerBlock = listOf(7, 10, 15, 20, 26, 18, 20, 24, 30, 18)
    private val blocks = listOf(1, 1, 1, 1, 1, 2, 2, 2, 2, 4)

    private fun versionOf(matrix: QrMatrix): Int = (matrix.size - 17) / 4

    @Test
    fun theSizeFollowsTheVersionTheTextNeeds() {
        assertEquals(21, QrCode.encode("a")!!.size) // version 1 holds 17 bytes
        assertEquals(21, QrCode.encode("x".repeat(17))!!.size)
        assertEquals(25, QrCode.encode("x".repeat(18))!!.size) // version 2
        assertEquals(29, QrCode.encode("x".repeat(33))!!.size) // version 3
        assertEquals(57, QrCode.encode("x".repeat(271))!!.size) // version 10, the last one
        assertNull(QrCode.encode("x".repeat(272)))
    }

    @Test
    fun theThreeFindersAndTheTimingLinesAreWhereTheyShouldBe() {
        val matrix = QrCode.encode("http://192.168.1.50:4000/abc")!!
        val last = matrix.size - 1
        for ((cx, cy) in listOf(3 to 3, last - 3 to 3, 3 to last - 3)) {
            assertTrue(matrix.isDark(cx, cy)) // the centre
            assertFalse(matrix.isDark(cx + 2, cy)) // the light ring
            assertTrue(matrix.isDark(cx + 3, cy)) // the dark ring
        }
        for (i in 8..matrix.size - 9) {
            assertEquals(i % 2 == 0, matrix.isDark(i, 6))
            assertEquals(i % 2 == 0, matrix.isDark(6, i))
        }
        assertTrue(matrix.isDark(8, matrix.size - 8)) // the module that is always dark
    }

    @Test
    fun theTextComesBackFromTheMatrixForEveryVersion() {
        for (length in listOf(1, 10, 17, 18, 32, 33, 52, 53, 77, 78, 106, 107, 134, 135, 154, 155, 192, 193, 230, 231, 271)) {
            val text = (0 until length).joinToString("") { ((it * 7) % 26 + 'a'.code).toChar().toString() }
            val matrix = QrCode.encode(text)
            assertNotNull("length $length", matrix)
            assertEquals("length $length", text, read(matrix!!))
        }
    }

    @Test
    fun theLinkThePageUsesComesBackToo() {
        val text = "http://192.168.1.50:41234/0123456789abcdef0123456789abcdef"
        assertEquals(text, read(QrCode.encode(text)!!))
        val accents = "http://192.168.1.5:8000/é-ñ-日本"
        assertEquals(accents, read(QrCode.encode(accents)!!))
    }

    // ---- the reader ---------------------------------------------------------------------------------

    private fun read(matrix: QrMatrix): String {
        val version = versionOf(matrix)
        val size = matrix.size
        // Format bits, both copies, and their own error check.
        fun bit(x: Int, y: Int): Int = if (matrix.isDark(x, y)) 1 else 0
        var first = 0
        val firstSpots = listOf(8 to 0, 8 to 1, 8 to 2, 8 to 3, 8 to 4, 8 to 5, 8 to 7, 8 to 8, 7 to 8, 5 to 8, 4 to 8, 3 to 8, 2 to 8, 1 to 8, 0 to 8)
        firstSpots.forEachIndexed { i, (x, y) -> first = first or (bit(x, y) shl i) }
        var second = 0
        for (i in 0..7) second = second or (bit(size - 1 - i, 8) shl i)
        for (i in 8..14) second = second or (bit(8, size - 15 + i) shl i)
        assertEquals("the two copies of the format bits", first, second)
        val format = first xor 0x5412
        val data = format ushr 10
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        assertEquals("the format bits' own check", rem, format and 0x3FF)
        assertEquals("level L", 1, data ushr 3)
        val mask = data and 7

        // Which modules are not data.
        val isFunction = Array(size) { BooleanArray(size) }
        fun mark(x0: Int, y0: Int, w: Int, h: Int) {
            for (y in y0 until y0 + h) for (x in x0 until x0 + w) if (x in 0 until size && y in 0 until size) isFunction[y][x] = true
        }
        mark(0, 0, 9, 9); mark(size - 8, 0, 8, 9); mark(0, size - 8, 9, 8) // finders, separators and format bits
        mark(6, 0, 1, size); mark(0, 6, size, 1) // timing
        val centres = alignment[version - 1]
        for (cy in centres) for (cx in centres) {
            if ((cx == 6 && cy == 6) || (cx == 6 && cy == centres.last()) || (cx == centres.last() && cy == 6)) continue
            mark(cx - 2, cy - 2, 5, 5)
        }
        if (version >= 7) { mark(size - 11, 0, 3, 6); mark(0, size - 11, 6, 3) }

        // The codewords, in the zigzag order, with the mask taken off.
        fun masked(x: Int, y: Int): Boolean = when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (x / 3 + y / 2) % 2 == 0
            5 -> x * y % 2 + x * y % 3 == 0
            6 -> (x * y % 2 + x * y % 3) % 2 == 0
            else -> ((x + y) % 2 + x * y % 3) % 2 == 0
        }
        val bits = ArrayList<Int>()
        var column = size - 1
        var upward = true
        while (column > 0) {
            if (column == 6) column--
            val rows = if (upward) (size - 1 downTo 0) else (0 until size)
            for (y in rows) for (x in listOf(column, column - 1)) {
                if (isFunction[y][x]) continue
                bits.add(bit(x, y) xor (if (masked(x, y)) 1 else 0))
            }
            upward = !upward
            column -= 2
        }
        val total = totalCodewords[version - 1]
        val codewords = IntArray(total) { index -> (0 until 8).fold(0) { acc, i -> (acc shl 1) or bits[index * 8 + i] } }

        // Back into blocks, and a check of each one's Reed-Solomon code.
        val blockCount = blocks[version - 1]
        val ecc = eccPerBlock[version - 1]
        val shortLength = total / blockCount
        val shortBlocks = blockCount - total % blockCount
        val blockData = List(blockCount) { ArrayList<Int>() }
        val blockEcc = List(blockCount) { ArrayList<Int>() }
        var at = 0
        for (i in 0 until shortLength + 1) {
            for (b in 0 until blockCount) {
                // A short block has no codeword where the long ones have their last data byte.
                if (i == shortLength - ecc && b < shortBlocks) continue
                val word = codewords[at++]
                if (i < shortLength - ecc + (if (b < shortBlocks) 0 else 1)) blockData[b].add(word) else blockEcc[b].add(word)
            }
        }
        assertEquals(total, at)
        val exp = IntArray(512)
        val log = IntArray(256)
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x; log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
        fun times(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
        val all = ArrayList<Int>()
        for (b in 0 until blockCount) {
            val word = blockData[b] + blockEcc[b]
            for (root in 0 until ecc) {
                var syndrome = 0
                for (w in word) syndrome = times(syndrome, exp[root]) xor w
                assertEquals("syndrome $root of block $b", 0, syndrome)
            }
            all.addAll(blockData[b])
        }
        assertEquals(dataCodewords[version - 1], all.size)

        // The data: mode 4 (bytes), the count, the bytes.
        val dataBits = all.flatMap { word -> (7 downTo 0).map { (word ushr it) and 1 } }
        fun take(from: Int, count: Int): Int = (from until from + count).fold(0) { acc, i -> (acc shl 1) or dataBits[i] }
        assertEquals("byte mode", 4, take(0, 4))
        val countBits = if (version <= 9) 8 else 16
        val length = take(4, countBits)
        val bytes = ByteArray(length) { i -> take(4 + countBits + i * 8, 8).toByte() }
        return String(bytes, StandardCharsets.UTF_8)
    }
}
