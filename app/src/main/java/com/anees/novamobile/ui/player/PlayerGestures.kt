package com.anees.novamobile.ui.player

import android.app.Activity
import android.media.AudioManager
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.anees.novamobile.R
import com.anees.novamobile.ui.common.dp
import kotlin.math.abs

/**
 * Phone-player touch, YouTube / MX style:
 *  - tap: show / hide controls
 *  - double-tap left / right: −10 / +10s, keep tapping to stack (−20, −30…); double-tap middle: play / pause
 *  - drag left half up/down: brightness · right half: volume (past max: loudness boost)
 *  - drag sideways: seek, with a preview of where you'll land
 *  - hold: 2× while held
 *  - pinch: fit ↔ zoom to fill
 * Touches starting at the screen edges are left to the system (back gesture, status bar).
 */
@UnstableApi
class PlayerGestures(
    private val activity: Activity,
    private val view: PlayerView,
    private val host: Host,
) {
    interface Host {
        val enabled: Boolean            // false while locked
        val swipes: Boolean             // settings: brightness / volume / seek swipes
        fun position(): Long
        fun duration(): Long
        fun seekTo(ms: Long)
        fun togglePlay(): Boolean        // returns playing after the toggle
        fun setTempSpeed(speed: Float?)  // null: back to the chosen speed
        fun boost(): Int
        fun setBoost(level: Int)
        fun onResize(mode: Int)
        fun onLockedTap()
        fun hud(icon: Int, text: String, fraction: Float?)   // fraction null: no bar
        fun hideHud()
        fun seekFlash(forward: Boolean, seconds: Int)
    }

    private enum class Mode { NONE, SEEK, BRIGHTNESS, VOLUME }

    private val audio = activity.getSystemService(AudioManager::class.java)
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
    private val edge = 28.dp
    private var mode = Mode.NONE
    private var ignore = false
    private var startX = 0f
    private var startY = 0f
    private var seekFrom = 0L
    private var seekTo = 0L
    private var brightness = -1f
    private var volumeAcc = 0f           // fractional volume steps carried between moves
    private var holding = false
    private var scaling = false

    // Double-tap streak: after a double tap, single taps on the same side keep adding 10s
    private var streakSide = 0           // -1 left, 1 right, 0 none
    private var streakUntil = 0L
    private var streakSeconds = 0
    private var streakDownTime = -1L

    private val scale = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var total = 1f
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { total = 1f; scaling = true; return host.enabled }
        override fun onScale(d: ScaleGestureDetector): Boolean { total *= d.scaleFactor; return true }
        override fun onScaleEnd(d: ScaleGestureDetector) {
            val zoom = when { total > 1.08f -> true; total < 0.92f -> false; else -> return }
            val m = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            if (view.resizeMode != m) {
                view.resizeMode = m
                host.onResize(m)
                host.hud(R.drawable.ic_aspect, if (zoom) "Zoom to fill" else "Fit to screen", null)
                view.postDelayed({ host.hideHud() }, 900)
            }
        }
    })

    private val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (!host.enabled) return true
            // Taps that continue a double-tap streak
            if (e.downTime != streakDownTime && streakSide != 0 && SystemClock.uptimeMillis() < streakUntil && side(e) == streakSide) {
                streakDownTime = e.downTime
                stackSeek(streakSide)
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!host.enabled) { host.onLockedTap(); return true }
            if (SystemClock.uptimeMillis() < streakUntil) return true    // part of a seek streak
            if (view.isControllerFullyVisible) view.hideController() else view.showController()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!host.enabled) return true
            val s = side(e)
            if (s == 0) {
                val playing = host.togglePlay()
                host.hud(if (playing) R.drawable.ic_play else R.drawable.ic_pause, if (playing) "Play" else "Paused", null)
                view.postDelayed({ host.hideHud() }, 700)
                return true
            }
            if (streakSide == s && SystemClock.uptimeMillis() < streakUntil) return true   // onDown already counted it
            streakSide = s; streakSeconds = 0; streakDownTime = e.downTime
            stackSeek(s)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!host.enabled || mode != Mode.NONE || scaling) return
            holding = true
            host.setTempSpeed(2f)
            host.hud(R.drawable.ic_speed, "2× speed", null)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!host.enabled || !host.swipes || ignore || holding || scaling || e2.pointerCount > 1) return false
            val totalX = e2.x - startX
            val totalY = e2.y - startY
            if (mode == Mode.NONE) {
                if (abs(totalX) < slop * 2 && abs(totalY) < slop * 2) return false
                mode = when {
                    abs(totalX) > abs(totalY) -> Mode.SEEK.also { seekFrom = host.position(); seekTo = seekFrom }
                    startX < view.width / 2f -> Mode.BRIGHTNESS
                    else -> Mode.VOLUME
                }
                view.hideController()
            }
            when (mode) {
                Mode.SEEK -> {
                    val dur = host.duration().takeIf { it > 0 } ?: return true
                    // Full screen width ≈ 90 seconds
                    seekTo = (seekFrom + (totalX / view.width * 90_000).toLong()).coerceIn(0, dur)
                    val diff = seekTo - seekFrom
                    host.hud(if (diff >= 0) R.drawable.ic_forward10 else R.drawable.ic_replay10,
                        "${if (diff >= 0) "+" else "−"}${fmt(abs(diff))}   ${fmt(seekTo)} / ${fmt(dur)}", seekTo.toFloat() / dur)
                }
                Mode.BRIGHTNESS -> {
                    if (brightness < 0) brightness = currentBrightness()
                    brightness = (brightness + dy / (view.height * 0.8f)).coerceIn(0.01f, 1f)
                    activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
                    host.hud(R.drawable.ic_brightness, "${(brightness * 100).toInt()}%", brightness)
                }
                Mode.VOLUME -> {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    volumeAcc += dy / (view.height * 0.8f) * (max + 3)          // 3 extra steps of boost above max
                    while (abs(volumeAcc) >= 1f) {
                        val up = volumeAcc > 0
                        volumeAcc -= if (up) 1f else -1f
                        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val b = host.boost()
                        when {
                            up && cur >= max && b < 3 -> host.setBoost(b + 1)
                            up && cur < max -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, cur + 1, 0)
                            !up && b > 0 -> host.setBoost(b - 1)
                            !up && cur > 0 -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, cur - 1, 0)
                        }
                    }
                    val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val b = host.boost()
                    val label = if (b > 0) "Boost +${listOf(0, 6, 10, 15)[b]} dB" else "${cur * 100 / max}%"
                    host.hud(R.drawable.ic_volume, label, if (b > 0) 1f else cur.toFloat() / max)
                }
                Mode.NONE -> {}
            }
            return true
        }
    })

    fun attach() {
        view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                startX = e.x; startY = e.y; mode = Mode.NONE; volumeAcc = 0f; scaling = false
                // System gesture zones: left/right edges (back), top strip (status bar), bottom strip (home)
                ignore = e.x < edge || e.x > view.width - edge || e.y < edge || e.y > view.height - edge
            }
            scale.onTouchEvent(e)
            detector.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) finish()
            true
        }
    }

    private fun finish() {
        if (holding) { holding = false; host.setTempSpeed(null); host.hideHud() }
        when (mode) {
            Mode.SEEK -> { host.seekTo(seekTo); host.hideHud() }
            Mode.BRIGHTNESS, Mode.VOLUME -> view.postDelayed({ host.hideHud() }, 500)
            Mode.NONE -> {}
        }
        mode = Mode.NONE
    }

    private fun stackSeek(s: Int) {
        streakSeconds += 10
        streakUntil = SystemClock.uptimeMillis() + 700
        val pos = host.position()
        val dur = host.duration().takeIf { it > 0 } ?: Long.MAX_VALUE
        host.seekTo(if (s < 0) (pos - 10_000).coerceAtLeast(0) else (pos + 10_000).coerceAtMost(dur))
        host.seekFlash(s > 0, streakSeconds)
    }

    /** -1 left third, 1 right third, 0 middle. */
    private fun side(e: MotionEvent): Int {
        val third = view.width / 3f
        return when { e.x < third -> -1; e.x > third * 2 -> 1; else -> 0 }
    }

    private fun currentBrightness(): Float {
        val w = activity.window.attributes.screenBrightness
        if (w >= 0) return w
        return runCatching { android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
