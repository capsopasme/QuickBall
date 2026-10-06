package io.github.chayanforyou.quickball.freeform

import kotlin.math.abs

/**
 * A rectangle in screen pixels. Used instead of android.graphics.Rect so geometry and protocol
 * code stays plain Kotlin (runs in JVM unit tests and in the root daemon alike).
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun moveTo(newLeft: Int, newTop: Int) = Box(newLeft, newTop, newLeft + width, newTop + height)

    fun approxEquals(other: Box, tolerance: Int = 2): Boolean =
        abs(left - other.left) <= tolerance && abs(top - other.top) <= tolerance &&
                abs(right - other.right) <= tolerance && abs(bottom - other.bottom) <= tolerance

    fun intersects(other: Box): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun encode(): String = "$left,$top,$right,$bottom"

    companion object {
        fun decode(text: String?): Box? {
            val parts = text?.split(',') ?: return null
            if (parts.size != 4) return null
            val values = parts.map { it.trim().toIntOrNull() ?: return null }
            return Box(values[0], values[1], values[2], values[3])
        }
    }
}

/**
 * Line protocol between the app and the root daemon, over the daemon's stdin/stdout.
 *
 * App → daemon:  `<id> <COMMAND> <args...>`
 * Daemon → app:  `READY <version>` once, then `R <id> OK <args...>` / `R <id> ERR <code> <text>`
 *                replies and `E <TYPE> <args...>` events.
 *
 * Arguments never contain spaces (package names, integers, boxes as "l,t,r,b").
 */
object FreeformProtocol {
    const val VERSION = 1

    // Commands
    const val PING = "PING"
    /** `WATCH <taskId,taskId,...|->` the full set of tasks the app manages. */
    const val WATCH = "WATCH"
    /** `CONVERT_TOP <box> <dpi> <excludedPackage>` → `OK <taskId> <package> <box>` */
    const val CONVERT_TOP = "CONVERT_TOP"
    /** `LAUNCH <package> <box> <dpi>` → `OK TASK <taskId> <package> <box>` or `OK PENDING` */
    const val LAUNCH = "LAUNCH"
    /** `MOVE <taskId> <box>` */
    const val MOVE = "MOVE"
    /** `RESIZE <taskId> <box> <dpi>` */
    const val RESIZE = "RESIZE"
    /** `MAXIMIZE <taskId>` */
    const val MAXIMIZE = "MAXIMIZE"
    /** `MINIMIZE <taskId>` */
    const val MINIMIZE = "MINIMIZE"
    /** `RESTORE <taskId> <dpi>` → `OK <box>` */
    const val RESTORE = "RESTORE"
    /** `CLOSE <taskId>` */
    const val CLOSE = "CLOSE"
    /** `RELEASE <taskId>` give the task back as a normal fullscreen app. */
    const val RELEASE = "RELEASE"
    /** `RESET <taskId>` drop density/always-on-top overrides (task already left freeform). */
    const val RESET = "RESET"
    /** `PIN <taskId> <dpi>` re-apply always-on-top and density. */
    const val PIN = "PIN"
    /** `INFO <taskId>` → `OK <state...>` (see [TaskState]) */
    const val INFO = "INFO"

    // Events
    /** `E REMOVED <taskId>` */
    const val EVT_REMOVED = "REMOVED"
    /** `E STATE <state...>` a watched task changed. */
    const val EVT_STATE = "STATE"
    /** `E FRONT <taskId>` a watched task was brought to the front by someone else. */
    const val EVT_FRONT = "FRONT"
    /** `E ATTACHED <taskId> <package> <box> <dpi>` a pending launch became a small window. */
    const val EVT_ATTACHED = "ATTACHED"
    /** `E LAUNCH_FAILED <package> <code>` */
    const val EVT_LAUNCH_FAILED = "LAUNCH_FAILED"

    // Error codes
    const val ERR_NO_TASK = "NO_TASK"
    const val ERR_NO_FREEFORM = "NO_FREEFORM"
    const val ERR_FAILED = "FAILED"
    const val ERR_BAD_REQUEST = "BAD_REQUEST"
    /** Client-side only: the daemon did not answer in time. */
    const val ERR_TIMEOUT = "TIMEOUT"
    /** Client-side only: `su` is missing. */
    const val ERR_NO_ROOT = "NO_ROOT"
    /** Client-side only: `su` refused or the daemon failed to start. */
    const val ERR_ROOT_DENIED = "ROOT_DENIED"
    /** Client-side only: the daemon went away while the request was in flight. */
    const val ERR_DAEMON_LOST = "DAEMON_LOST"

    // Framework constants the protocol carries (WindowConfiguration)
    const val WINDOWING_MODE_UNDEFINED = 0
    const val WINDOWING_MODE_FULLSCREEN = 1
    const val WINDOWING_MODE_FREEFORM = 5

    /** Bundle key read by ActivityOptions(Bundle) for the launch windowing mode. */
    const val KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode"

    sealed interface Line

    data class Ready(val version: Int) : Line

    data class Reply(val id: Int, val ok: Boolean, val code: String, val args: List<String>) : Line {
        fun arg(index: Int): String? = args.getOrNull(index)
    }

    data class Event(val type: String, val args: List<String>) : Line {
        fun arg(index: Int): String? = args.getOrNull(index)
    }

    data class Request(val id: Int, val command: String, val args: List<String>) {
        fun arg(index: Int): String? = args.getOrNull(index)
    }

    /** A watched task as the daemon reports it: `<taskId> <mode> <visible> <focused> <box>`. */
    data class TaskState(
        val taskId: Int,
        val windowingMode: Int,
        val visible: Boolean,
        val focused: Boolean,
        val bounds: Box,
    ) {
        fun encode(): String =
            "$taskId $windowingMode ${if (visible) 1 else 0} ${if (focused) 1 else 0} ${bounds.encode()}"

        companion object {
            fun decode(args: List<String>, offset: Int = 0): TaskState? {
                if (args.size < offset + 5) return null
                return TaskState(
                    taskId = args[offset].toIntOrNull() ?: return null,
                    windowingMode = args[offset + 1].toIntOrNull() ?: return null,
                    visible = args[offset + 2] == "1",
                    focused = args[offset + 3] == "1",
                    bounds = Box.decode(args[offset + 4]) ?: return null,
                )
            }
        }
    }

    fun request(id: Int, command: String, vararg args: Any): String = buildLine(id.toString(), command, args)

    fun ok(id: Int, vararg args: Any): String = buildLine("R", "$id OK", args)

    fun error(id: Int, code: String, message: String?): String =
        buildLine("R", "$id ERR $code", arrayOf(sanitize(message ?: "")))

    fun event(type: String, vararg args: Any): String = buildLine("E", type, args)

    fun ready(): String = "READY $VERSION"

    /** Parses a line written by the daemon; null for anything else (e.g. stray su output). */
    fun parseFromDaemon(line: String): Line? {
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return when (parts[0]) {
            "READY" -> Ready(parts.getOrNull(1)?.toIntOrNull() ?: return null)
            "R" -> {
                val id = parts.getOrNull(1)?.toIntOrNull() ?: return null
                when (parts.getOrNull(2)) {
                    "OK" -> Reply(id, ok = true, code = "OK", args = parts.drop(3))
                    "ERR" -> Reply(
                        id, ok = false,
                        code = parts.getOrNull(3) ?: ERR_FAILED,
                        args = parts.drop(4)
                    )
                    else -> null
                }
            }
            "E" -> Event(parts.getOrNull(1) ?: return null, parts.drop(2))
            else -> null
        }
    }

    /** Parses a line written by the app; null if malformed. */
    fun parseRequest(line: String): Request? {
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val id = parts[0].toIntOrNull() ?: return null
        return Request(id, parts[1], parts.drop(2))
    }

    fun encodeIds(ids: Collection<Int>): String = if (ids.isEmpty()) "-" else ids.joinToString(",")

    fun decodeIds(text: String?): Set<Int> {
        if (text.isNullOrEmpty() || text == "-") return emptySet()
        return text.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    private fun buildLine(head: String, second: String, args: Array<out Any>): String =
        buildString {
            append(head).append(' ').append(second)
            for (arg in args) {
                val text = arg.toString()
                if (text.isNotEmpty()) append(' ').append(text)
            }
        }

    /** Free text (error messages) goes last and must stay on one line. */
    private fun sanitize(text: String): String =
        text.replace(Regex("\\s+"), "_").take(200)
}
