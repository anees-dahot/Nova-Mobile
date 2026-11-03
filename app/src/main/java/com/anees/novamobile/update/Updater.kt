package com.anees.novamobile.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import com.anees.novamobile.BuildConfig
import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.ui.common.ActionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Self-update from GitHub Releases (anees-dahot/Nova-Movies-TV-App). Publishing a release with
 * the APK attached is all it takes: the TV offers it on next start.
 */
object Updater {
    // Phone builds are published as releases tagged "mobile-v1.0" with an APK named *mobile*.apk
    private const val LATEST = "https://api.github.com/repos/anees-dahot/Nova-Movies-TV-App/releases/latest"

    data class Release(val version: String, val notes: String, val apkUrl: String)

    fun check(activity: Activity, scope: LifecycleCoroutineScope, manual: Boolean = false) {
        scope.launch {
            val rel = withContext(Dispatchers.IO) { latest() }
            when {
                rel == null -> if (manual) toast(activity, "Couldn't reach GitHub")
                !isNewer(rel.version, BuildConfig.VERSION_NAME) -> if (manual) toast(activity, "You're on the latest version (${BuildConfig.VERSION_NAME})")
                !manual && LocalStore.skippedUpdate() == rel.version -> {}
                else -> ActionSheet.show(activity, "Nova TV ${rel.version} is available", listOf(
                    ActionSheet.Item("Update now") { download(activity, scope, rel) },
                    ActionSheet.Item("Later"),
                    ActionSheet.Item("Skip this version") { LocalStore.setSkippedUpdate(rel.version) }
                ), subtitle = rel.notes.lineSequence().firstOrNull { it.isNotBlank() }?.take(90) ?: "You have ${BuildConfig.VERSION_NAME}")
            }
        }
    }

    private fun latest(): Release? = runCatching {
        val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
        RetrofitClient.base.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = JSONObject(r.body?.string() ?: return null)
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") && it.getString("name").contains("mobile", true) }?.getString("browser_download_url") ?: return null
            Release(o.getString("tag_name").removePrefix("mobile-").removePrefix("v"), o.optString("body"), apk)
        }
    }.getOrNull()

    /** "2.10" > "2.9", "2.2" > "2.1.5". */
    fun isNewer(remote: String, local: String): Boolean {
        val a = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val b = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun download(activity: Activity, scope: LifecycleCoroutineScope, rel: Release) {
        // Android 8+: the user must allow this app to install updates once
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            ActionSheet.show(activity, "Allow updates", listOf(
                ActionSheet.Item("Open settings") {
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    }.onFailure { toast(activity, "Open Settings → Apps → Nova TV → Install unknown apps") }
                },
                ActionSheet.Item("Cancel")
            ), subtitle = "Turn on “Install unknown apps” for Nova TV, then choose Update again")
            return
        }
        toast(activity, "Downloading Nova TV ${rel.version}…")
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(activity.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                    val out = File(dir, "nova-${rel.version}.apk")
                    RetrofitClient.base.newCall(Request.Builder().url(rel.apkUrl).build()).execute().use { r ->
                        if (!r.isSuccessful) return@runCatching null
                        r.body?.byteStream()?.use { input -> out.outputStream().use { input.copyTo(it) } }
                    }
                    out.takeIf { it.length() > 100_000 }
                }.getOrNull()
            }
            if (file == null) { toast(activity, "Download failed — try again later"); return@launch }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun toast(a: Activity, msg: String) = Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()
}
