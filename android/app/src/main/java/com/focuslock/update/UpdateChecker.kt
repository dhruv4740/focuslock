package com.focuslock.update

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object UpdateChecker {

    private const val TAG = "UpdateChecker"

    private const val GITHUB_REPO = "dhruv4740/focuslock"

    fun checkForUpdate(activity: Activity) {
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
                    return@Thread
                }

                val body = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "") ?: ""
                if (tagName.isEmpty()) return@Thread

                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                var shaUrl: String? = null
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

                val currentVersion = try {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0"
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read current version", e)
                    return@Thread
                }

                if (!isRemoteNewer(tagName, currentVersion)) return@Thread
                if (apkUrl.isNullOrEmpty()) return@Thread

                val finalApkUrl = apkUrl
                val finalShaUrl = shaUrl
                val displayVersion = tagName.removePrefix("v").removePrefix("V")
                activity.runOnUiThread {
                    AlertDialog.Builder(activity)
                        .setTitle("Update available")
                        .setMessage("FocusLock $displayVersion is available. Update now?")
                        .setCancelable(true)
                        .setPositiveButton("Update") { _, _ ->
                            downloadAndInstall(activity, finalApkUrl, finalShaUrl)
                        }
                        .setNegativeButton("Later") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
            }
        }.start()
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

    private fun downloadAndInstall(activity: Activity, url: String, shaUrl: String?) {
        try {
            val downloadsDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return
            val apkFile = File(downloadsDir, "focuslock-update.apk")
            if (apkFile.exists()) apkFile.delete()

            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("FocusLock update")
                .setDestinationUri(Uri.fromFile(apkFile))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val downloadId = dm.enqueue(request)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id != downloadId) return
                    try {
                        activity.unregisterReceiver(this)
                    } catch (e: Exception) {
                        Log.w(TAG, "Unregister receiver failed", e)
                    }

                    if (!shaUrl.isNullOrEmpty()) {
                        val expected = fetchText(shaUrl)?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()
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
                                Toast.makeText(activity, "Update failed verification", Toast.LENGTH_LONG).show()
                                return
                            }
                        }
                    }

                    if (!activity.packageManager.canRequestPackageInstalls()) {
                        Toast.makeText(
                            activity,
                            "Allow FocusLock to install updates, then tap Update again",
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
                        return
                    }

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

            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            } else {
                activity.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "downloadAndInstall failed", e)
        }
    }
}
