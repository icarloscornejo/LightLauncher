package com.prototype.lightlauncher.core

import java.nio.charset.StandardCharsets

/** A QR code: a square of [size] by [size] modules, dark or light. */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    fun isDark(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && dark[y * size + x]
}

/**
 * A QR code encoder, for the screen that sends the screensaver's album link from a phone. Written for
 * one job and nothing more: text in byte mode (UTF-8), error correction level L, versions 1 to 10 (up to 271 bytes),
 * the mask with the lowest penalty. Nothing is added to the project for it. It follows ISO/IEC 18004 and is checked
 * in the tests with an independent reader of its own matrices, and on a TV by scanning it with a phone.
 *
 * Adapted from the "QR Code generator library" by Project Nayuki (https://www.nayuki.io/page/qr-code-generator-library,
 * MIT license, Copyright (c) Project Nayuki); the notice is in THIRD_PARTY_NOTICES.md.
 */
object QrCode {
    const val MAX_VERSION: Int = 10

    /** Error correction codewords per block, and number of blocks, for level L, versions 1 to 10. */
    private val ECC_CODEWORDS_PER_BLOCK: IntArray = intArrayOf(7, 10, 15, 20, 26, 18, 20, 24, 30, 18)
    private val NUMBER_OF_BLOCKS: IntArray = intArrayOf(1, 1, 1, 1, 1, 2, 2, 2, 2, 4)

    /** Level L in the format information. */
    private const val ECC_FORMAT_BITS: Int = 1

    private const val PENALTY_RUN: Int = 3
    private const val PENALTY_BLOCK: Int = 3
    private const val PENALTY_FINDER: Int = 40
    private const val PENALTY_BALANCE: Int = 10

    /** The code for [text], or null when it does not fit in version 10. */
    fun encode(text: String): QrMatrix? {
        val bytes: ByteArray = text.toByteArray(StandardCharsets.UTF_8)
        var version = 1
        while (4 + countBits(version) + bytes.size * 8 > dataCodewords(version) * 8) {
            if (++version > MAX_VERSION) return null
        }
        val codewords: ByteArray = addErrorCorrection(dataBits(bytes, version), version)
        return Matrix(version, codewords).build()
    }

    private fun countBits(version: Int): Int = if (version <= 9) 8 else 16

    /** Modules that carry data or error correction: all but the patterns, the timing lines and the format and version bits. */
    private fun rawDataModules(version: Int): Int {
        var result: Int = (16 * version + 128) * version + 64
        if (version >= 2) {
            val aligns: Int = version / 7 + 2
            result -= (25 * aligns - 10) * aligns - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    private fun dataCodewords(version: Int): Int =
        rawDataModules(version) / 8 - ECC_CODEWORDS_PER_BLOCK[version - 1] * NUMBER_OF_BLOCKS[version - 1]

    /** The data codewords: mode, length, the bytes, a terminator, and the two filler bytes that complete the version. */
    private fun dataBits(bytes: ByteArray, version: Int): ByteArray {
        val bits: MutableList<Boolean> = ArrayList()
        fun append(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add(((value ushr i) and 1) != 0)
        }
        append(0b0100, 4)
        append(bytes.size, countBits(version))
        for (b in bytes) append(b.toInt() and 0xFF, 8)
        val capacity: Int = dataCodewords(version) * 8
        append(0, Math.min(4, capacity - bits.size))
        append(0, (8 - bits.size % 8) % 8)
        var filler = 0xEC
        while (bits.size < capacity) {
            append(filler, 8)
            filler = filler xor (0xEC xor 0x11)
        }
        val result = ByteArray(bits.size / 8)
        for (i in bits.indices) if (bits[i]) result[i / 8] = (result[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
        return result
    }

    /** Splits the data into blocks, adds each block's Reed-Solomon codewords and interleaves them. */
    private fun addErrorCorrection(data: ByteArray, version: Int): ByteArray {
        val blocks: Int = NUMBER_OF_BLOCKS[version - 1]
        val eccLength: Int = ECC_CODEWORDS_PER_BLOCK[version - 1]
        val raw: Int = rawDataModules(version) / 8
        val shortBlocks: Int = blocks - raw % blocks
        val shortLength: Int = raw / blocks
        val divisor: IntArray = reedSolomonDivisor(eccLength)
        val all: MutableList<ByteArray> = ArrayList()
        var at = 0
        for (i in 0 until blocks) {
            val length: Int = shortLength - eccLength + (if (i < shortBlocks) 0 else 1)
            val block: ByteArray = data.copyOfRange(at, at + length)
            at += length
            val ecc: ByteArray = reedSolomonRemainder(block, divisor)
            // A short block gets a placeholder where the long ones have their last data byte, skipped when interleaving.
            all.add((if (i < shortBlocks) block + byteArrayOf(0) else block) + ecc)
        }
        val result = ByteArray(raw)
        var k = 0
        for (i in 0 until all[0].size) {
            for (j in all.indices) {
                if (i != shortLength - eccLength || j >= shortBlocks) result[k++] = all[j][i]
            }
        }
        return result
    }

    private fun reedSolomonDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in result.indices) {
                result[j] = multiply(result[j], root)
                if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
            }
            root = multiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: ByteArray, divisor: IntArray): ByteArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor: Int = (b.toInt() and 0xFF) xor result[0]
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
        }
        return ByteArray(result.size) { result[it].toByte() }
    }

    /** Product in GF(2^8) with the polynomial 0x11D. */
    private fun multiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun bit(value: Int, index: Int): Boolean = ((value ushr index) and 1) != 0

    /** The grid of modules of one code, drawn in the order the standard gives. */
    private class Matrix(private val version: Int, private val codewords: ByteArray) {
        private val size: Int = version * 4 + 17
        private val modules: Array<BooleanArray> = Array(size) { BooleanArray(size) }
        private val isFunction: Array<BooleanArray> = Array(size) { BooleanArray(size) }

        fun build(): QrMatrix {
            drawFunctionPatterns()
            drawCodewords()
            var best = 0
            var lowest: Int = Int.MAX_VALUE
            for (mask in 0..7) {
                applyMask(mask)
                drawFormatBits(mask)
                val penalty: Int = penalty()
                if (penalty < lowest) {
                    best = mask
                    lowest = penalty
                }
                applyMask(mask) // the same mask again takes it off
            }
            applyMask(best)
            drawFormatBits(best)
            val flat = BooleanArray(size * size)
            for (y in 0 until size) for (x in 0 until size) flat[y * size + x] = modules[y][x]
            return QrMatrix(size, flat)
        }

        private fun set(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunction[y][x] = true
        }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)
            val positions: IntArray = alignmentPositions()
            for (i in positions.indices) {
                for (j in positions.indices) {
                    // Not where a finder pattern is.
                    if ((i == 0 && j == 0) || (i == 0 && j == positions.size - 1) || (i == positions.size - 1 && j == 0)) continue
                    drawAlignment(positions[i], positions[j])
                }
            }
            drawFormatBits(0) // reserves the place; the real mask is written after the choice
            drawVersion()
        }

        private fun drawFinder(centerX: Int, centerY: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val distance: Int = Math.max(Math.abs(dx), Math.abs(dy))
                    val x: Int = centerX + dx
                    val y: Int = centerY + dy
                    if (x in 0 until size && y in 0 until size) set(x, y, distance != 2 && distance != 4)
                }
            }
        }

        private fun drawAlignment(centerX: Int, centerY: Int) {
            for (dy in -2..2) {
                for (dx in -2..2) set(centerX + dx, centerY + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1)
            }
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val count: Int = version / 7 + 2
            // The distance between patterns, even, as the standard's tables have it (version 7: 6, 22, 38; version 10: 6, 28, 50).
            val step: Int = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
            val result = IntArray(count)
            result[0] = 6
            var position: Int = size - 7
            for (i in count - 1 downTo 1) {
                result[i] = position
                position -= step
            }
            return result
        }

        private fun drawFormatBits(mask: Int) {
            val data: Int = (ECC_FORMAT_BITS shl 3) or mask
            var rem: Int = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits: Int = ((data shl 10) or rem) xor 0x5412
            for (i in 0..5) set(8, i, bit(bits, i))
            set(8, 7, bit(bits, 6))
            set(8, 8, bit(bits, 7))
            set(7, 8, bit(bits, 8))
            for (i in 9..14) set(14 - i, 8, bit(bits, i))
            for (i in 0..7) set(size - 1 - i, 8, bit(bits, i))
            for (i in 8..14) set(8, size - 15 + i, bit(bits, i))
            set(8, size - 8, true) // the one module that is always dark
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem: Int = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits: Int = (version shl 12) or rem
            for (i in 0 until 18) {
                val dark: Boolean = bit(bits, i)
                val a: Int = size - 11 + i % 3
                val b: Int = i / 3
                set(a, b, dark)
                set(b, a, dark)
            }
        }

        /** Data and error correction bits, two columns at a time, from the bottom right, up and down in turn. */
        private fun drawCodewords() {
            var i = 0
            var right: Int = size - 1
            while (right >= 1) {
                if (right == 6) right = 5 // the column of the timing pattern is skipped
                for (vertical in 0 until size) {
                    for (j in 0..1) {
                        val x: Int = right - j
                        val upward: Boolean = ((right + 1) and 2) == 0
                        val y: Int = if (upward) size - 1 - vertical else vertical
                        if (!isFunction[y][x] && i < codewords.size * 8) {
                            modules[y][x] = bit(codewords[i ushr 3].toInt(), 7 - (i and 7))
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val invert: Boolean = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    if (!isFunction[y][x] && invert) modules[y][x] = !modules[y][x]
                }
            }
        }

        /** The standard's four penalty rules: long runs, 2 by 2 blocks, patterns that look like a finder, and too much of one colour. */
        private fun penalty(): Int {
            var result = 0
            for (y in 0 until size) {
                var color = false
                var run = 0
                val history = IntArray(7)
                for (x in 0 until size) {
                    if (modules[y][x] == color) {
                        run++
                        if (run == 5) result += PENALTY_RUN else if (run > 5) result++
                    } else {
                        addHistory(run, history)
                        if (!color) result += countFinderLike(history) * PENALTY_FINDER
                        color = modules[y][x]
                        run = 1
                    }
                }
                result += terminateAndCount(color, run, history) * PENALTY_FINDER
            }
            for (x in 0 until size) {
                var color = false
                var run = 0
                val history = IntArray(7)
                for (y in 0 until size) {
                    if (modules[y][x] == color) {
                        run++
                        if (run == 5) result += PENALTY_RUN else if (run > 5) result++
                    } else {
                        addHistory(run, history)
                        if (!color) result += countFinderLike(history) * PENALTY_FINDER
                        color = modules[y][x]
                        run = 1
                    }
                }
                result += terminateAndCount(color, run, history) * PENALTY_FINDER
            }
            for (y in 0 until size - 1) {
                for (x in 0 until size - 1) {
                    val color: Boolean = modules[y][x]
                    if (color == modules[y][x + 1] && color == modules[y + 1][x] && color == modules[y + 1][x + 1]) result += PENALTY_BLOCK
                }
            }
            var dark = 0
            for (row in modules) for (module in row) if (module) dark++
            val total: Int = size * size
            val k: Int = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            return result + k * PENALTY_BALANCE
        }

        /** 1:1:3:1:1 with a light stretch of four or more on a side, once for each side it has. */
        private fun countFinderLike(history: IntArray): Int {
            val n: Int = history[1]
            val core: Boolean = n > 0 && history[2] == n && history[3] == n * 3 && history[4] == n && history[5] == n
            return (if (core && history[0] >= n * 4 && history[6] >= n) 1 else 0) +
                (if (core && history[6] >= n * 4 && history[0] >= n) 1 else 0)
        }

        private fun terminateAndCount(runColor: Boolean, runLength: Int, history: IntArray): Int {
            var length: Int = runLength
            if (runColor) {
                addHistory(length, history)
                length = 0
            }
            length += size // the light border around the code
            addHistory(length, history)
            return countFinderLike(history)
        }

        private fun addHistory(runLength: Int, history: IntArray) {
            var length: Int = runLength
            if (history[0] == 0) length += size // the first run also has the light border before it
            System.arraycopy(history, 0, history, 1, history.size - 1)
            history[0] = length
        }
    }
}
