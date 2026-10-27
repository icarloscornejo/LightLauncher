package com.prototype.lightlauncher.core

/**
 * Some image hosts let the address say how big a picture it wants, and the apps that publish to the TV
 * provider ask for small ones (a 600 px poster, a 640 px thumbnail). For the few hosts where the size is part of a
 * known address, [candidates] gives the bigger address to try first, then the published one, so a host that does not
 * have the bigger picture costs one failed request and nothing else. Every address, the bigger one included, still goes
 * through [ShelfImageUrl] and the loader's limits.
 *
 * ponytail: two hosts, found by reading what the dock apps publish (Max's CDN takes a `w=` width, YouTube keeps a
 * 1280 px `maxresdefault`). Add a rule when another dock app's pictures turn out to have a size knob.
 */
object ShelfImageUpgrade {
    /** From this target width a picture fills the screen; a smaller target is a card. */
    const val FULL_SCREEN_WIDTH: Int = 1600
    private const val MAX_WIDTH: Int = 1920

    private val MAX_WIDTH_PARAMETER: Regex = Regex("([?&]w=)(\\d{2,4})(?=&|$)")
    private val YOUTUBE_THUMBNAIL: Regex = Regex("^https://i\\.ytimg\\.com/vi/([A-Za-z0-9_-]{11})/(?:default|mqdefault|hqdefault|sddefault)(?:_custom_\\d+)?\\.jpg(?:\\?.*)?$")
    private const val MAX_HOST_PREFIX: String = "https://images.cdn.prd.api.discomax.com/"

    /** Addresses to try for [address], best first; the published address is always last. */
    fun candidates(address: String, targetWidth: Int): List<String> {
        val better: String? = widerWidthParameter(address, targetWidth) ?: youtubeMaxResolution(address, targetWidth)
        return if (better == null || better == address) listOf(address) else listOf(better, address)
    }

    /** Max's CDN: `...jpeg?w=600&q=85` asks for 600 px wide; the same address with a bigger `w` gives a bigger picture. */
    private fun widerWidthParameter(address: String, targetWidth: Int): String? {
        if (!address.startsWith(MAX_HOST_PREFIX)) return null
        val match: MatchResult = MAX_WIDTH_PARAMETER.find(address) ?: return null
        val wanted: Int = Math.min(targetWidth, MAX_WIDTH)
        if (match.groupValues[2].toInt() >= wanted) return null
        return address.replaceRange(match.range, match.groupValues[1] + wanted)
    }

    /**
     * YouTube thumbnails: the 640 px ones (4:3 with black bars) have a clean 16:9 1280 px sibling. Only for cards: a
     * thumbnail full of title text is fine in a small card and wrong as a backdrop.
     */
    private fun youtubeMaxResolution(address: String, targetWidth: Int): String? {
        if (targetWidth >= FULL_SCREEN_WIDTH) return null
        val match: MatchResult = YOUTUBE_THUMBNAIL.matchEntire(address) ?: return null
        return "https://i.ytimg.com/vi/${match.groupValues[1]}/maxresdefault.jpg"
    }
}
