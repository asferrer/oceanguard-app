package com.oceanguard.ai.utils

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import com.google.gson.JsonParser
import com.oceanguard.ai.BuildConfig
import com.oceanguard.ai.R
import com.oceanguard.ai.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * True when release [latest] (e.g. "1.2.0") is newer than the installed [current].
 * Versions compare by their numeric x.y.z part. A build after a tag
 * (`git describe`: "1.1.1-30-g6014fca") or a release candidate of a later
 * version ("1.2.0-rc1") is never offered an older or equal release, which the
 * former string inequality did.
 */
internal fun isNewerVersion(latest: String, current: String): Boolean {
    fun numbers(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = numbers(latest)
    val b = numbers(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    // Same x.y.z: only a release candidate of it ("1.2.0-rc1") is older than the release.
    return current.contains("-rc", ignoreCase = true) && !latest.contains('-')
}

data class UpdateInfo(
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
)

/**
 * Checks GitHub Releases API for newer app versions.
 * Rate-limited to one check per 24 hours. Fails silently on network errors.
 */
class UpdateChecker(private val settings: SettingsRepository) {

    private companion object {
        const val TAG = "UpdateChecker"
        const val CHECK_INTERVAL_MS = 24 * 3600 * 1000L
        const val RELEASES_URL =
            "https://api.github.com/repos/asferrer/oceanguard-app/releases/latest"
        const val DOWNLOAD_URL =
            "https://github.com/asferrer/oceanguard-app/releases/latest/download/OceanGuard-AI-latest.apk"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(): UpdateInfo? {
        val lastCheck = settings.lastUpdateCheckMs.first()
        if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) return null

        val info = runCatching { fetchLatestRelease() }
            .onFailure { Log.w(TAG, "Update check failed: ${it.message}") }
            .getOrNull() ?: return null

        settings.setLastUpdateCheckMs(System.currentTimeMillis())

        val currentVersion = BuildConfig.VERSION_NAME
            .removeSuffix("-debug")
        if (!isNewerVersion(info.versionName, currentVersion)) return null

        val skipped = settings.skippedVersion.first()
        if (info.versionName == skipped) return null

        return info
    }

    fun downloadApk(context: Context, info: UpdateInfo) {
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
            .setTitle("OceanGuard AI v${info.versionName}")
            .setDescription(context.getString(R.string.update_downloading))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "OceanGuard-AI-${info.versionName}.apk",
            )
            .setMimeType("application/vnd.android.package-archive")

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(context, context.getString(R.string.update_download_started), Toast.LENGTH_SHORT).show()
    }

    private suspend fun fetchLatestRelease(): UpdateInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .build()

        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body?.string() ?: error("Empty body")
            val json = JsonParser.parseString(body).asJsonObject
            val tagName = json.get("tag_name").asString.removePrefix("v")
            val notes = json.get("body")?.asString ?: ""
            UpdateInfo(tagName, notes, DOWNLOAD_URL)
        }
    }
}
