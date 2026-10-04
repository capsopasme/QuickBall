package io.github.chayanforyou.quickball.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Remembers the last two foreground apps so the ball can toggle between them, and tells the
 * service whether a window event really came from an app screen.
 *
 * Only TYPE_WINDOW_STATE_CHANGED events whose class is a real Activity count, which filters
 * out dialogs, popups, toasts, the notification shade and the input method's window. The
 * launcher, the current input method, SystemUI and QuickBall itself are ignored for the
 * switch target, so "home → other app" still leaves the previous app as the switch target.
 */
class RecentAppTracker(private val context: Context) {

    companion object {
        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.intentresolver",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
        private const val MAX_CACHE = 256
    }

    private val pm: PackageManager = context.packageManager

    /** Package currently in front, as far as we know. */
    var currentPackage: String? = null
        private set

    /** Package that was in front before [currentPackage]; the switch target. */
    var previousPackage: String? = null
        private set

    private val activityCache = HashMap<String, Boolean>()
    private val homePackages: Set<String> by lazy { resolveHomePackages() }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (pkg == currentPackage || isIgnored(pkg) || !isActivity(pkg, cls)) return

        previousPackage = currentPackage
        currentPackage = pkg
    }

    /**
     * True when [event] is an activity window of an app (launcher included) coming to the
     * front, false for the input method, popups, dialogs, overlays and other non-activity
     * windows that must not change the foreground app used for auto-hide.
     */
    fun isAppScreen(event: AccessibilityEvent): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        val pkg = event.packageName?.toString() ?: return false
        val cls = event.className?.toString() ?: return false
        return pkg != currentImePackage() && isActivity(pkg, cls)
    }

    private fun isIgnored(pkg: String): Boolean {
        return pkg == context.packageName ||
                pkg in IGNORED_PACKAGES ||
                pkg in homePackages ||
                pkg == currentImePackage()
    }

    private fun isActivity(pkg: String, cls: String): Boolean {
        val key = "$pkg/$cls"
        activityCache[key]?.let { return it }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.MATCH_DISABLED_COMPONENTS
        } else {
            0
        }
        val result = try {
            pm.getActivityInfo(ComponentName(pkg, cls), flags)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
        if (activityCache.size >= MAX_CACHE) activityCache.clear()
        activityCache[key] = result
        return result
    }

    /** Settings.Secure keeps a process-local cache, so this is not a binder call per event. */
    private fun currentImePackage(): String? {
        val id = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ) ?: return null
        return ComponentName.unflattenFromString(id)?.packageName
    }

    private fun resolveHomePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return try {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
