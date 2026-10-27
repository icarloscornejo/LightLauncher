package com.prototype.lightlauncher.core

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * The part of an HTTP request the link page needs: the method, the path (no query), the fields of a form body, and the
 * language the phone asked for.
 */
class LinkRequest(val method: String, val path: String, val fields: Map<String, String>, val acceptLanguage: String? = null) {

    companion object {
        /** More than this and the request is refused: the page only ever sends one short link. */
        const val MAX_BYTES: Int = 4096

        /** Where the header ends and the body begins. */
        private const val HEADER_END: String = "\r\n\r\n"

        /** The index of the end of the headers in [raw], or -1 while they are not all there yet. */
        fun headerEnd(raw: String): Int = raw.indexOf(HEADER_END).let { if (it < 0) -1 else it + HEADER_END.length }

        /** The Content-Length the headers declare, 0 when there is none, -1 when it is not a number. */
        fun contentLength(raw: String): Int {
            val head: String = raw.substring(0, Math.max(0, raw.indexOf(HEADER_END)))
            val line: String = head.split("\r\n").drop(1).firstOrNull { it.startsWith("content-length:", ignoreCase = true) } ?: return 0
            return line.substringAfter(':').trim().toIntOrNull()?.takeIf { it >= 0 } ?: -1
        }

        /**
         * Reads a complete request. Null when it is not one this page understands: no request line, a method other
         * than GET or POST, a target that is not a path, or a body that does not decode.
         */
        fun parse(raw: String): LinkRequest? {
            val end: Int = headerEnd(raw)
            if (end < 0) return null
            val requestLine: String = raw.substringBefore("\r\n").trim()
            val parts: List<String> = requestLine.split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
            val method: String = parts[0]
            if (method != "GET" && method != "POST") return null
            val target: String = parts[1]
            if (!target.startsWith("/")) return null
            val path: String = target.substringBefore('?')
            val fields: Map<String, String> = if (method == "POST") {
                parseForm(raw.substring(end)) ?: return null
            } else {
                emptyMap()
            }
            val language: String? = raw.substring(0, end).split("\r\n").drop(1)
                .firstOrNull { it.startsWith("accept-language:", ignoreCase = true) }?.substringAfter(':')?.trim()
            return LinkRequest(method, path, fields, language)
        }

        /** `a=1&b=x%20y` to a map; null when a part cannot be decoded. */
        private fun parseForm(body: String): Map<String, String>? {
            val fields: MutableMap<String, String> = LinkedHashMap()
            for (pair in body.split('&')) {
                if (pair.isEmpty()) continue
                val name: String = pair.substringBefore('=')
                val value: String = if ('=' in pair) pair.substringAfter('=') else ""
                try {
                    fields[URLDecoder.decode(name, StandardCharsets.UTF_8.name())] = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
                } catch (e: IllegalArgumentException) {
                    return null
                }
            }
            return fields
        }
    }
}
