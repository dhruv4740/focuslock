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
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val TAG = "UpdateChecker"

    // TODO: set to the published GitHub repo, e.g. "dhruvk/focuslock"
    private const val GITHUB_REPO = "OWNER/REPO"

    fun checkForUpdate(activity: Activity) {
        if (GITHUB_REPO == "OWNER/REPO") return

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
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", null)
                            break
                        }
                    }
                }

                val remoteVersion = tagName.removePrefix("v")
                val currentVersion = try {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0"
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read current version", e)
                    return@Thread
                }

                if (!isRemoteNewer(remoteVersion, currentVersion)) return@Thread
                if (apkUrl.isNullOrEmpty()) return@Thread

                val finalApkUrl = apkUrl
                activity.runOnUiThread {
                    AlertDialog.Builder(activity)
                        .setTitle("Update available")
                        .setMessage("FocusLock $remoteVersion is available. Update now?")
                        .setCancelable(true)
                        .setPositiveButton("Update") { _, _ ->
                            downloadAndInstall(activity, finalApkUrl, remoteVersion)
                        }
                        .setNegativeButton("Later") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
            }
        }.start()
    }

    private fun isRemoteNewer(remote: String, current: String): Boolean {
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun downloadAndInstall(activity: Activity, url: String, remoteVersion: String) {
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
                ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            } else {
                activity.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "downloadAndInstall failed", e)
        }
    }
}
