package io.github.chayanforyou.quickball.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Remembers the last two foreground apps so the ball can toggle between them.
 *
 * Only TYPE_WINDOW_STATE_CHANGED events whose class is a real Activity count, which filters
 * out dialogs, popups, toasts and the notification shade. The launcher, the current input
 * method, SystemUI and QuickBall itself are ignored, so "home → other app" still leaves the
 * previous app as the switch target.
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

    private fun isIgnored(pkg: String): Boolean {
        return pkg == context.packageName ||
                pkg in IGNORED_PACKAGES ||
                pkg in homePackages ||
                pkg == currentImePackage()
    }

    private fun isActivity(pkg: String, cls: String): Boolean {
        val key = "$pkg/$cls"
        activityCache[key]?.let { return it }
        val result = try {
            pm.getActivityInfo(ComponentName(pkg, cls), 0)
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
