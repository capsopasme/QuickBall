package io.github.chayanforyou.quickball.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.annotation.WorkerThread
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toBitmap
import io.github.chayanforyou.quickball.domain.AppPreference
import io.github.chayanforyou.quickball.domain.models.InstalledApp

/**
 * Get the application icon for a given package name.
 * Returns the app's icon drawable or a default app icon if the package is not found.
 *
 * @param packageName The package name of the application
 * @return The app's icon drawable, or default icon if package not found
 */
fun Context.getAppIcon(packageName: String): Drawable? {
    return try {
        packageManager.getApplicationIcon(packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        ContextCompat.getDrawable(this, android.R.drawable.sym_def_app_icon)
    }
}

/** Size the app lists draw icons at; icons are rasterised once at this size. */
private const val APP_ICON_DP = 36f

/**
 * Load all installed applications that have a launcher entry.
 * Filters out the current app and apps without proper names.
 *
 * Call it off the main thread. One launcher query replaces a getLaunchIntentForPackage() IPC
 * per installed package, and each icon is rasterised here once instead of on every
 * recomposition of the list (a full-size bitmap per app each time a switch was toggled).
 *
 * @param sortBySelectedFirst If true, selected apps (from auto-hide list) appear first,
 *                            then sorted alphabetically. If false, all apps sorted alphabetically.
 * @return List of installed apps with their name, package, icon, and selection state
 */
@WorkerThread
fun Context.loadInstalledApps(sortBySelectedFirst: Boolean = false): List<InstalledApp> {
    val pm = packageManager
    val autoHideApps = AppPreference.getInstance(this).autoHideApps
    val iconPx = DensityUtils.dp2px(APP_ICON_DP)
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val seen = HashSet<String>()

    val apps = pm.queryIntentActivities(launcherIntent, 0)
        .mapNotNull { resolveInfo ->
            val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
            val pkg = appInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) return@mapNotNull null
            val appName = pm.getApplicationLabel(appInfo).toString()
            if (appName.isBlank() || appName.equals(pkg, ignoreCase = true)) return@mapNotNull null
            val icon = try {
                pm.getApplicationIcon(appInfo).toBitmap(iconPx, iconPx).asImageBitmap()
            } catch (_: Exception) {
                return@mapNotNull null
            }
            InstalledApp(
                appName = appName,
                packageName = pkg,
                icon = icon,
                isSelected = autoHideApps.contains(pkg)
            )
        }

    return if (sortBySelectedFirst) {
        apps.sortedWith(compareByDescending<InstalledApp> { it.isSelected }
            .thenBy { it.appName.lowercase() })
    } else {
        apps.sortedBy { it.appName.lowercase() }
    }
}

/**
 * Returns the actual screen size in pixels as a [Pair] of width and height.
 *
 * Compared to [DisplayMetrics], this provides more reliable dimensions
 * across orientation changes, multi-window mode, and different system UI
 * configurations.
 *
 * @return A [Pair] where:
 * - first = screen width in pixels
 * - second = screen height in pixels
 */
fun Context.getScreenSize(): Pair<Int, Int> {
    return getSystemService<WindowManager>()?.getScreenSize() ?: (0 to 0)
}

/**
 * Returns the actual screen size in pixels as a [Pair] of width and height.
 *
 * Uses [WindowMetrics] on Android R and above, and [Display.getRealSize]
 * on older Android versions for compatibility.
 *
 * @return A [Pair] where:
 * - first = screen width in pixels
 * - second = screen height in pixels
 */
fun WindowManager.getScreenSize(): Pair<Int, Int> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = maximumWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        @Suppress("DEPRECATION")
        Point().apply {
            defaultDisplay.getRealSize(this)
        }.let {
            it.x to it.y
        }
    }
}