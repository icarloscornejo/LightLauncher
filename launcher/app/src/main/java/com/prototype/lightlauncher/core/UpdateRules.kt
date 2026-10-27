package com.prototype.lightlauncher.core

/** One file attached to a release, as GitHub lists it. [digest] is "sha256:<hex>" when GitHub computed one. */
class ReleaseAsset(val name: String, val url: String, val digest: String?, val size: Long)

/** An update that can be installed: the version, the file to download and the SHA-256 it must have (lowercase hex). */
class UpdateOffer(val version: String, val url: String, val sha256: String, val size: Long)

/** What a release means for the installed version. */
sealed class UpdateDecision {
    /** The release is not newer than the installed version. */
    object UpToDate : UpdateDecision()

    data class Offer(val offer: UpdateOffer) : UpdateDecision()

    /** The release is newer but cannot be installed safely: no tag we can read, no single APK, no digest, too big. */
    object Unusable : UpdateDecision()
}

/**
 * Decides what to do with the latest release. It fails closed: a newer release is offered only when
 * its tag is a version, it has exactly one file named LightLauncher-<version>.apk, that file has a SHA-256 digest from
 * GitHub, it is no larger than [MAX_APK_BYTES], and its address is one the updater may fetch. Pure logic.
 */
object UpdateRules {
    const val MAX_APK_BYTES: Int = 20 * 1024 * 1024

    private val SHA256: Regex = Regex("sha256:([0-9a-fA-F]{64})")

    fun decide(tag: String, assets: List<ReleaseAsset>, installedVersion: String): UpdateDecision {
        val version: String = AppVersion.canonical(tag) ?: return UpdateDecision.Unusable
        if (!AppVersion.isNewer(version, installedVersion)) return UpdateDecision.UpToDate
        val matching: List<ReleaseAsset> = assets.filter { it.name == "LightLauncher-$version.apk" }
        val asset: ReleaseAsset = matching.singleOrNull() ?: return UpdateDecision.Unusable
        val hash: String = asset.digest?.let { SHA256.matchEntire(it)?.groupValues?.get(1) }?.lowercase() ?: return UpdateDecision.Unusable
        if (asset.size <= 0L || asset.size > MAX_APK_BYTES || !UpdateUrls.isAllowed(asset.url)) return UpdateDecision.Unusable
        return UpdateDecision.Offer(UpdateOffer(version, asset.url, hash, asset.size))
    }
}
