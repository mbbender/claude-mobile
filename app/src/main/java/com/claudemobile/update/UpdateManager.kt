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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String
)

class UpdateManager(private val context: Context, private val scope: CoroutineScope) {

    private val _downloadProgress = MutableStateFlow(-1f) // -1 = not downloading
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    companion object {
        private const val VERSION_URL_KEY = "update_server_url"
        private const val DEFAULT_VERSION_PATH = "/claude-mobile-version.json"
        private const val APK_FILENAME = "claude-mobile-update.apk"

        fun getVersionUrl(context: Context): String {
            val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
            val baseUrl = prefs.getString(VERSION_URL_KEY, null)
            return if (baseUrl != null) "$baseUrl$DEFAULT_VERSION_PATH"
            else "${com.claudemobile.BuildConfig.UPDATE_SERVER_URL}$DEFAULT_VERSION_PATH"
        }

        fun setUpdateServer(context: Context, url: String) {
            context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                .edit().putString(VERSION_URL_KEY, url).apply()
        }
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(getVersionUrl(context)).openConnection() as HttpURLConnection
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
        startDownload(update, onComplete, retriesLeft = 1)
    }

    private fun startDownload(update: UpdateInfo, onComplete: () -> Unit, retriesLeft: Int) {
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

        _downloadProgress.value = 0f

        // Poll download progress and auto-retry on failure
        scope.launch(Dispatchers.IO) {
            var done = false
            while (!done) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                dm.query(query)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            done = true
                            _downloadProgress.value = 1f
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            done = true
                            if (retriesLeft > 0) {
                                delay(500)
                                startDownload(update, onComplete, retriesLeft - 1)
                            } else {
                                _downloadProgress.value = -1f
                            }
                        } else {
                            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            if (total > 0) {
                                _downloadProgress.value = downloaded.toFloat() / total.toFloat()
                            }
                        }
                    }
                }
                if (!done) delay(200)
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    context.unregisterReceiver(this)
                    _downloadProgress.value = -1f
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
