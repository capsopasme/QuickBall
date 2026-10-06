package io.github.chayanforyou.quickball.domain.handlers

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.net.toUri
import io.github.chayanforyou.quickball.core.RecentAppTracker
import io.github.chayanforyou.quickball.core.VolumeHud
import io.github.chayanforyou.quickball.domain.AppPreference
import io.github.chayanforyou.quickball.domain.models.MenuAction
import io.github.chayanforyou.quickball.domain.models.QuickBallMenuItem
import io.github.chayanforyou.quickball.R
import io.github.chayanforyou.quickball.localsend.ClipboardSendActivity
import io.github.chayanforyou.quickball.localsend.LocalSendSender
import io.github.chayanforyou.quickball.utils.BrightnessUtils
import io.github.chayanforyou.quickball.utils.ToastUtil
import io.github.chayanforyou.quickball.utils.performHapticFeedback
import kotlin.math.roundToInt

class QuickBallActionHandler(
    private val accessibilityService: AccessibilityService,
    private val recentAppTracker: RecentAppTracker? = null,
    private val performStash: (() -> Unit)? = null,
    private val startPartialScreenshot: (() -> Unit)? = null,
) {

    companion object {
        private const val TAG = "QuickBallActionHandler"
        private const val BRIGHTNESS_STEP_PERCENT = 10
        private const val VOLUME_STEP_PERCENT = 10
        private const val ASSISTANT_PACKAGE = "com.capsopasme.assistant"
        private const val ASSISTANT_ACTION_START = "com.capsopasme.assistant.START"
    }

    private val context: Context = accessibilityService.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var isTorchOn = false
    // Flash-capable camera id; found once instead of querying every camera's characteristics
    // (one binder round trip each, and the SM8650 exposes many logical/physical cameras).
    private var torchCameraId: String? = null

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val torchCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                isTorchOn = enabled
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                isTorchOn = false
            }
        }
    } else null

    private val localSendNotifier: (String) -> Unit = { message -> showToast(message) }

    private val volumeHud = VolumeHud(accessibilityService)

    init {
        initTorch()
        LocalSendSender.notifier = localSendNotifier
    }

    private fun initTorch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && torchCallback != null) {
            try {
                cameraManager.registerTorchCallback(torchCallback, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register torch callback", e)
            }
        }
    }

    /** Removes the volume HUD and any toast at once, e.g. right before a screenshot. */
    fun dismissTransientUi() {
        volumeHud.dismiss(immediate = true)
        ToastUtil.hideNow()
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        volumeHud.onConfigurationChanged(newConfig)
    }

    fun cleanup() {
        volumeHud.destroy()
        if (LocalSendSender.notifier === localSendNotifier) LocalSendSender.notifier = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && torchCallback != null) {
            try {
                cameraManager.unregisterTorchCallback(torchCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister torch callback", e)
            }
        }
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private inline fun runDelayed(
        delayMillis: Long = 200L,
        crossinline action: () -> Unit
    ) {
        handler.postDelayed({
            try {
                action()
            } catch (e: Exception) {
                Log.e(TAG, "Error running delayed action", e)
            }
        }, delayMillis)
    }

    private fun showToast(message: String, performHaptic: Boolean = false) {
        // Both sit at the top of the screen; the newer message wins.
        volumeHud.dismiss(immediate = true)
        if (performHaptic) {
            runDelayed { context.performHapticFeedback() }
        }
        ToastUtil.show(accessibilityService, message)
    }

    private fun canWriteSettings() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.System.canWrite(context)

    private fun requestSystemSettingsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = "package:${context.packageName}".toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            showToast("Allow 'Modify system settings' permission", performHaptic = true)
        } catch (_: Exception) {
            showToast("Could not request system settings permission")
        }
    }

    fun onMenuAction(menuItem: QuickBallMenuItem) {
        when (menuItem.action) {
            MenuAction.VOLUME_UP -> performVolumeUpAction()
            MenuAction.VOLUME_DOWN -> performVolumeDownAction()
            MenuAction.BRIGHTNESS_UP -> changeBrightness(increase = true)
            MenuAction.BRIGHTNESS_DOWN -> changeBrightness(increase = false)
            MenuAction.LOCK_SCREEN -> performLockScreenAction()
            MenuAction.SCREENSHOT -> performScreenshotAction()
            MenuAction.PARTIAL_SCREENSHOT -> startPartialScreenshot?.invoke()
            MenuAction.WIFI_TOGGLE -> toggleWifi()
            MenuAction.BLUETOOTH_TOGGLE -> toggleBluetooth()
            MenuAction.MOBILE_DATA_TOGGLE -> toggleMobileData()
            MenuAction.DND_TOGGLE -> toggleDndMode()
            MenuAction.VIBRATE_TOGGLE -> toggleVibrateMode()
            MenuAction.MEDIA_PLAY_PAUSE -> mediaPlayPause()
            MenuAction.MEDIA_NEXT -> mediaNext()
            MenuAction.MEDIA_PREVIOUS -> mediaPrevious()
            MenuAction.VOLUME_BAR -> showVolume()
            MenuAction.VOLUME_PANEL -> openVolumePanel()
            MenuAction.VOLUME_MIXER -> volumeHud.showPanel()
            MenuAction.TORCH_TOGGLE -> toggleTorch()
            MenuAction.AUTO_ROTATE_TOGGLE -> toggleAutoRotate()
            MenuAction.AIRPLANE_MODE_TOGGLE -> toggleAirplaneMode()
            MenuAction.HOME -> performHomeAction()
            MenuAction.BACK -> performBackAction()
            MenuAction.RECENT -> performMenuAction()
            MenuAction.SWITCH_LAST_APP -> switchToLastApp()
            MenuAction.SEND_CLIPBOARD_LOCALSEND -> sendClipboardToLocalSend()
            MenuAction.PHONE_ASSISTANT -> launchPhoneAssistant()
            MenuAction.NOTIFICATION -> performNotificationAction()
            MenuAction.QUICK_SETTINGS -> performQuickSettingsAction()
            MenuAction.POWER_DIALOG -> performPowerDialogAction()
            MenuAction.LAUNCH_APP -> launchApp(menuItem.packageName)
        }
    }

    // -------------------- Navigation Actions --------------------
    private fun performHomeAction() {
        accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    private fun performBackAction() {
        accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun performMenuAction() {
        accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    private fun performNotificationAction() {
        accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    }

    private fun performQuickSettingsAction() {
        accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    }

    private fun performPowerDialogAction() {
        accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
    }

    // -------------------- Volume Actions --------------------
    private fun performVolumeUpAction() = stepVolume(up = true)

    private fun performVolumeDownAction() = stepVolume(up = false)

    /**
     * Moves media volume to the next 10 % mark of the stream's range. Volume is an integer
     * index (often 0–15), so the 10 % marks are rounded to indices and the step goes to the
     * nearest mark strictly above/below the current index, which never stalls.
     */
    private fun stepVolume(up: Boolean) {
        try {
            val stream = AudioManager.STREAM_MUSIC
            val max = audioManager.getStreamMaxVolume(stream)
            val current = audioManager.getStreamVolume(stream)
            val marks = (0..100 step VOLUME_STEP_PERCENT).map { (it * max / 100.0).roundToInt() }
            val target = if (up) {
                marks.firstOrNull { it > current } ?: max
            } else {
                marks.lastOrNull { it < current } ?: 0
            }
            if (target != current) {
                audioManager.setStreamVolume(stream, target, AudioManager.FLAG_PLAY_SOUND)
            }
            if (AppPreference.getInstance(context).isIosVolumeHud) {
                volumeHud.showStep(
                    stream = stream,
                    previous = current,
                    up = up,
                    limitReached = target == current
                )
            } else {
                showVolumeToast()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to change volume (up=$up)", e)
        }
    }

    private fun showVolumeToast() {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        ToastUtil.showVolumeToast(
            context = accessibilityService,
            currentVolume = currentVolume,
            maxVolume = maxVolume,
            onVolumeChanged = { newVol ->
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set volume via slider", e)
                }
            }
        )
    }

    // -------------------- Media Controls --------------------
    private fun sendMediaKeyEvent(keyCode: Int) {
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    private fun mediaPlayPause() {
        sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    private fun mediaNext() {
        sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    private fun mediaPrevious() {
        sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    private fun openVolumePanel() {
        runDelayed {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startActivity(Intent(Settings.Panel.ACTION_VOLUME).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } else {
                context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    private fun showVolume() {
        performStash?.invoke()
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_SAME,
            AudioManager.FLAG_SHOW_UI
        )
    }

    // -------------------- Brightness --------------------
    private fun changeBrightness(increase: Boolean) {
        if (!canWriteSettings()) {
            requestSystemSettingsPermission()
            return
        }

        val current = getCurrentBrightness()
        val currentPercent = (BrightnessUtils.linearToPercent(current) + 5) / 10 * 10
        val delta = if (increase) BRIGHTNESS_STEP_PERCENT else -BRIGHTNESS_STEP_PERCENT
        val newPercent = (currentPercent + delta).coerceIn(0, 100)

        val newBrightness = BrightnessUtils.percentToLinear(newPercent)
        setBrightness(newBrightness, newPercent)
    }

    private fun getCurrentBrightness(): Int {
        return try {
            Settings.System.getInt(
                accessibilityService.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
        } catch (_: Settings.SettingNotFoundException) {
            BrightnessUtils.MAX_BRIGHTNESS / 2 // Default to middle brightness
        }
    }

    private fun setBrightness(brightness: Int, percent: Int) {
        try {
            Settings.System.putInt(
                accessibilityService.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )
            showBrightnessToast(percent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness", e)
        }
    }

    private fun showBrightnessToast(percent: Int) {
        volumeHud.dismiss(immediate = true)
        ToastUtil.showBrightnessToast(
            context = accessibilityService,
            percent = percent,
            onBrightnessChanged = { brightness ->
                if (canWriteSettings()) {
                    try {
                        Settings.System.putInt(
                            accessibilityService.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            brightness
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set brightness via slider", e)
                    }
                } else {
                    requestSystemSettingsPermission()
                }
            }
        )
    }

    // -------------------- Do Not Disturb (DND) Mode --------------------
    private fun toggleDndMode() {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !notificationManager.isNotificationPolicyAccessGranted
        ) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runDelayed { context.startActivity(intent) }
            showToast("Grant Do Not Disturb access")
            return
        }

        val newMode = when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_SILENT
            AudioManager.RINGER_MODE_SILENT -> AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_NORMAL
        }

        runDelayed {
            try {
                audioManager.ringerMode = newMode
                showToast(getDndModeText(newMode))
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to toggle DND mode", e)
            }
        }
    }

    private fun getDndModeText(mode: Int): String {
        return when (mode) {
            AudioManager.RINGER_MODE_SILENT -> "Do Not Disturb ON"
            AudioManager.RINGER_MODE_NORMAL -> "Do Not Disturb OFF"
            else -> "Do Not Disturb OFF"
        }
    }

    // -------------------- Vibration Mode --------------------
    private fun toggleVibrateMode() {
        val newMode = when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_NORMAL
        }

        runDelayed {
            try {
                audioManager.ringerMode = newMode
                showToast(getVibrationModeText(newMode))
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to toggle vibrate mode", e)
            }
        }
    }

    private fun getVibrationModeText(mode: Int): String {
        return when (mode) {
            AudioManager.RINGER_MODE_VIBRATE -> "Vibration mode ON"
            AudioManager.RINGER_MODE_NORMAL -> "Vibration mode OFF"
            else -> "Vibration mode OFF"
        }
    }

    // -------------------- Torch --------------------
    private fun toggleTorch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            showToast("Torch is not supported on this device.", performHaptic = true)
            return
        }

        try {
            val cameraId = torchCameraId ?: cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }?.also { torchCameraId = it } ?: return

            val newState = !isTorchOn
            cameraManager.setTorchMode(cameraId, newState)
            showToast(if (newState) "Torch ON" else "Torch OFF")
        } catch (e: Exception) {
            Log.e(TAG, "Torch toggle failed", e)
        }
    }

    // -------------------- Connectivity --------------------
    private fun toggleWifi() {
        runDelayed {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startActivity(Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } else {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = !wifiManager.isWifiEnabled
            }
        }
    }

    private fun toggleBluetooth() {
        runDelayed {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } else {
                @Suppress("DEPRECATION", "MissingPermission")
                BluetoothAdapter.getDefaultAdapter()?.let { adapter ->
                    if (adapter.isEnabled) adapter.disable() else adapter.enable()
                }
            }
        }
    }

    private fun toggleMobileData() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runDelayed {
                context.startActivity(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        } else {
            showToast("Mobile data toggle not supported on this device", performHaptic = true)
        }
    }

    // -------------------- Screenshot --------------------
    private fun performScreenshotAction() {
        performStash?.invoke()
        runDelayed {
            takeScreenshot()
        }
    }

    private fun takeScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            showToast("Screenshot is not supported on this device.", performHaptic = true)
        }
    }

    // -------------------- Lock Screen --------------------
    private fun performLockScreenAction() {
        runDelayed {
            lockScreen()
        }
    }

    private fun lockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            showToast("Lock screen not supported on this device.", performHaptic = true)
        }
    }

    // -------------------- Auto Rotate --------------------
    private fun toggleAutoRotate() {
        if (!canWriteSettings()) {
            requestSystemSettingsPermission()
            return
        }

        val current = Settings.System.getInt(
            accessibilityService.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION, 0
        )
        val newValue = if (current == 1) 0 else 1
        Settings.System.putInt(
            accessibilityService.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            newValue
        )
        showToast(if (newValue == 1) "Auto-rotate ON" else "Auto-rotate OFF")
    }

    // -------------------- Airplane Mode --------------------
    private fun toggleAirplaneMode() {
        runDelayed {
            context.startActivity(
                Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    // -------------------- Switch To Last App --------------------
    /**
     * Brings the previously used app back to the front. Uses the launcher-style intent
     * (NEW_TASK | RESET_TASK_IF_NEEDED), which resumes the existing task instead of
     * starting a fresh one, so repeated use toggles between the last two apps.
     */
    private fun switchToLastApp() {
        val target = recentAppTracker?.previousPackage
        if (target.isNullOrBlank()) {
            showToast(context.getString(R.string.toast_no_previous_app))
            return
        }
        try {
            val intent = accessibilityService.packageManager.getLaunchIntentForPackage(target)
            if (intent == null) {
                showToast(context.getString(R.string.toast_no_previous_app))
                return
            }
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            accessibilityService.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch to $target", e)
        }
    }

    // -------------------- Send Clipboard To LocalSend --------------------
    /**
     * Reading the clipboard from the background is blocked since Android 10 unless the caller
     * owns the focused window, so a transparent activity takes focus for a moment, reads the
     * primary clip and hands it to [io.github.chayanforyou.quickball.localsend.LocalSendClient].
     */
    private fun sendClipboardToLocalSend() {
        try {
            accessibilityService.startActivity(
                Intent(context, ClipboardSendActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start clipboard reader", e)
        }
    }

    // -------------------- Phone Assistant --------------------
    /**
     * Opens the assistant sheet of PhoneAssistant (com.capsopasme.assistant) through its public
     * START action; if that app is missing, falls back to whatever is set as the system's default
     * digital assistant (ACTION_ASSIST), the same thing the corner swipe opens.
     */
    private fun launchPhoneAssistant() {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        val candidates = listOf(
            Intent(ASSISTANT_ACTION_START).setPackage(ASSISTANT_PACKAGE),
            Intent(Intent.ACTION_ASSIST)
        )
        for (intent in candidates) {
            try {
                accessibilityService.startActivity(intent.addFlags(flags))
                return
            } catch (_: ActivityNotFoundException) {
                // try the next one
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open assistant via ${intent.action}", e)
                return
            }
        }
        showToast(context.getString(R.string.toast_assistant_not_found))
    }

    // -------------------- App Launch --------------------
    /**
     * Opens an app straight away (gesture / wave bindings): there is no menu to collapse first,
     * so no delay. RESET_TASK_IF_NEEDED resumes the app's existing task like the launcher does.
     */
    fun launchAppNow(packageName: String) {
        try {
            val intent = accessibilityService.packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                showToast(context.getString(R.string.toast_app_not_found))
                return
            }
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            accessibilityService.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app: $packageName", e)
        }
    }

    private fun launchApp(packageName: String?) {
        if (packageName.isNullOrBlank()) {
            Log.w(TAG, "Cannot launch app - package name is null or empty")
            return
        }

        try {
            accessibilityService.packageManager
                .getLaunchIntentForPackage(packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                ?.let { intent ->
                    performStash?.invoke()
                    runDelayed { accessibilityService.startActivity(intent) }
                } ?: showToast("App not found or cannot be launched")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app: $packageName", e)
        }
    }
}