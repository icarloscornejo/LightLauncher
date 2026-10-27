package com.prototype.lightlauncher.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import com.prototype.lightlauncher.core.AppVersion
import java.io.File
import java.io.IOException

/**
 * Hands a downloaded APK to the system installer (a PackageInstaller session). The system asks the owner
 * to confirm, and only installs it over this app if it is signed with the same key. [resultReceiver] is the broadcast
 * receiver that gets the session's answer.
 */
class ApkInstaller(
    private val context: Context,
    private val resultReceiver: Class<*>,
    private val logger: Logger,
) {
    /**
     * Checks that [apk] is this app at [expectedVersion], then commits a session for it. True when the session was
     * committed (the answer comes to the receiver); false when the file is not what was expected or the session failed
     * (an abandoned session is never left behind).
     */
    fun install(apk: File, expectedVersion: String): Boolean {
        if (!isExpected(apk, expectedVersion)) {
            logger.warn("Update install refused: the file is not this app at the expected version")
            return false
        }
        val installer: PackageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(SESSION_FILE, 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                // Explicit and mutable: the system adds the status and the confirmation screen to it.
                val intent = Intent(context, resultReceiver)
                val pending: PendingIntent = PendingIntent.getBroadcast(
                    context, sessionId, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(pending.intentSender)
            }
            return true
        } catch (e: IOException) {
            logger.warn("Update install failed: the session could not be written")
        } catch (e: SecurityException) {
            logger.warn("Update install failed: not allowed to install")
        } catch (e: IllegalStateException) {
            logger.warn("Update install failed: the session is not usable")
        } catch (e: IllegalArgumentException) {
            logger.warn("Update install failed: the session was refused")
        }
        if (sessionId != -1) {
            try {
                installer.abandonSession(sessionId)
            } catch (e: SecurityException) {
                logger.warn("Update install: the session could not be abandoned")
            }
        }
        return false
    }

    private fun isExpected(apk: File, expectedVersion: String): Boolean {
        @Suppress("DEPRECATION")
        val info: PackageInfo = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return false
        return info.packageName == context.packageName && AppVersion.canonical(info.versionName ?: "") == expectedVersion
    }

    private companion object {
        const val SESSION_FILE: String = "LightLauncher.apk"
    }
}
