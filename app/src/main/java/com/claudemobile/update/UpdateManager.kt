package com.claudemobile.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String
)

class UpdateManager(private val context: Context) {

    companion object {
        private const val VERSION_URL = "http://100.110.253.52:8888/claude-mobile-version.json"
        private const val APK_FILENAME = "claude-mobile-update.apk"
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.useCaches = false
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val obj = JSONObject(json)
            val remoteCode = obj.getInt("versionCode")
            val currentCode = context.packageManager
                .getPackageInfo(context.packageName, 0).let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt()
                    else @Suppress("DEPRECATION") it.versionCode
                }

            if (remoteCode > currentCode) {
                UpdateInfo(
                    versionCode = remoteCode,
                    versionName = obj.getString("versionName"),
                    url = obj.getString("url")
                )
            } else null
        } catch (e: Exception) {
            throw e
        }
    }

    fun downloadAndInstall(update: UpdateInfo, onComplete: () -> Unit = {}) {
        // Clean up old downloads
        val apkFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            APK_FILENAME
        )
        if (apkFile.exists()) apkFile.delete()

        val request = DownloadManager.Request(Uri.parse(update.url))
            .setTitle("Claude Mobile v${update.versionName}")
            .setDescription("Downloading update...")
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                APK_FILENAME
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    context.unregisterReceiver(this)
                    onComplete()
                    installApk(apkFile)
                }
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED
        )
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
