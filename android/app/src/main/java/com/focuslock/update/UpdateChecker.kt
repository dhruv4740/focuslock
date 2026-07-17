package com.focuslock.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.focuslock.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

object UpdateChecker {

    private const val TAG = "UpdateChecker"

    private const val GITHUB_REPO = "dhruv4740/focuslock"
    private const val APK_FILE_NAME = "focuslock-update.apk"

    internal data class ReleaseInfo(
        val tagName: String,
        val apkUrl: String?,
        val shaUrl: String?,
        val notes: String
    )

    // A downloaded, checksum-verified APK waiting on the "install unknown apps"
    // permission. Kept so returning from Settings can resume the install
    // directly instead of forcing a re-download.
    @Volatile private var pendingVerifiedApk: File? = null

    private fun toast(activity: Activity, message: String) {
        activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    }

    /**
     * [manual] = user tapped "check for updates": give toast feedback for the
     * up-to-date and failure cases. The automatic launch check stays silent.
     */
    fun checkForUpdate(activity: Activity, manual: Boolean = false) {
        Thread {
            try {
                val apiUrl = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
                val connection = URL(apiUrl).openConnection() as HttpURLConnection
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "FocusLock-Updater")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "GitHub API returned $responseCode")
                    if (manual) toast(activity, "Update check failed (server returned $responseCode)")
                    return@Thread
                }

                val body = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                val info = parseRelease(body) ?: return@Thread

                val currentVersion = try {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0"
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read current version", e)
                    return@Thread
                }

                if (!isRemoteNewer(info.tagName, currentVersion)) {
                    if (manual) toast(activity, "You're on the latest version ($currentVersion)")
                    return@Thread
                }
                if (info.apkUrl.isNullOrEmpty()) {
                    if (manual) toast(activity, "Update ${info.tagName} found but it has no APK attached")
                    return@Thread
                }

                val displayVersion = info.tagName.removePrefix("v").removePrefix("V")
                activity.runOnUiThread {
                    showUpdateDialog(activity, currentVersion, displayVersion, info)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                if (manual) toast(activity, "Update check failed — are you online?")
            }
        }.start()
    }

    private fun showUpdateDialog(activity: Activity, currentVersion: String, newVersion: String, info: ReleaseInfo) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_update, null)
        view.findViewById<TextView>(R.id.txtUpdateVersions).text =
            "Current: v$currentVersion   →   New: v$newVersion"
        val notes = info.notes.trim()
        view.findViewById<TextView>(R.id.txtUpdateNotes).text =
            notes.ifEmpty { "No release notes provided." }

        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setView(view)
            .setCancelable(true)
            .setPositiveButton("Update") { _, _ -> downloadWithProgress(activity, info) }
            .setNegativeButton("Later") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Call from Activity.onResume(). If an update APK was downloaded and
     * verified but installation was blocked on the "install unknown apps"
     * permission, and that permission has since been granted (user returning
     * from Settings), fire the install now instead of making them re-download.
     */
    fun resumePendingInstall(activity: Activity) {
        val apkFile = pendingVerifiedApk ?: return
        if (!apkFile.exists()) {
            pendingVerifiedApk = null
            return
        }
        if (!activity.packageManager.canRequestPackageInstalls()) return
        pendingVerifiedApk = null
        installApk(activity, apkFile)
    }

    /** Deletes the fixed-name update APK, e.g. after a successful install. */
    fun cleanupDownloadedApk(context: Context) {
        try {
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            File(downloadsDir, APK_FILE_NAME).delete()
        } catch (e: Exception) {
            Log.w(TAG, "cleanupDownloadedApk failed", e)
        }
    }

    /**
     * Parses a GitHub "latest release" API response into the fields the updater
     * needs. Returns null when there's no usable tag name (malformed/empty
     * response) — a missing APK asset or release-notes body is left null/blank
     * for the caller to handle.
     */
    internal fun parseRelease(body: String): ReleaseInfo? {
        val json = JSONObject(body)
        val tagName = json.optString("tag_name", "")
        if (tagName.isEmpty()) return null

        var apkUrl: String? = null
        var shaUrl: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val downloadUrl = asset.optString("browser_download_url", "").ifEmpty { null }
                if (name.endsWith(".apk")) {
                    apkUrl = downloadUrl
                } else if (name.endsWith(".apk.sha256")) {
                    shaUrl = downloadUrl
                }
            }
        }
        val notes = json.optString("body", "")
        return ReleaseInfo(tagName, apkUrl, shaUrl, notes)
    }

    /**
     * Compares a GitHub release tag (e.g. "v1.2.3", "V0.3.0", "0.10.0-beta") against
     * the installed versionName. Tolerant of missing/uppercase "v" prefix and
     * pre-release/build-metadata suffixes; non-numeric segments count as 0.
     */
    internal fun isRemoteNewer(remoteTag: String, current: String): Boolean {
        fun parse(v: String): List<Int> = v.trim()
            .removePrefix("v").removePrefix("V")
            .substringBefore('+')
            .substringBefore('-')
            .split(".")
            .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

        val remoteParts = parse(remoteTag)
        val currentParts = parse(current)
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fetchText(url: String): String? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val text = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            text
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch $url", e)
            null
        }
    }

    private fun downloadWithProgress(activity: Activity, info: ReleaseInfo) {
        val apkUrl = info.apkUrl ?: return
        val progressView = activity.layoutInflater.inflate(R.layout.dialog_update_progress, null)
        val statusText = progressView.findViewById<TextView>(R.id.txtUpdateStatus)
        val progressBar = progressView.findViewById<ProgressBar>(R.id.progressUpdate)
        val percentText = progressView.findViewById<TextView>(R.id.txtUpdatePercent)
        val cancelled = AtomicBoolean(false)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Updating FocusLock")
            .setView(progressView)
            .setCancelable(false)
            .setNegativeButton("Cancel") { d, _ ->
                cancelled.set(true)
                d.dismiss()
            }
            .create()
        dialog.show()

        Thread {
            val downloadsDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir == null) {
                activity.runOnUiThread { dialog.dismiss() }
                toast(activity, "Update failed — storage unavailable")
                return@Thread
            }
            val apkFile = File(downloadsDir, APK_FILE_NAME)
            try {
                if (apkFile.exists()) apkFile.delete()

                val connection = URL(apkUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = true

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    activity.runOnUiThread { dialog.dismiss() }
                    toast(activity, "Download failed (server returned $responseCode)")
                    return@Thread
                }

                val totalBytes = connection.contentLengthLong
                activity.runOnUiThread { progressBar.isIndeterminate = totalBytes <= 0 }

                var bytesRead = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (cancelled.get()) throw CancellationException("Update download cancelled")
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (totalBytes > 0) {
                                val percent = ((bytesRead * 100) / totalBytes).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    activity.runOnUiThread {
                                        progressBar.progress = percent
                                        percentText.text = "$percent%"
                                    }
                                }
                            }
                        }
                    }
                }
                connection.disconnect()

                activity.runOnUiThread { statusText.text = "Verifying…" }

                if (!info.shaUrl.isNullOrEmpty()) {
                    val expected = fetchText(info.shaUrl)?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()
                    if (expected != null) {
                        val actual = try {
                            sha256Hex(apkFile)
                        } catch (e: Exception) {
                            Log.w(TAG, "Hash check failed", e)
                            null
                        }
                        if (actual == null || !actual.equals(expected, ignoreCase = true)) {
                            Log.w(TAG, "APK checksum mismatch, aborting install")
                            apkFile.delete()
                            activity.runOnUiThread {
                                dialog.dismiss()
                                Toast.makeText(activity, "Update failed verification", Toast.LENGTH_LONG).show()
                            }
                            return@Thread
                        }
                    }
                }

                if (!activity.packageManager.canRequestPackageInstalls()) {
                    // Keep the verified APK around — when the user grants the
                    // permission and comes back, resumePendingInstall() picks
                    // it up with no re-download.
                    pendingVerifiedApk = apkFile
                    activity.runOnUiThread {
                        dialog.dismiss()
                        Toast.makeText(
                            activity,
                            "Allow FocusLock to install updates — the update will continue when you return",
                            Toast.LENGTH_LONG
                        ).show()
                        try {
                            val settingsIntent = Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            activity.startActivity(settingsIntent)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not open unknown-sources settings", e)
                        }
                    }
                    return@Thread
                }

                activity.runOnUiThread {
                    statusText.text = "Installing…"
                    progressBar.isIndeterminate = true
                }
                installApk(activity, apkFile)
                activity.runOnUiThread { dialog.dismiss() }
            } catch (e: CancellationException) {
                apkFile.delete()
                Log.d(TAG, "Update download cancelled by user")
            } catch (e: Exception) {
                Log.w(TAG, "Update download failed", e)
                apkFile.delete()
                activity.runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(activity, "Update failed — are you online?", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * Installs via a PackageInstaller session so the app stays in control of the
     * flow (vs. handing the user off to "open this downloaded file"). Android
     * still shows its own confirmation dialog for a sideloaded app — that part
     * can't be skipped. Falls back to the legacy ACTION_VIEW/FileProvider flow
     * if session creation fails for any reason.
     */
    private fun installApk(activity: Activity, apkFile: File) {
        try {
            val installer = activity.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(activity.packageName)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite(APK_FILE_NAME, 0, apkFile.length()).use { out ->
                    apkFile.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val statusIntent = Intent(activity, InstallStatusReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pendingIntent = PendingIntent.getBroadcast(activity, sessionId, statusIntent, flags)
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PackageInstaller session failed, falling back to legacy install", e)
            launchInstallLegacy(activity, apkFile)
        }
    }

    private fun launchInstallLegacy(activity: Activity, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "com.focuslock.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Install intent failed", e)
        }
    }
}
