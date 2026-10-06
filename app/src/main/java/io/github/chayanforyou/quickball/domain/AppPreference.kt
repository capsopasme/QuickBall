package io.github.chayanforyou.quickball.domain

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import io.github.chayanforyou.quickball.domain.models.MenuAction
import io.github.chayanforyou.quickball.domain.models.QuickBallMenuItem
import io.github.chayanforyou.quickball.localsend.LocalSendTarget

class AppPreference private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "quick_ball_prefs"
        private const val KEY_QUICK_BALL_ENABLED = "quick_ball_enabled"
        private const val KEY_BALL_SIZE = "ball_size"
        private const val KEY_BALL_COLOR = "ball_color"
        private const val KEY_BALL_ICON_COLOR = "ball_icon_color"
        private const val KEY_MENU_COLOR = "menu_color"
        private const val KEY_MENU_ICON_COLOR = "menu_icon_color"
        private const val KEY_MENU_SIZE = "menu_size"
        private const val KEY_MENU_ICON_SIZE = "menu_icon_size"
        private const val KEY_MENU_RADIUS = "menu_radius"
        private const val KEY_TOAST_BG_COLOR = "toast_bg_color"
        private const val KEY_TOAST_FG_COLOR = "toast_fg_color"
        private const val KEY_PILL_COLOR = "pill_color"
        private const val KEY_PILL_HEIGHT = "pill_height"
        private const val KEY_PILL_THICKNESS = "pill_thickness"
        private const val KEY_PILL_TOUCH_WIDTH = "pill_touch_width"
        private const val KEY_PILL_ARC_ANGLE = "pill_arc_angle"
        private const val KEY_GESTURE_ENABLED = "pill_gesture_enabled"
        private const val KEY_LONG_PRESS = "pill_long_press"
        private const val KEY_SWIPE_UP = "pill_swipe_up"
        private const val KEY_SWIPE_DOWN = "pill_swipe_down"
        private const val KEY_STICK_TO_EDGE = "stick_to_edge"
        private const val KEY_LOCK_BALL_POSITION = "lock_ball_position"
        private const val KEY_SHOW_ON_LOCK_SCREEN = "show_on_lock_screen"
        private const val KEY_HIDE_ON_LANDSCAPE = "hide_on_landscape"
        private const val KEY_SELECTED_MENU_ITEMS = "selected_menu_items"
        private const val KEY_SELECTED_APPS = "selected_apps"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PORTRAIT_IS_ON_RIGHT = "portrait_is_on_right"
        private const val KEY_PORTRAIT_Y_FRACTION = "portrait_y_fraction"
        private const val KEY_LANDSCAPE_IS_ON_RIGHT = "landscape_is_on_right"
        private const val KEY_LANDSCAPE_Y_FRACTION = "landscape_y_fraction"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_HAPTIC_FEEDBACK_ENABLED = "haptic_feedback_enabled"
        private const val KEY_HAPTIC_INTENSITY = "haptic_intensity"
        private const val KEY_WAVE_ENABLED = "wave_enabled"
        private const val KEY_WAVE_ON_RIGHT = "wave_on_right"
        private const val KEY_WAVE_Y_FRACTION = "wave_y_fraction"
        private const val KEY_WAVE_HEIGHT = "wave_height"
        private const val KEY_WAVE_TOUCH_WIDTH = "wave_touch_width"
        private const val KEY_WAVE_THICKNESS = "wave_thickness"
        private const val KEY_WAVE_COLOR = "wave_color"
        private const val KEY_WAVE_SWIPE_UP = "wave_swipe_up"
        private const val KEY_WAVE_SWIPE_DOWN = "wave_swipe_down"
        private const val KEY_IOS_VOLUME_HUD = "ios_volume_hud"
        private const val KEY_LOCALSEND_HOST = "localsend_host"
        private const val KEY_LOCALSEND_PORT = "localsend_port"
        private const val KEY_LOCALSEND_HTTPS = "localsend_https"
        private const val KEY_LOCALSEND_FINGERPRINT = "localsend_fingerprint"
        private const val KEY_LOCALSEND_PIN = "localsend_pin"
        private const val KEY_LOCALSEND_ALIAS = "localsend_alias"

        private val gson = Gson()

        @Volatile
        private var INSTANCE: AppPreference? = null

        fun getInstance(context: Context): AppPreference {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppPreference(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    var isOnboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDING_COMPLETED, value) }

    var isQuickBallEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUICK_BALL_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_QUICK_BALL_ENABLED, value) }

    var ballSize: Float
        get() = prefs.getFloat(KEY_BALL_SIZE, AppDefaults.BALL_SIZE)
        set(value) = prefs.edit { putFloat(KEY_BALL_SIZE, value) }

    var ballColor: Int
        get() = prefs.getInt(KEY_BALL_COLOR, AppDefaults.BALL_COLOR)
        set(value) = prefs.edit { putInt(KEY_BALL_COLOR, value) }

    var ballIconColor: Int
        get() = prefs.getInt(KEY_BALL_ICON_COLOR, AppDefaults.BALL_ICON_COLOR)
        set(value) = prefs.edit { putInt(KEY_BALL_ICON_COLOR, value) }

    var menuColor: Int
        get() = prefs.getInt(KEY_MENU_COLOR, AppDefaults.MENU_COLOR)
        set(value) = prefs.edit { putInt(KEY_MENU_COLOR, value) }

    var menuIconColor: Int
        get() = prefs.getInt(KEY_MENU_ICON_COLOR, AppDefaults.MENU_ICON_COLOR)
        set(value) = prefs.edit { putInt(KEY_MENU_ICON_COLOR, value) }

    var menuIconSize: Float
        get() = prefs.getFloat(KEY_MENU_ICON_SIZE, AppDefaults.MENU_ICON_SIZE)
        set(value) = prefs.edit { putFloat(KEY_MENU_ICON_SIZE, value) }

    var menuSize: Float
        get() = prefs.getFloat(KEY_MENU_SIZE, AppDefaults.MENU_SIZE)
        set(value) = prefs.edit { putFloat(KEY_MENU_SIZE, value) }

    var menuRadius: Float
        get() = prefs.getFloat(KEY_MENU_RADIUS, AppDefaults.MENU_RADIUS)
        set(value) = prefs.edit { putFloat(KEY_MENU_RADIUS, value) }

    var toastBgColor: Int
        get() = prefs.getInt(KEY_TOAST_BG_COLOR, AppDefaults.TOAST_BG_COLOR)
        set(value) = prefs.edit { putInt(KEY_TOAST_BG_COLOR, value) }

    var toastFgColor: Int
        get() = prefs.getInt(KEY_TOAST_FG_COLOR, AppDefaults.TOAST_FG_COLOR)
        set(value) = prefs.edit { putInt(KEY_TOAST_FG_COLOR, value) }

    var pillColor: Int
        get() = prefs.getInt(KEY_PILL_COLOR, AppDefaults.PILL_COLOR)
        set(value) = prefs.edit { putInt(KEY_PILL_COLOR, value) }

    var pillHeight: Float
        get() = prefs.getFloat(KEY_PILL_HEIGHT, AppDefaults.PILL_HEIGHT)
        set(value) = prefs.edit { putFloat(KEY_PILL_HEIGHT, value) }

    var pillThickness: Float
        get() = prefs.getFloat(KEY_PILL_THICKNESS, AppDefaults.PILL_THICKNESS)
        set(value) = prefs.edit { putFloat(KEY_PILL_THICKNESS, value) }

    var pillTouchWidth: Float
        get() = prefs.getFloat(KEY_PILL_TOUCH_WIDTH, AppDefaults.PILL_TOUCH_WIDTH)
        set(value) = prefs.edit { putFloat(KEY_PILL_TOUCH_WIDTH, value) }

    var pillArcAngle: Float
        get() = prefs.getFloat(KEY_PILL_ARC_ANGLE, AppDefaults.PILL_ARC_ANGLE)
        set(value) = prefs.edit { putFloat(KEY_PILL_ARC_ANGLE, value) }

    var isGestureEnabled: Boolean
        get() = prefs.getBoolean(KEY_GESTURE_ENABLED, AppDefaults.GESTURE_ENABLED)
        set(value) = prefs.edit { putBoolean(KEY_GESTURE_ENABLED, value) }

    var longPressAction: String
        get() = prefs.getString(KEY_LONG_PRESS, AppDefaults.LONG_PRESS_ACTION) ?: AppDefaults.LONG_PRESS_ACTION
        set(value) = prefs.edit { putString(KEY_LONG_PRESS, value) }

    var swipeUpAction: String
        get() = prefs.getString(KEY_SWIPE_UP, AppDefaults.SWIPE_UP_ACTION) ?: AppDefaults.SWIPE_UP_ACTION
        set(value) = prefs.edit { putString(KEY_SWIPE_UP, value) }

    var swipeDownAction: String
        get() = prefs.getString(KEY_SWIPE_DOWN, AppDefaults.SWIPE_DOWN_ACTION) ?: AppDefaults.SWIPE_DOWN_ACTION
        set(value) = prefs.edit { putString(KEY_SWIPE_DOWN, value) }

    var isHapticFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_FEEDBACK_ENABLED, AppDefaults.HAPTIC_FEEDBACK_ENABLED)
        set(value) = prefs.edit { putBoolean(KEY_HAPTIC_FEEDBACK_ENABLED, value) }

    var hapticIntensity: String
        get() = prefs.getString(KEY_HAPTIC_INTENSITY, AppDefaults.HAPTIC_INTENSITY) ?: AppDefaults.HAPTIC_INTENSITY
        set(value) = prefs.edit { putString(KEY_HAPTIC_INTENSITY, value) }

    /** iOS-style animated volume capsule instead of the plain volume toast. */
    var isIosVolumeHud: Boolean
        get() = prefs.getBoolean(KEY_IOS_VOLUME_HUD, AppDefaults.IOS_VOLUME_HUD)
        set(value) = prefs.edit { putBoolean(KEY_IOS_VOLUME_HUD, value) }

    // -------------------- Wave edge bar --------------------
    var isWaveEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAVE_ENABLED, AppDefaults.WAVE_ENABLED)
        set(value) = prefs.edit { putBoolean(KEY_WAVE_ENABLED, value) }

    var waveOnRight: Boolean
        get() = prefs.getBoolean(KEY_WAVE_ON_RIGHT, AppDefaults.WAVE_ON_RIGHT)
        set(value) = prefs.edit { putBoolean(KEY_WAVE_ON_RIGHT, value) }

    /** Vertical centre of the bar as a fraction of the screen height. */
    var waveYFraction: Float
        get() = prefs.getFloat(KEY_WAVE_Y_FRACTION, AppDefaults.WAVE_Y_FRACTION)
        set(value) = prefs.edit { putFloat(KEY_WAVE_Y_FRACTION, value) }

    var waveHeight: Float
        get() = prefs.getFloat(KEY_WAVE_HEIGHT, AppDefaults.WAVE_HEIGHT)
            .coerceIn(AppDefaults.WAVE_HEIGHT_MIN, AppDefaults.WAVE_HEIGHT_MAX)
        set(value) = prefs.edit { putFloat(KEY_WAVE_HEIGHT, value) }

    var waveTouchWidth: Float
        get() = prefs.getFloat(KEY_WAVE_TOUCH_WIDTH, AppDefaults.WAVE_TOUCH_WIDTH)
        set(value) = prefs.edit { putFloat(KEY_WAVE_TOUCH_WIDTH, value) }

    var waveThickness: Float
        get() = prefs.getFloat(KEY_WAVE_THICKNESS, AppDefaults.WAVE_THICKNESS)
        set(value) = prefs.edit { putFloat(KEY_WAVE_THICKNESS, value) }

    var waveColor: Int
        get() = prefs.getInt(KEY_WAVE_COLOR, AppDefaults.WAVE_COLOR)
        set(value) = prefs.edit { putInt(KEY_WAVE_COLOR, value) }

    var waveSwipeUpAction: String
        get() = prefs.getString(KEY_WAVE_SWIPE_UP, AppDefaults.WAVE_SWIPE_UP_ACTION) ?: AppDefaults.WAVE_SWIPE_UP_ACTION
        set(value) = prefs.edit { putString(KEY_WAVE_SWIPE_UP, value) }

    var waveSwipeDownAction: String
        get() = prefs.getString(KEY_WAVE_SWIPE_DOWN, AppDefaults.WAVE_SWIPE_DOWN_ACTION) ?: AppDefaults.WAVE_SWIPE_DOWN_ACTION
        set(value) = prefs.edit { putString(KEY_WAVE_SWIPE_DOWN, value) }

    var localSendTarget: LocalSendTarget
        get() = LocalSendTarget(
            host = prefs.getString(KEY_LOCALSEND_HOST, "") ?: "",
            port = prefs.getInt(KEY_LOCALSEND_PORT, LocalSendTarget.DEFAULT_PORT),
            https = prefs.getBoolean(KEY_LOCALSEND_HTTPS, true),
            fingerprint = prefs.getString(KEY_LOCALSEND_FINGERPRINT, "") ?: "",
            pin = prefs.getString(KEY_LOCALSEND_PIN, "") ?: "",
            alias = prefs.getString(KEY_LOCALSEND_ALIAS, "") ?: ""
        )
        set(value) = prefs.edit {
            putString(KEY_LOCALSEND_HOST, value.host.trim())
            putInt(KEY_LOCALSEND_PORT, value.port)
            putBoolean(KEY_LOCALSEND_HTTPS, value.https)
            putString(KEY_LOCALSEND_FINGERPRINT, value.fingerprint.trim())
            putString(KEY_LOCALSEND_PIN, value.pin)
            putString(KEY_LOCALSEND_ALIAS, value.alias.trim())
        }

    var isStickToEdgeEnabled: Boolean
        get() = prefs.getBoolean(KEY_STICK_TO_EDGE, true)
        set(value) = prefs.edit { putBoolean(KEY_STICK_TO_EDGE, value) }

    var isLockBallPositionEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_BALL_POSITION, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCK_BALL_POSITION, value) }

    var isShowOnLockScreenEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ON_LOCK_SCREEN, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_ON_LOCK_SCREEN, value) }

    var isHideOnLandscapeEnabled: Boolean
        get() = prefs.getBoolean(KEY_HIDE_ON_LANDSCAPE, false)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_ON_LANDSCAPE, value) }

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "en") ?: "en"
        set(value) = prefs.edit { putString(KEY_LANGUAGE, value) }

    var autoHideApps: Set<String>
        get() = prefs.getStringSet(KEY_SELECTED_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_SELECTED_APPS, value) }

    var selectedMenuItems: List<QuickBallMenuItem>
        get() {
            val json = prefs.getString(KEY_SELECTED_MENU_ITEMS, null)
            if (json.isNullOrEmpty()) return getDefaultSelectedItems()

            return try {
                val validItems = QuickBallMenuItem.parseStoredList(json)
                if (validItems.size >= 2) validItems else getDefaultSelectedItems()
            } catch (_: Exception) {
                // Only unreadable JSON is dropped; unknown entries are skipped by the parser.
                prefs.edit { remove(KEY_SELECTED_MENU_ITEMS) }
                getDefaultSelectedItems()
            }
        }
        set(value) {
            try {
                val json = gson.toJson(value)
                prefs.edit { putString(KEY_SELECTED_MENU_ITEMS, json) }
            } catch (e: Exception) {
                Log.e("PreferenceManager", "Failed to save menu items", e)
            }
        }

    var portraitIsOnRight: Boolean
        get() = prefs.getBoolean(KEY_PORTRAIT_IS_ON_RIGHT, true)
        set(value) = prefs.edit { putBoolean(KEY_PORTRAIT_IS_ON_RIGHT, value) }

    var portraitYFraction: Float
        get() = prefs.getFloat(KEY_PORTRAIT_Y_FRACTION, 0.5f)
        set(value) = prefs.edit { putFloat(KEY_PORTRAIT_Y_FRACTION, value) }

    fun savePortraitPosition(isOnRight: Boolean, yFraction: Float) {
        prefs.edit {
            putBoolean(KEY_PORTRAIT_IS_ON_RIGHT, isOnRight)
            putFloat(KEY_PORTRAIT_Y_FRACTION, yFraction)
        }
    }

    var landscapeIsOnRight: Boolean
        get() = prefs.getBoolean(KEY_LANDSCAPE_IS_ON_RIGHT, true)
        set(value) = prefs.edit { putBoolean(KEY_LANDSCAPE_IS_ON_RIGHT, value) }

    var landscapeYFraction: Float
        get() = prefs.getFloat(KEY_LANDSCAPE_Y_FRACTION, 0.5f)
        set(value) = prefs.edit { putFloat(KEY_LANDSCAPE_Y_FRACTION, value) }

    fun saveLandscapePosition(isOnRight: Boolean, yFraction: Float) {
        prefs.edit {
            putBoolean(KEY_LANDSCAPE_IS_ON_RIGHT, isOnRight)
            putFloat(KEY_LANDSCAPE_Y_FRACTION, yFraction)
        }
    }

    private fun getDefaultSelectedItems(): List<QuickBallMenuItem> {
        val defaultActions = listOf(
            MenuAction.VOLUME_UP,
            MenuAction.VOLUME_DOWN,
            MenuAction.BRIGHTNESS_UP,
            MenuAction.BRIGHTNESS_DOWN,
            MenuAction.LOCK_SCREEN
        )

        return defaultActions.mapNotNull { action ->
            QuickBallMenuItem.getMenuItemByAction(action)
        }
    }
}