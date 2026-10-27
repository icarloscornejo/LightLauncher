package com.prototype.lightlauncher.core

/**
 * Reads a release tag ("v1.2.0", "1.10") as numbers and compares two of them, so 1.10 is newer than 1.9
 * (a text comparison gets that wrong). Pure logic.
 */
object AppVersion {
    private const val MAX_PARTS: Int = 4
    private const val MAX_DIGITS: Int = 5

    /** The numbers of [text] without a leading "v", or null when it is not a dotted list of plain numbers. */
    fun parse(text: String): List<Int>? {
        val bare: String = text.trim().removePrefix("v").removePrefix("V")
        val parts: List<String> = bare.split('.')
        if (parts.isEmpty() || parts.size > MAX_PARTS) return null
        val numbers: MutableList<Int> = ArrayList()
        for (part in parts) {
            if (part.isEmpty() || part.length > MAX_DIGITS || !part.all { it in '0'..'9' }) return null
            numbers.add(part.toInt())
        }
        return numbers
    }

    /** "1.2.0" for "v1.2" or "1.2.0": the form used in file names. Null for an invalid tag. */
    fun canonical(text: String): String? {
        val numbers: List<Int> = parse(text) ?: return null
        val padded: List<Int> = numbers + List(maxOf(0, 3 - numbers.size)) { 0 }
        return padded.joinToString(".")
    }

    /** True when [candidate] is a higher version than [installed]; false when either one is not a version. */
    fun isNewer(candidate: String, installed: String): Boolean {
        val left: List<Int> = parse(candidate) ?: return false
        val right: List<Int> = parse(installed) ?: return false
        for (index in 0 until maxOf(left.size, right.size)) {
            val difference: Int = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (difference != 0) return difference > 0
        }
        return false
    }
}
