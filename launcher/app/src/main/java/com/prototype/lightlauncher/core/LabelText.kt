package com.prototype.lightlauncher.core

/** Text hygiene for labels that come from other apps. Pure, so it is tested on the JVM. */
object LabelText {
    /**
     * An app's own label as the tile shows it: control, format (bidi overrides, zero width joiners),
     * line and paragraph separator characters become spaces, runs of spaces collapse, at most 40
     * characters (never cutting a surrogate pair), the fallback (package name) when nothing is left.
     */
    fun clean(raw: String?, fallback: String): String {
        if (raw == null) return fallback
        val out = StringBuilder(Math.min(raw.length, 48))
        var space = true // leading spaces are dropped too
        var index = 0
        while (index < raw.length) {
            val c: Char = raw[index]
            val type: Int = Character.getType(c)
            val blank: Boolean = c == ' ' || Character.isISOControl(c) || Character.isWhitespace(c) ||
                type == Character.FORMAT.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt() || type == Character.SPACE_SEPARATOR.toInt()
            if (blank) {
                if (!space) out.append(' ')
                space = true
                index++
                continue
            }
            val pair: Boolean = Character.isHighSurrogate(c) && index + 1 < raw.length && Character.isLowSurrogate(raw[index + 1])
            if (out.length + (if (pair) 2 else 1) > 40) break
            out.append(c)
            if (pair) out.append(raw[++index])
            space = false
            index++
        }
        val cleaned: String = out.toString().trim()
        return if (cleaned.isEmpty()) fallback else cleaned
    }
}
