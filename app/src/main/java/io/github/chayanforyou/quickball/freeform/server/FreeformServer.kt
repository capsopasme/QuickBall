package io.github.chayanforyou.quickball.freeform.server

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.TaskStackListener
import android.content.ComponentName
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.chayanforyou.quickball.freeform.Box
import io.github.chayanforyou.quickball.freeform.FreeformProtocol
import io.github.chayanforyou.quickball.freeform.FreeformProtocol.Request
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Root daemon for small windows. Started by the app through `su` + app_process with the APK
 * on the classpath; talks [FreeformProtocol] over stdin/stdout.
 *
 * Lifetime is tied to the pipe: when the app closes stdin (idle, service off) or dies, every
 * task it still manages is turned back into a normal app and the daemon exits, so nothing is
 * left floating, always-on-top or shrunk without the app's decoration around it.
 *
 * All state lives on the main looper; binder callbacks and the stdin reader only post to it.
 */
object FreeformServer {
    private const val TAG = "QuickBallFreeformd"

    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        try {
            Server().start()
        } catch (t: Throwable) {
            Log.e(TAG, "Daemon failed to start", t)
            // Not the READY line: the app reports root/daemon failure.
            println("INIT_FAILED ${t.javaClass.simpleName}")
            exitProcess(2)
        }
        Looper.loop()
    }

    @SuppressLint("PrivateApi")
    private class Server {

        private val main = Handler(Looper.getMainLooper())
        private val control = TaskControl()
        private val out = PrintStream(FileOutputStream(FileDescriptor.out), false, "UTF-8")

        /** Managed tasks → density they should keep and the last state sent to the app. */
        private val watched = HashMap<Int, Watched>()
        private val pending = ArrayList<PendingLaunch>()
        private var refreshPosted = false
        private var listenerRegistered = false
        private var shuttingDown = false

        private class Watched(var dpi: Int) {
            var lastState: String? = null
        }

        private class PendingLaunch(
            val packageName: String,
            val bounds: Rect,
            val dpi: Int,
            val deadline: Long,
        ) {
            /** Task this launch turned into; kept until the deadline to follow trampolines. */
            var attachedTask = -1
        }

        private val listener = object : TaskStackListener() {
            override fun onTaskStackChanged() {
                main.post { scheduleRefresh() }
            }

            override fun onTaskRemoved(taskId: Int) {
                main.post { onRemoved(taskId) }
            }

            override fun onTaskFocusChanged(taskId: Int, focused: Boolean) {
                main.post { scheduleRefresh() }
            }

            override fun onTaskDisplayChanged(taskId: Int, newDisplayId: Int) {
                main.post { scheduleRefresh() }
            }

            // Not declared in the compile-time stub (framework parameter types); these override
            // the real TaskStackListener methods at runtime by signature.
            @Suppress("unused")
            fun onTaskCreated(taskId: Int, componentName: ComponentName?) {
                val pkg = componentName?.packageName
                main.post { onCreated(taskId, pkg) }
            }

            @Suppress("unused")
            fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo?) {
                val taskId = taskInfo?.taskId ?: return
                val pkg = (taskInfo.baseActivity ?: taskInfo.topActivity)?.packageName
                main.post { onMovedToFront(taskId, pkg) }
            }
        }

        fun start() {
            control.init()
            try {
                control.registerListener(listener)
                listenerRegistered = true
            } catch (t: Throwable) {
                // Still usable: the app polls with INFO after every change it makes.
                Log.e(TAG, "registerTaskStackListener failed", t)
            }
            Thread(::readLoop, "freeform-stdin").apply { isDaemon = true }.start()
            send(FreeformProtocol.ready())
        }

        // -------------------- I/O --------------------

        private fun readLoop() {
            val reader = BufferedReader(InputStreamReader(FileInputStream(FileDescriptor.`in`), Charsets.UTF_8))
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (_: IOException) {
                    null
                } ?: break
                main.post { handle(line) }
            }
            main.post { shutdown() }
        }

        private fun send(line: String) {
            out.print(line)
            out.print('\n')
            out.flush()
            // The app is gone if stdout broke; give everything back and leave.
            if (out.checkError()) main.post { shutdown() }
        }

        private fun reply(id: Int, vararg args: Any) = send(FreeformProtocol.ok(id, *args))

        private fun fail(id: Int, code: String, message: String? = null) =
            send(FreeformProtocol.error(id, code, message))

        private fun emit(type: String, vararg args: Any) = send(FreeformProtocol.event(type, *args))

        // -------------------- Requests --------------------

        private fun handle(line: String) {
            if (shuttingDown) return
            val request = FreeformProtocol.parseRequest(line) ?: return
            try {
                dispatch(request)
            } catch (e: TaskGone) {
                fail(request.id, FreeformProtocol.ERR_NO_TASK)
            } catch (e: NoFreeform) {
                fail(request.id, FreeformProtocol.ERR_NO_FREEFORM)
            } catch (t: Throwable) {
                Log.e(TAG, "${request.command} failed", t)
                fail(request.id, FreeformProtocol.ERR_FAILED, t.javaClass.simpleName)
            }
        }

        private fun dispatch(r: Request) {
            when (r.command) {
                FreeformProtocol.PING -> reply(r.id)

                FreeformProtocol.WATCH -> {
                    val ids = FreeformProtocol.decodeIds(r.arg(0))
                    watched.keys.retainAll(ids)
                    for (id in ids) watched.getOrPut(id) { Watched(TaskControl.DENSITY_UNDEFINED) }
                    reply(r.id)
                    scheduleRefresh()
                }

                FreeformProtocol.CONVERT_TOP -> {
                    val bounds = r.box(0)
                    val dpi = r.int(1)
                    val excluded = r.arg(2)
                    val top = control.tasks().firstOrNull {
                        it.displayId == TaskControl.DEFAULT_DISPLAY &&
                                it.visible &&
                                it.activityType == TaskControl.ACTIVITY_TYPE_STANDARD &&
                                it.windowingMode == FreeformProtocol.WINDOWING_MODE_FULLSCREEN &&
                                it.packageName != null &&
                                it.packageName != excluded &&
                                it.packageName != SYSTEM_UI
                    } ?: throw TaskGone()
                    val result = convert(top, bounds, dpi)
                    reply(r.id, top.id, top.packageName!!, result.toBox().encode())
                }

                FreeformProtocol.LAUNCH -> {
                    val pkg = r.arg(0) ?: throw IllegalArgumentException("package")
                    val bounds = r.box(1)
                    val dpi = r.int(2)
                    val existing = control.tasks().firstOrNull {
                        it.packageName == pkg &&
                                it.displayId == TaskControl.DEFAULT_DISPLAY &&
                                it.activityType == TaskControl.ACTIVITY_TYPE_STANDARD &&
                                (it.windowingMode == FreeformProtocol.WINDOWING_MODE_FULLSCREEN ||
                                        it.windowingMode == FreeformProtocol.WINDOWING_MODE_FREEFORM)
                    }
                    if (existing != null) {
                        val result = convert(existing, bounds, dpi)
                        reply(r.id, "TASK", existing.id, pkg, result.toBox().encode())
                    } else {
                        pending.removeAll { it.packageName == pkg }
                        pending += PendingLaunch(pkg, bounds, dpi, SystemClock.uptimeMillis() + LAUNCH_WINDOW_MS)
                        reply(r.id, "PENDING")
                        main.postDelayed({ scanPending() }, LAUNCH_SCAN_MS)
                        main.postDelayed({ expirePending() }, LAUNCH_WINDOW_MS + 50)
                    }
                }

                FreeformProtocol.MOVE -> {
                    val task = require(r.int(0))
                    control.resize(task.id, r.box(1))
                    reply(r.id)
                }

                FreeformProtocol.RESIZE -> {
                    val task = require(r.int(0))
                    val dpi = r.int(2)
                    control.resize(task.id, r.box(1))
                    task.token?.let { token -> control.transact { setDensityDpi(token, dpi) } }
                    watched[task.id]?.dpi = dpi
                    reply(r.id)
                }

                FreeformProtocol.MAXIMIZE -> {
                    val task = require(r.int(0))
                    watched.remove(task.id)
                    maximize(task)
                    reply(r.id)
                }

                FreeformProtocol.MINIMIZE -> {
                    val task = require(r.int(0))
                    val token = task.token ?: throw TaskGone()
                    control.transact {
                        setAlwaysOnTop(token, false)
                        reorder(token, false)
                    }
                    reply(r.id)
                }

                FreeformProtocol.RESTORE -> {
                    val task = require(r.int(0))
                    val dpi = r.int(1)
                    control.startFromRecents(task.id, FreeformProtocol.WINDOWING_MODE_UNDEFINED)
                    val after = require(task.id)
                    if (after.windowingMode != FreeformProtocol.WINDOWING_MODE_FREEFORM) throw NoFreeform()
                    pin(after, dpi)
                    reply(r.id, after.bounds.toBox().encode())
                }

                FreeformProtocol.PIN -> {
                    val task = require(r.int(0))
                    pin(task, r.int(1))
                    reply(r.id)
                }

                FreeformProtocol.CLOSE -> {
                    val taskId = r.int(0)
                    watched.remove(taskId)
                    control.remove(taskId)
                    reply(r.id)
                }

                FreeformProtocol.RELEASE -> {
                    val taskId = r.int(0)
                    watched.remove(taskId)
                    control.find(taskId)?.let { release(it) }
                    reply(r.id)
                }

                FreeformProtocol.RESET -> {
                    val taskId = r.int(0)
                    watched.remove(taskId)
                    control.find(taskId)?.token?.let { token ->
                        control.transact {
                            setDensityDpi(token, TaskControl.DENSITY_UNDEFINED)
                            setAlwaysOnTop(token, false)
                        }
                    }
                    reply(r.id)
                }

                FreeformProtocol.INFO -> {
                    val task = require(r.int(0))
                    reply(r.id, stateOf(task).encode())
                }

                else -> fail(r.id, FreeformProtocol.ERR_BAD_REQUEST, r.command)
            }
        }

        // -------------------- Operations --------------------
        //
        // A task's surface is placed by the shell (SystemUI), and only inside a transition: WM
        // never repositions an organized task's surface by itself. So anything that moves or
        // resizes a task on screen goes through resizeTask / startActivityFromRecents, which
        // run proper transitions. Changes applied directly (no transition) are limited to ones
        // that leave the surface where it is: density, always-on-top, and mode or bounds of a
        // task that is full-size or not on screen at that moment.

        /** Turns [task] into a small window at [target] with [dpi]. */
        private fun convert(task: TaskRef, target: Rect, dpi: Int): Rect {
            val token = task.token ?: throw TaskGone()
            when {
                task.windowingMode == FreeformProtocol.WINDOWING_MODE_FREEFORM -> {
                    // Someone else's freeform window: show it, pin it, move it.
                    if (!task.visible) control.startFromRecents(task.id, FreeformProtocol.WINDOWING_MODE_UNDEFINED)
                    control.transact {
                        setAlwaysOnTop(token, true)
                        setDensityDpi(token, dpi)
                    }
                    if (!task.bounds.toBox().approxEquals(target.toBox())) control.resize(task.id, target)
                }
                task.visible -> {
                    // A full-size freeform window looks exactly like the fullscreen app, so the
                    // mode can change directly; the shrink then runs as a transition.
                    control.transact {
                        setWindowingMode(token, FreeformProtocol.WINDOWING_MODE_FREEFORM)
                        setBounds(token, task.bounds)
                        setAlwaysOnTop(token, true)
                    }
                    requireFreeform(task.id, token)
                    control.resize(task.id, target)
                    control.transact { setDensityDpi(token, dpi) }
                }
                else -> {
                    // Not on screen: set it all up, density included (the app relaunches before
                    // its first frame), then open it with a transition.
                    control.transact {
                        setWindowingMode(token, FreeformProtocol.WINDOWING_MODE_FREEFORM)
                        setBounds(token, target)
                    }
                    requireFreeform(task.id, token)
                    control.transact { setDensityDpi(token, dpi) }
                    control.startFromRecents(task.id, FreeformProtocol.WINDOWING_MODE_UNDEFINED)
                    // Only once it is in front: on a hidden task this would raise it without
                    // the shell showing it.
                    control.transact { setAlwaysOnTop(token, true) }
                }
            }
            watched[task.id] = Watched(dpi)
            verifyBoundsLater(task.id, target)
            scheduleRefresh()
            return target
        }

        /** Freeform windows may be disabled (or the app not resizable): undo and report. */
        private fun requireFreeform(taskId: Int, token: Any) {
            val mode = control.find(taskId)?.windowingMode ?: throw TaskGone()
            if (mode == FreeformProtocol.WINDOWING_MODE_FREEFORM) return
            control.transact {
                setAlwaysOnTop(token, false)
                setBounds(token, Rect())
                setWindowingMode(token, FreeformProtocol.WINDOWING_MODE_UNDEFINED)
            }
            throw NoFreeform()
        }

        private fun pin(task: TaskRef, dpi: Int) {
            val token = task.token ?: throw TaskGone()
            control.transact {
                setDensityDpi(token, dpi)
                setAlwaysOnTop(token, true)
            }
            watched.getOrPut(task.id) { Watched(dpi) }.dpi = dpi
            scheduleRefresh()
        }

        /**
         * Back to a normal fullscreen app at the display's density. A small window on screen
         * first grows to full size in a transition; only then does its mode change, which no
         * longer moves anything. [wait] blocks until then (used on the way out).
         */
        private fun maximize(task: TaskRef, wait: Boolean = false) {
            val token = task.token ?: throw TaskGone()
            val full = if (task.visible && task.windowingMode == FreeformProtocol.WINDOWING_MODE_FREEFORM) {
                displayBounds()
            } else {
                null
            }
            if (full == null || task.bounds.toBox().approxEquals(full.toBox())) {
                becomeFullscreen(token)
                return
            }
            control.resize(task.id, full)
            if (wait) {
                val deadline = SystemClock.uptimeMillis() + GROW_TIMEOUT_MS
                while (SystemClock.uptimeMillis() < deadline) {
                    val now = control.find(task.id) ?: return
                    if (now.bounds.toBox().approxEquals(full.toBox())) break
                    SystemClock.sleep(GROW_POLL_MS)
                }
                becomeFullscreen(token)
            } else {
                awaitBounds(task.id, full, GROW_TIMEOUT_MS / GROW_POLL_MS) { becomeFullscreen(token) }
            }
        }

        private fun becomeFullscreen(token: Any) {
            control.transact {
                setWindowingMode(token, FreeformProtocol.WINDOWING_MODE_UNDEFINED)
                setBounds(token, Rect())
                setDensityDpi(token, TaskControl.DENSITY_UNDEFINED)
                setAlwaysOnTop(token, false)
            }
        }

        /** Calls [done] once the task has [target] bounds (or is gone, or time is up). */
        private fun awaitBounds(taskId: Int, target: Rect, attemptsLeft: Long, done: () -> Unit) {
            main.postDelayed({
                val task = try {
                    control.find(taskId)
                } catch (_: Throwable) {
                    null
                }
                when {
                    task == null -> Unit
                    task.bounds.toBox().approxEquals(target.toBox()) || attemptsLeft <= 1 -> runCatching(done)
                    else -> awaitBounds(taskId, target, attemptsLeft - 1, done)
                }
            }, GROW_POLL_MS)
        }

        /** The display area's bounds, as any fullscreen task (home always is one) reports them. */
        private fun displayBounds(): Rect? = control.tasks().firstOrNull {
            it.displayId == TaskControl.DEFAULT_DISPLAY &&
                    it.windowingMode == FreeformProtocol.WINDOWING_MODE_FULLSCREEN &&
                    !it.bounds.isEmpty
        }?.bounds

        /**
         * Gives a task back without the app's management. A small window on screen becomes a
         * fullscreen app; a hidden (minimized) one only loses its overrides and freeform mode,
         * which needs no transition while nothing of it is visible.
         */
        private fun release(task: TaskRef, wait: Boolean = false) {
            val token = task.token ?: return
            if (task.visible && task.windowingMode == FreeformProtocol.WINDOWING_MODE_FREEFORM) {
                maximize(task, wait)
            } else {
                becomeFullscreen(token)
            }
        }

        /** WM can still adjust launch bounds after the fact; correct it once if it did. */
        private fun verifyBoundsLater(taskId: Int, bounds: Rect) {
            main.postDelayed({
                if (shuttingDown || taskId !in watched) return@postDelayed
                val task = control.find(taskId) ?: return@postDelayed
                if (task.windowingMode == FreeformProtocol.WINDOWING_MODE_FREEFORM &&
                    !task.bounds.toBox().approxEquals(bounds.toBox())
                ) {
                    runCatching { control.resize(taskId, bounds) }
                }
            }, VERIFY_DELAY_MS)
        }

        // -------------------- Pending launches --------------------

        private fun onCreated(taskId: Int, pkg: String?) {
            if (shuttingDown) return
            val now = SystemClock.uptimeMillis()
            val launch = pending.firstOrNull { it.packageName == pkg && now < it.deadline }
            if (launch != null) {
                val previous = launch.attachedTask
                // A second task while the first still exists is the app's own business.
                if (previous == -1 || control.find(previous) == null) {
                    attach(launch, taskId, attempt = 0)
                    return
                }
            }
            if (watched.isNotEmpty()) {
                main.postDelayed({ enforceFullscreen(taskId) }, FOREIGN_CHECK_MS)
            }
        }

        private fun onMovedToFront(taskId: Int, pkg: String?) {
            if (shuttingDown) return
            if (taskId in watched) {
                emit(FreeformProtocol.EVT_FRONT, taskId)
            } else {
                // A pending launch that reused an existing (e.g. restored) task.
                val now = SystemClock.uptimeMillis()
                pending.firstOrNull { it.packageName == pkg && it.attachedTask == -1 && now < it.deadline }
                    ?.let { attach(it, taskId, attempt = 0) }
            }
            scheduleRefresh()
        }

        private fun attach(launch: PendingLaunch, taskId: Int, attempt: Int) {
            val task = control.find(taskId)
            if (task == null) {
                // Created but not listed yet; it shows up within a few frames.
                if (attempt < 5) main.postDelayed({ attach(launch, taskId, attempt + 1) }, 40)
                return
            }
            if (task.windowingMode != FreeformProtocol.WINDOWING_MODE_FREEFORM) {
                pending.remove(launch)
                emit(FreeformProtocol.EVT_LAUNCH_FAILED, launch.packageName, FreeformProtocol.ERR_NO_FREEFORM)
                return
            }
            val token = task.token ?: return
            // Usually lands before the app's first frame (its process is still starting).
            control.transact {
                setDensityDpi(token, launch.dpi)
                setAlwaysOnTop(token, true)
            }
            launch.attachedTask = taskId
            watched[taskId] = Watched(launch.dpi)
            emit(
                FreeformProtocol.EVT_ATTACHED, taskId, launch.packageName,
                task.bounds.toBox().encode(), launch.dpi
            )
            scheduleRefresh()
        }

        /** Restored recent tasks don't report onTaskCreated; find them by package instead. */
        private fun scanPending() {
            if (shuttingDown || pending.none { it.attachedTask == -1 }) return
            val tasks = control.tasks()
            for (launch in pending.filter { it.attachedTask == -1 }) {
                val task = tasks.firstOrNull {
                    it.packageName == launch.packageName && it.id !in watched &&
                            it.windowingMode == FreeformProtocol.WINDOWING_MODE_FREEFORM
                } ?: continue
                attach(launch, task.id, attempt = 0)
            }
        }

        private fun expirePending() {
            scanPending()
            val now = SystemClock.uptimeMillis()
            val expired = pending.filter { now >= it.deadline }
            pending.removeAll(expired.toSet())
            for (launch in expired) {
                if (launch.attachedTask == -1) {
                    emit(FreeformProtocol.EVT_LAUNCH_FAILED, launch.packageName, FreeformProtocol.ERR_TIMEOUT)
                }
            }
        }

        /**
         * A task launched from a small window inherits freeform mode from its source. Without
         * the app's decoration it would sit undecorated under the always-on-top window, so it
         * opens fullscreen instead.
         */
        private fun enforceFullscreen(taskId: Int) {
            if (shuttingDown || watched.isEmpty() || taskId in watched) return
            if (pending.any { it.attachedTask == taskId }) return
            val task = control.find(taskId) ?: return
            if (task.windowingMode == FreeformProtocol.WINDOWING_MODE_FREEFORM &&
                task.activityType == TaskControl.ACTIVITY_TYPE_STANDARD &&
                task.displayId == TaskControl.DEFAULT_DISPLAY
            ) {
                runCatching { maximize(task) }
            }
        }

        // -------------------- State reporting --------------------

        private fun onRemoved(taskId: Int) {
            if (watched.remove(taskId) != null) emit(FreeformProtocol.EVT_REMOVED, taskId)
        }

        private fun scheduleRefresh() {
            if (refreshPosted || watched.isEmpty() || shuttingDown) return
            refreshPosted = true
            main.postDelayed({
                refreshPosted = false
                refresh()
            }, REFRESH_DEBOUNCE_MS)
        }

        private fun refresh() {
            if (watched.isEmpty() || shuttingDown) return
            val tasks = try {
                control.tasks().associateBy { it.id }
            } catch (t: Throwable) {
                Log.e(TAG, "getTasks failed", t)
                return
            }
            for ((id, watch) in watched) {
                // Gone tasks are reported by onTaskRemoved.
                val task = tasks[id] ?: continue
                val state = stateOf(task).encode()
                if (state != watch.lastState) {
                    watch.lastState = state
                    emit(FreeformProtocol.EVT_STATE, state)
                }
            }
        }

        private fun stateOf(task: TaskRef) = FreeformProtocol.TaskState(
            taskId = task.id,
            windowingMode = task.windowingMode,
            visible = task.visible,
            focused = task.focused,
            bounds = task.bounds.toBox(),
        )

        // -------------------- Shutdown --------------------

        private fun shutdown() {
            if (shuttingDown) return
            shuttingDown = true
            for (id in watched.keys.toList()) {
                try {
                    control.find(id)?.let { release(it, wait = true) }
                } catch (t: Throwable) {
                    Log.e(TAG, "release $id failed", t)
                }
            }
            watched.clear()
            if (listenerRegistered) runCatching { control.unregisterListener(listener) }
            exitProcess(0)
        }

        // -------------------- Helpers --------------------

        private fun require(taskId: Int): TaskRef = control.find(taskId) ?: throw TaskGone()

        private fun Request.int(index: Int): Int =
            arg(index)?.toIntOrNull() ?: throw IllegalArgumentException("arg $index")

        private fun Request.box(index: Int): Rect {
            val box = Box.decode(arg(index)) ?: throw IllegalArgumentException("box $index")
            if (box.isEmpty) throw IllegalArgumentException("empty box")
            return Rect(box.left, box.top, box.right, box.bottom)
        }

        private fun Rect.toBox() = Box(left, top, right, bottom)

        private class TaskGone : RuntimeException()
        private class NoFreeform : RuntimeException()

        companion object {
            private const val SYSTEM_UI = "com.android.systemui"
            private const val REFRESH_DEBOUNCE_MS = 60L
            private const val VERIFY_DELAY_MS = 450L
            private const val FOREIGN_CHECK_MS = 300L
            private const val LAUNCH_SCAN_MS = 1500L
            private const val LAUNCH_WINDOW_MS = 4000L
            private const val GROW_TIMEOUT_MS = 1500L
            private const val GROW_POLL_MS = 50L
        }
    }
}
