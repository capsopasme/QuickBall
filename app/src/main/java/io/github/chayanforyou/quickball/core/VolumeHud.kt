package io.github.chayanforyou.quickball.core

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import io.github.chayanforyou.quickball.R
import io.github.chayanforyou.quickball.ui.floating.VolumePanelView
import io.github.chayanforyou.quickball.utils.DensityUtils
import io.github.chayanforyou.quickball.utils.ToastUtil

/**
 * Owns the volume HUD window: creates it on demand, keeps it in sync with AudioManager while it
 * is visible and removes it (with its receivers) once it has animated away, so nothing stays
 * registered or attached while the HUD is not on screen.
 */
class VolumeHud(private val service: AccessibilityService) : VolumePanelView.Host {

    companion object {
        private const val TAG = "VolumeHud"
        private const val HIDE_DELAY_COLLAPSED_MS = 1800L
        private const val HIDE_DELAY_EXPANDED_MS = 5000L
        private const val GAP_BELOW_STATUS_BAR_DP = 6f

        // Hidden AudioManager broadcasts, sent by the system on every volume / mute change.
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val ACTION_STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
    }

    private val audio = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var view: VolumePanelView? = null
    private var params: WindowManager.LayoutParams? = null
    private var windowExpanded = false
    private var touchActive = false
    private var receiverRegistered = false

    private val hideRunnable = Runnable { view?.hide() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            view?.let { sync(it, animate = true) }
        }
    }

    private val windowManager: WindowManager?
        get() = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    // ------------------------------------------------------------------ actions

    /**
     * Shows the HUD for a volume-key style step the caller has already applied: a new HUD starts
     * from [previous] so the change itself is animated, an existing one springs to the new level.
     */
    fun showStep(stream: Int, previous: Int, up: Boolean, limitReached: Boolean) {
        val existed = view != null
        val hud = obtainView() ?: return
        if (!existed) {
            hud.rows.firstOrNull { it.stream == stream }?.let { hud.setIndex(it, previous, animate = false) }
        }
        sync(hud, animate = true)
        hud.onStepped(stream, up, limitReached)
        scheduleHide()
    }

    /** Opens the HUD directly in its expanded, all-streams form. */
    fun showPanel() {
        val hud = obtainView() ?: return
        hud.expand()
        scheduleHide()
    }

    fun dismiss(immediate: Boolean) {
        handler.removeCallbacks(hideRunnable)
        val hud = view ?: return
        if (immediate) removeWindow() else hud.hide()
    }

    fun destroy() = dismiss(immediate = true)

    // ------------------------------------------------------------------ window

    private fun obtainView(): VolumePanelView? {
        view?.let {
            it.show()
            return it
        }
        val wm = windowManager ?: return null
        ToastUtil.hideNow()

        val hud = VolumePanelView(service, this)
        for (row in hud.rows) {
            val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { audio.getStreamMinVolume(row.stream) }.getOrDefault(0)
            } else 0
            val max = runCatching { audio.getStreamMaxVolume(row.stream) }.getOrDefault(1)
            hud.setRange(row, min, max)
        }
        sync(hud, animate = false)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }
        val p = WindowManager.LayoutParams(
            hud.collapsedWindowWidth,
            hud.collapsedWindowHeight,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (capsuleTop() - hud.marginPx).coerceAtLeast(0)
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            wm.addView(hud, p)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add volume HUD", e)
            return null
        }
        view = hud
        params = p
        windowExpanded = false
        touchActive = false
        registerReceiver()
        hud.show()
        return hud
    }

    /**
     * Just below the status bar in portrait (it is at least as tall as the camera hole, so the
     * capsule never covers the camera), near the top edge in landscape where the status bar is
     * usually hidden and the camera sits on a side edge.
     */
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun capsuleTop(): Int {
        val res = service.resources
        val gap = DensityUtils.dp2px(GAP_BELOW_STATUS_BAR_DP)
        if (res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return gap
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (id > 0) res.getDimensionPixelSize(id) else DensityUtils.dp2px(24f)
        return statusBar + gap
    }

    private fun resizeWindow(expanded: Boolean) {
        val hud = view ?: return
        val p = params ?: return
        if (windowExpanded == expanded) return
        windowExpanded = expanded
        p.width = if (expanded) hud.expandedWindowWidth else hud.collapsedWindowWidth
        p.height = if (expanded) hud.expandedWindowHeight else hud.collapsedWindowHeight
        try {
            windowManager?.updateViewLayout(hud, p)
        } catch (_: Exception) {
        }
    }

    private fun removeWindow() {
        handler.removeCallbacks(hideRunnable)
        unregisterReceiver()
        val hud = view ?: return
        view = null
        params = null
        windowExpanded = false
        touchActive = false
        try {
            windowManager?.removeView(hud)
        } catch (_: Exception) {
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        if (touchActive) return
        val hud = view ?: return
        val delay = if (hud.isExpanded) HIDE_DELAY_EXPANDED_MS else HIDE_DELAY_COLLAPSED_MS
        handler.postDelayed(hideRunnable, delay)
    }

    // ------------------------------------------------------------------ audio

    private fun sync(hud: VolumePanelView, animate: Boolean) {
        for (row in hud.rows) {
            val index = try {
                audio.getStreamVolume(row.stream)
            } catch (_: Exception) {
                continue
            }
            hud.setIndex(row, index, animate)
        }
        hud.ringerMode = audio.ringerMode
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_VOLUME_CHANGED)
            addAction(ACTION_STREAM_MUTE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                service.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register volume receiver", e)
        }
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { service.unregisterReceiver(receiver) }
    }

    private fun cycleRingerMode() {
        val notificationManager =
            service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val canSilence = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                notificationManager.isNotificationPolicyAccessGranted
        val next = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE ->
                if (canSilence) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL

            else -> AudioManager.RINGER_MODE_NORMAL
        }
        try {
            audio.ringerMode = next
        } catch (_: SecurityException) {
            requestDndAccess()
            return
        }
        view?.let { sync(it, animate = true) }
    }

    /** Leaving or entering silent mode toggles Do Not Disturb, which needs policy access. */
    private fun requestDndAccess() {
        dismiss(immediate = true)
        try {
            service.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open DND access settings", e)
        }
        ToastUtil.show(service, service.getString(R.string.volume_need_dnd_access))
    }

    // ------------------------------------------------------------------ VolumePanelView.Host

    override fun onVolumeChosen(stream: Int, index: Int) {
        try {
            audio.setStreamVolume(stream, index, 0)
        } catch (_: SecurityException) {
            requestDndAccess()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume of stream $stream", e)
        }
        // Ring and notification may be linked, and ring 0 switches to vibrate: re-read all.
        view?.let { sync(it, animate = true) }
    }

    override fun onTouchActive(active: Boolean) {
        touchActive = active
        if (active) handler.removeCallbacks(hideRunnable) else scheduleHide()
    }

    override fun onExpandStarted() {
        resizeWindow(expanded = true)
        scheduleHide()
    }

    override fun onCollapseSettled() {
        resizeWindow(expanded = false)
        scheduleHide()
    }

    override fun onHidden() = removeWindow()

    override fun onRingerModeClicked() {
        cycleRingerMode()
        scheduleHide()
    }

    override fun onSystemPanelClicked() {
        dismiss(immediate = false)
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_VOLUME)
            } else {
                Intent(Settings.ACTION_SOUND_SETTINGS)
            }
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open system volume panel", e)
        }
    }
}
