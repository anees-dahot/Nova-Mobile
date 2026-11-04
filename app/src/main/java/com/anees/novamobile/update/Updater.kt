package com.anees.novamobile.update

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import com.anees.novamobile.BuildConfig
import com.anees.novamobile.R
import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.ui.common.Ui
import com.anees.novamobile.ui.common.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Self-update from GitHub Releases (anees-dahot/Nova-Mobile): publish a release tagged vX.Y with
 * the APK attached, and the app offers it on next start in a sheet with the changelog; one tap
 * downloads it with live progress and opens the installer.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/anees-dahot/Nova-Mobile/releases/latest"
    /** Automatic checks at most this often; Settings → Check for Updates always checks. */
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    fun check(activity: Activity, scope: LifecycleCoroutineScope, manual: Boolean = false) {
        if (!manual && System.currentTimeMillis() - LocalStore.lastUpdateCheck() < CHECK_EVERY_MS) return
        scope.launch {
            val rel = withContext(Dispatchers.IO) { latest() }
            if (rel != null) LocalStore.setLastUpdateCheck(System.currentTimeMillis())
            if (activity.isFinishing) return@launch
            when {
                rel == null -> if (manual) toast(activity, "Couldn't reach GitHub")
                !isNewer(rel.version, BuildConfig.VERSION_NAME) -> if (manual) toast(activity, "You're on the latest version (${BuildConfig.VERSION_NAME})")
                !manual && LocalStore.skippedUpdate() == rel.version -> {}
                else -> Sheet(activity, scope, rel).show()
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
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
            Release(o.getString("tag_name").removePrefix("mobile-").removePrefix("v"), o.optString("body"),
                apk.getString("browser_download_url"), apk.optLong("size"))
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

    private fun toast(a: Activity, msg: String) = Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()

    private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)

    /** Bottom sheet: icon, "1.0 → 1.1", changelog, then the Update button turns into the download's progress. */
    private class Sheet(val act: Activity, val scope: LifecycleCoroutineScope, val rel: Release) {
        private val dialog = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        private val button = TextView(act)
        private val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal)
        private val status = TextView(act)
        private val later = TextView(act)
        private var job: Job? = null
        private var apk: File? = null

        fun show() {
            val sheet = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(Ui.SURFACE); val r = 22f.dp; cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
                isClickable = true
                setPadding(22.dp, 8.dp, 22.dp, 22.dp)
                addView(View(act).apply { background = GradientDrawable().apply { setColor(0x4DFFFFFF); cornerRadius = 3f.dp } },
                    LinearLayout.LayoutParams(36.dp, 5.dp).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = 18.dp })

                val icon = ImageView(act).apply {
                    setImageResource(R.mipmap.ic_launcher)
                    scaleX = 0.6f; scaleY = 0.6f; alpha = 0f
                    animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(120).setDuration(420).setInterpolator(OvershootInterpolator(2.2f)).start()
                }
                addView(icon, LinearLayout.LayoutParams(72.dp, 72.dp).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(TextView(act).apply {
                    text = "A new Nova is here"; textSize = 22f; setTextColor(Ui.TEXT); gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, 14.dp, 0, 6.dp)
                })
                // Version chip: 1.0 → 1.1
                addView(TextView(act).apply {
                    text = "${BuildConfig.VERSION_NAME}  →  ${rel.version}" + if (rel.size > 0) "   ·   ${mb(rel.size)}" else ""
                    textSize = 13f; setTextColor(Ui.ACCENT); fontFeatureSettings = "tnum"
                    background = GradientDrawable().apply { setColor(0x260A84FF); cornerRadius = 14f.dp }
                    setPadding(14.dp, 6.dp, 14.dp, 6.dp)
                }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = 18.dp })

                val notes = cleanNotes(rel.notes)
                if (notes.isNotEmpty()) {
                    addView(TextView(act).apply {
                        text = "WHAT'S NEW"; textSize = 12f; letterSpacing = 0.06f; setTextColor(Ui.TEXT3); setPadding(2.dp, 0, 0, 8.dp)
                    })
                    val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                    notes.take(7).forEachIndexed { i, line ->
                        list.addView(TextView(act).apply {
                            text = "•  $line"; textSize = 14f; setTextColor(Ui.TEXT2); setLineSpacing(0f, 1.15f); setPadding(2.dp, 3.dp, 0, 3.dp)
                            alpha = 0f; translationY = 8f.dp
                            animate().alpha(1f).translationY(0f).setStartDelay(220L + i * 45L).setDuration(260).start()
                        })
                    }
                    addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 18.dp })
                }

                bar.apply {
                    max = 1000; progressDrawable = act.getDrawable(R.drawable.accent_track); visibility = View.GONE
                }
                addView(bar, LinearLayout.LayoutParams(-1, 6.dp).apply { bottomMargin = 8.dp })
                status.apply { textSize = 12.5f; setTextColor(Ui.TEXT2); gravity = Gravity.CENTER; fontFeatureSettings = "tnum"; visibility = View.GONE }
                addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp })

                button.apply {
                    text = "Update now"; textSize = 16f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 24f.dp }
                    minHeight = 50.dp
                    setOnClickListener { Ui.confirm(it); primary() }
                    Ui.pressable(this)
                }
                addView(button, LinearLayout.LayoutParams(-1, -2))
                later.apply {
                    text = "Later"; textSize = 15f; setTextColor(Ui.TEXT2); gravity = Gravity.CENTER; minHeight = 46.dp
                    setOnClickListener { dialog.dismiss() }
                    setOnLongClickListener { LocalStore.setSkippedUpdate(rel.version); toast(act, "Won't ask about ${rel.version} again"); dialog.dismiss(); true }
                }
                addView(later, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dp })
            }
            val width = minOf(act.resources.displayMetrics.widthPixels, 480.dp)
            val scrim = FrameLayout(act).apply {
                setBackgroundColor(0x99000000.toInt())
                setOnClickListener { if (job?.isActive != true) dialog.dismiss() }
                addView(sheet, FrameLayout.LayoutParams(width, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            }
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(scrim)
            dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT) }
            dialog.setOnDismissListener { job?.cancel() }
            dialog.show()
            sheet.translationY = 500f.dp
            sheet.animate().translationY(0f).setDuration((280 * Ui.motion(act)).toLong()).setInterpolator(DecelerateInterpolator(2.2f)).start()
        }

        /** GitHub markdown → plain lines: headings and bullets kept as text, links/markup dropped. */
        private fun cleanNotes(md: String): List<String> = md.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").trim().trimStart('#').trim() }
            .map { it.replace(Regex("""!\[[^]]*]\([^)]*\)"""), "").replace(Regex("""\[([^]]+)]\([^)]*\)"""), "$1").replace("**", "").replace("`", "") }
            .map { it.removePrefix(">").trim() }
            .filter { it.isNotBlank() && !it.startsWith("<") && !it.startsWith("|") }
            // Release-page boilerplate (install steps, signing note, footer) isn't news
            .filterNot { Regex("""^(install|highlights)$|\.apk|signed with|generated with""", RegexOption.IGNORE_CASE).containsMatchIn(it) }

        private fun primary() {
            apk?.let { install(it); return }
            if (job?.isActive == true) return
            download()   // the install permission is asked once the file is ready
        }

        private fun download() {
            button.text = "Downloading…"; button.alpha = 0.6f
            later.text = "Cancel"; later.setOnClickListener { job?.cancel(); dialog.dismiss() }
            bar.visibility = View.VISIBLE; status.visibility = View.VISIBLE
            bar.alpha = 0f; bar.animate().alpha(1f).setDuration(200).start()
            job = scope.launch {
                val file = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = File(act.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                        val out = File(dir, "nova-${rel.version}.apk")
                        RetrofitClient.base.newCall(Request.Builder().url(rel.apkUrl).build()).execute().use { r ->
                            if (!r.isSuccessful) return@runCatching null
                            val body = r.body ?: return@runCatching null
                            val total = body.contentLength().takeIf { it > 0 } ?: rel.size
                            val started = System.currentTimeMillis()
                            var done = 0L; var lastUi = 0L
                            body.byteStream().use { input -> out.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                while (isActive) {
                                    val n = input.read(buf); if (n < 0) break
                                    output.write(buf, 0, n); done += n
                                    val now = System.currentTimeMillis()
                                    if (now - lastUi > 120) {
                                        lastUi = now
                                        val speed = done * 1000.0 / (now - started).coerceAtLeast(1)
                                        val d = done
                                        act.runOnUiThread {
                                            if (total > 0) bar.setProgress((d * 1000 / total).toInt(), true)
                                            status.text = (if (total > 0) "${d * 100 / total}%  ·  ${mb(d)} of ${mb(total)}" else mb(d)) + "  ·  ${mb(speed.toLong())}/s"
                                        }
                                    }
                                }
                            } }
                        }
                        out.takeIf { it.length() > 100_000 }
                    }.getOrNull()
                }
                if (!isActive) return@launch
                if (file == null) {
                    button.text = "Try again"; button.alpha = 1f
                    status.text = "Download failed. Check your connection."
                    bar.visibility = View.GONE
                    return@launch
                }
                apk = file
                bar.setProgress(1000, true)
                status.text = "Ready to install"
                button.alpha = 1f
                button.text = "Install ${rel.version}"
                button.animate().scaleX(1.04f).scaleY(1.04f).setDuration(140).withEndAction {
                    button.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }.start()
                later.text = "Later"; later.setOnClickListener { dialog.dismiss() }
                install(file)
            }
        }

        private fun install(file: File) {
            // Android 8+: the user must allow this app to install updates once
            if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
                status.text = "Allow “Install unknown apps” for Nova, come back and tap Install"
                runCatching { act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))) }
                return
            }
            val uri = FileProvider.getUriForFile(act, "${act.packageName}.updates", file)
            runCatching {
                act.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }.onFailure { toast(act, "Couldn't open the installer") }
        }
    }
}
