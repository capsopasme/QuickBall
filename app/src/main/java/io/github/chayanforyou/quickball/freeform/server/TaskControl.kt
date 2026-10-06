package io.github.chayanforyou.quickball.freeform.server

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.graphics.Rect
import android.os.Bundle
import io.github.chayanforyou.quickball.freeform.FreeformProtocol
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** A task as the daemon needs it. [token] is the task's WindowContainerToken. */
internal class TaskRef(
    val id: Int,
    val packageName: String?,
    val token: Any?,
    val windowingMode: Int,
    val activityType: Int,
    val visible: Boolean,
    val focused: Boolean,
    val bounds: Rect,
    val displayId: Int,
)

/**
 * Hidden framework calls, made by reflection from the root daemon (app_process has no hidden
 * API restrictions). Every lookup happens once in [init]; a missing method fails there, before
 * the daemon reports READY, instead of half way through an operation.
 *
 * Signatures checked against LineageOS 23.2 (Android 16) frameworks/base.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
internal class TaskControl {

    companion object {
        const val ACTIVITY_TYPE_STANDARD = 1
        const val DEFAULT_DISPLAY = 0
        private const val INVALID_DISPLAY = -1
        /** ActivityTaskManager.RESIZE_MODE_USER: keep the window while resizing. */
        private const val RESIZE_MODE_USER = 1
        /** Configuration.DENSITY_DPI_UNDEFINED: inherit the display density again. */
        const val DENSITY_UNDEFINED = 0
        private const val MAX_TASKS = 48
    }

    private lateinit var atm: Any
    private lateinit var organizer: Any

    private lateinit var getTasks: Method
    private var getTasksArity = 0
    private lateinit var startActivityFromRecents: Method
    private lateinit var removeTask: Method
    private lateinit var resizeTask: Method
    private lateinit var registerTaskStackListener: Method
    private lateinit var unregisterTaskStackListener: Method
    private lateinit var applyTransaction: Method

    private lateinit var wctClass: Class<*>
    private lateinit var wctSetBounds: Method
    private lateinit var wctSetDensityDpi: Method
    private lateinit var wctSetAlwaysOnTop: Method
    private lateinit var wctReorder: Method
    private lateinit var wctSetWindowingMode: Method

    private lateinit var fTaskId: Field
    private lateinit var fToken: Field
    private lateinit var fConfiguration: Field
    private lateinit var fWindowConfiguration: Field
    private lateinit var fIsVisible: Field
    private lateinit var fIsFocused: Field
    private lateinit var fDisplayId: Field
    private lateinit var fBaseActivity: Field
    private lateinit var fTopActivity: Field
    private lateinit var mWinGetBounds: Method
    private lateinit var mWinGetWindowingMode: Method
    private lateinit var mWinGetActivityType: Method

    fun init() {
        val atmClass = Class.forName("android.app.ActivityTaskManager")
        atm = atmClass.getMethod("getService").invoke(null)
            ?: throw IllegalStateException("activity_task service unavailable")
        val iAtm = Class.forName("android.app.IActivityTaskManager")

        val candidates = iAtm.methods.filter { it.name == "getTasks" }
        getTasks = candidates.maxByOrNull { it.parameterTypes.size }
            ?: throw NoSuchMethodException("getTasks")
        getTasksArity = getTasks.parameterTypes.size
        startActivityFromRecents = iAtm.getMethod(
            "startActivityFromRecents", Int::class.javaPrimitiveType, Bundle::class.java
        )
        removeTask = iAtm.getMethod("removeTask", Int::class.javaPrimitiveType)
        resizeTask = iAtm.getMethod(
            "resizeTask", Int::class.javaPrimitiveType, Rect::class.java, Int::class.javaPrimitiveType
        )
        val listenerClass = Class.forName("android.app.ITaskStackListener")
        registerTaskStackListener = iAtm.getMethod("registerTaskStackListener", listenerClass)
        unregisterTaskStackListener = iAtm.getMethod("unregisterTaskStackListener", listenerClass)

        organizer = iAtm.getMethod("getWindowOrganizerController").invoke(atm)
            ?: throw IllegalStateException("window organizer unavailable")
        wctClass = Class.forName("android.window.WindowContainerTransaction")
        applyTransaction = Class.forName("android.window.IWindowOrganizerController")
            .getMethod("applyTransaction", wctClass)

        val tokenClass = Class.forName("android.window.WindowContainerToken")
        val bool = Boolean::class.javaPrimitiveType
        val int = Int::class.javaPrimitiveType
        wctSetBounds = wctClass.getMethod("setBounds", tokenClass, Rect::class.java)
        wctSetDensityDpi = wctClass.getMethod("setDensityDpi", tokenClass, int)
        wctSetAlwaysOnTop = wctClass.getMethod("setAlwaysOnTop", tokenClass, bool)
        wctReorder = wctClass.getMethod("reorder", tokenClass, bool)
        wctSetWindowingMode = wctClass.getMethod("setWindowingMode", tokenClass, int)

        val taskInfo = Class.forName("android.app.TaskInfo")
        fTaskId = taskInfo.getField("taskId")
        fToken = taskInfo.getField("token")
        fConfiguration = taskInfo.getField("configuration")
        fIsVisible = taskInfo.getField("isVisible")
        fIsFocused = taskInfo.getField("isFocused")
        fDisplayId = taskInfo.getField("displayId")
        fBaseActivity = taskInfo.getField("baseActivity")
        fTopActivity = taskInfo.getField("topActivity")
        fWindowConfiguration = android.content.res.Configuration::class.java
            .getField("windowConfiguration")
        val winConfig = Class.forName("android.app.WindowConfiguration")
        mWinGetBounds = winConfig.getMethod("getBounds")
        mWinGetWindowingMode = winConfig.getMethod("getWindowingMode")
        mWinGetActivityType = winConfig.getMethod("getActivityType")
    }

    // -------------------- Queries --------------------

    fun tasks(): List<TaskRef> {
        val args: Array<Any> = when (getTasksArity) {
            4 -> arrayOf(MAX_TASKS, false, false, INVALID_DISPLAY)
            3 -> arrayOf(MAX_TASKS, false, false)
            2 -> arrayOf(MAX_TASKS, false)
            else -> arrayOf(MAX_TASKS)
        }
        val list = call(getTasks, atm, *args) as? List<*> ?: return emptyList()
        return list.mapNotNull { info -> info?.let { toTaskRef(it) } }
    }

    fun find(taskId: Int): TaskRef? = tasks().firstOrNull { it.id == taskId }

    private fun toTaskRef(info: Any): TaskRef? = try {
        val configuration = fConfiguration.get(info)
        val window = fWindowConfiguration.get(configuration)
        val bounds = Rect(mWinGetBounds.invoke(window) as Rect)
        val component = (fBaseActivity.get(info) as? ComponentName)
            ?: (fTopActivity.get(info) as? ComponentName)
        TaskRef(
            id = fTaskId.getInt(info),
            packageName = component?.packageName,
            token = fToken.get(info),
            windowingMode = mWinGetWindowingMode.invoke(window) as Int,
            activityType = mWinGetActivityType.invoke(window) as Int,
            visible = fIsVisible.getBoolean(info),
            focused = fIsFocused.getBoolean(info),
            bounds = bounds,
            displayId = fDisplayId.getInt(info),
        )
    } catch (_: ReflectiveOperationException) {
        null
    }

    // -------------------- Task operations --------------------

    /** Brings a task to the front with a proper shell transition, optionally changing mode. */
    fun startFromRecents(taskId: Int, windowingMode: Int) {
        val options = ActivityOptions.makeBasic().toBundle()
        if (windowingMode != FreeformProtocol.WINDOWING_MODE_UNDEFINED) {
            options.putInt(FreeformProtocol.KEY_LAUNCH_WINDOWING_MODE, windowingMode)
        }
        call(startActivityFromRecents, atm, taskId, options)
    }

    /** Moves/resizes a task through a shell transition (the only way its surface follows). */
    fun resize(taskId: Int, bounds: Rect) {
        call(resizeTask, atm, taskId, bounds, RESIZE_MODE_USER)
    }

    fun remove(taskId: Int): Boolean = call(removeTask, atm, taskId) as? Boolean ?: false

    fun registerListener(listener: Any) {
        call(registerTaskStackListener, atm, listener)
    }

    fun unregisterListener(listener: Any) {
        call(unregisterTaskStackListener, atm, listener)
    }

    /**
     * Applies window container changes directly (no transition). Only used for properties that
     * don't move the task's surface: density, always-on-top, z-order of hidden tasks, and the
     * requested bounds / mode of tasks that are not on screen.
     */
    fun transact(block: Transaction.() -> Unit) {
        val wct = wctClass.getConstructor().newInstance()
        Transaction(wct).block()
        call(applyTransaction, organizer, wct)
    }

    inner class Transaction(private val wct: Any) {
        fun setBounds(token: Any, bounds: Rect) {
            call(wctSetBounds, wct, token, bounds)
        }

        fun setDensityDpi(token: Any, dpi: Int) {
            call(wctSetDensityDpi, wct, token, dpi)
        }

        fun setAlwaysOnTop(token: Any, alwaysOnTop: Boolean) {
            call(wctSetAlwaysOnTop, wct, token, alwaysOnTop)
        }

        fun reorder(token: Any, onTop: Boolean) {
            call(wctReorder, wct, token, onTop)
        }

        fun setWindowingMode(token: Any, mode: Int) {
            call(wctSetWindowingMode, wct, token, mode)
        }
    }

    /** Unwraps InvocationTargetException so callers see the framework's own exception. */
    private fun call(method: Method, target: Any, vararg args: Any?): Any? = try {
        method.invoke(target, *args)
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }
}
