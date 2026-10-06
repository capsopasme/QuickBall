package io.github.chayanforyou.quickball.domain.models

/**
 * What a gesture (pill long-press/swipes, wave bar swipes) is bound to, stored as one string in
 * the existing preference: either a [MenuAction] name ("RECENT") or "LAUNCH_APP:<package>" to
 * open an app. Old values stay valid because a plain action name parses exactly as before.
 */
object GestureBinding {

    private const val APP_PREFIX = "LAUNCH_APP:"

    fun forApp(packageName: String): String = APP_PREFIX + packageName

    /** The package for an app binding, or null for an action binding. */
    fun appPackage(binding: String?): String? =
        binding?.takeIf { it.startsWith(APP_PREFIX) }
            ?.substring(APP_PREFIX.length)
            ?.takeIf { it.isNotBlank() }

    /** The action for an action binding, or null for an app binding / unknown value. */
    fun action(binding: String?): MenuAction? =
        MenuAction.fromName(binding)?.takeIf { it != MenuAction.LAUNCH_APP }
}
