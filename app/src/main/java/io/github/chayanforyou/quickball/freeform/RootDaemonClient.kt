package io.github.chayanforyou.quickball.freeform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.chayanforyou.quickball.freeform.FreeformProtocol.Event
import io.github.chayanforyou.quickball.freeform.FreeformProtocol.Ready
import io.github.chayanforyou.quickball.freeform.FreeformProtocol.Reply
import io.github.chayanforyou.quickball.freeform.server.FreeformServer
import java.io.IOException
import java.io.OutputStream

/**
 * Starts the root daemon on demand (`su` → app_process with this APK) and talks to it over its
 * stdin/stdout. The pipe is private to this process, so no other app can drive the daemon, and
 * the daemon exits by itself when this process goes away.
 *
 * Main-thread only. Replies and events are delivered on the main thread.
 *
 * The daemon is a JVM (~25 MB), so it only runs while small windows exist, plus [IDLE_STOP_MS]
 * to keep the next open fast; [keepAlive] tells it whether windows exist.
 */
class RootDaemonClient(private val context: Context) {

    interface Listener {
        fun onDaemonEvent(event: Event)

        /** The daemon died while it was needed; its state is gone. */
        fun onDaemonLost()
    }

    companion object {
        private const val TAG = "RootDaemonClient"
        private const val PROCESS_NAME = "quickball_freeformd"
        private const val START_TIMEOUT_MS = 15_000L
        private const val DEFAULT_CALL_TIMEOUT_MS = 5_000L
        private const val IDLE_STOP_MS = 180_000L
    }

    var listener: Listener? = null

    /** True while windows are managed: the daemon then stays up and its death is reported. */
    var keepAlive = false
        set(value) {
            field = value
            scheduleIdleStop()
        }

    private enum class State { STOPPED, STARTING, READY }

    private class Call(val callback: (Reply) -> Unit, val timeout: Runnable)

    private val main = Handler(Looper.getMainLooper())
    private var state = State.STOPPED
    private var process: Process? = null
    private var stdin: OutputStream? = null
    private val startWaiters = ArrayList<(String?) -> Unit>()
    private val calls = HashMap<Int, Call>()
    private var nextId = 1
    private var lastNoise: String? = null

    private val startTimeout = Runnable {
        if (state == State.STARTING) {
            Log.w(TAG, "Daemon did not start in time (${lastNoise ?: "no output"})")
            kill()
            finishStart(FreeformProtocol.ERR_ROOT_DENIED)
        }
    }

    private val idleStop = Runnable {
        if (!keepAlive && calls.isEmpty()) stop()
    }

    val isRunning: Boolean get() = state == State.READY

    /** Starts the daemon if needed; [onResult] gets null when ready, else an error code. */
    fun ensureStarted(onResult: (String?) -> Unit) {
        when (state) {
            State.READY -> onResult(null)
            State.STARTING -> startWaiters += onResult
            State.STOPPED -> {
                startWaiters += onResult
                start()
            }
        }
        scheduleIdleStop()
    }

    fun call(
        command: String,
        vararg args: Any,
        timeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
        callback: (Reply) -> Unit = {},
    ) {
        ensureStarted { error ->
            if (error != null) {
                callback(Reply(0, ok = false, code = error, args = emptyList()))
                return@ensureStarted
            }
            val id = nextId++
            val timeout = Runnable {
                calls.remove(id)?.callback?.invoke(
                    Reply(id, ok = false, code = FreeformProtocol.ERR_TIMEOUT, args = emptyList())
                )
                scheduleIdleStop()
            }
            calls[id] = Call(callback, timeout)
            main.postDelayed(timeout, timeoutMs)
            if (!write(FreeformProtocol.request(id, command, *args))) {
                main.removeCallbacks(timeout)
                calls.remove(id)
                callback(Reply(id, ok = false, code = FreeformProtocol.ERR_DAEMON_LOST, args = emptyList()))
            }
        }
    }

    /**
     * Closes the daemon's stdin. It then gives every managed task back as a normal app and
     * exits; the process is destroyed as a fallback if it does not.
     */
    fun stop() {
        main.removeCallbacks(idleStop)
        val p = process ?: run {
            // Still waiting for su: the reader thread destroys the process once it arrives.
            if (state == State.STARTING) {
                main.removeCallbacks(startTimeout)
                state = State.STOPPED
                finishStart(FreeformProtocol.ERR_DAEMON_LOST)
            }
            state = State.STOPPED
            return
        }
        try {
            stdin?.close()
        } catch (_: IOException) {
        }
        main.postDelayed({ p.destroy() }, 2_000L)
        detach(p, error = FreeformProtocol.ERR_DAEMON_LOST, notifyLost = false)
    }

    // -------------------- Internals --------------------

    private fun start() {
        state = State.STARTING
        lastNoise = null
        val apk = context.applicationInfo.sourceDir
        val command = "export CLASSPATH='$apk'; exec /system/bin/app_process /system/bin " +
                "--nice-name=$PROCESS_NAME ${FreeformServer::class.java.name} 2>/dev/null"
        main.postDelayed(startTimeout, START_TIMEOUT_MS)
        Thread({
            val p = try {
                ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            } catch (e: IOException) {
                Log.w(TAG, "su unavailable", e)
                main.post {
                    main.removeCallbacks(startTimeout)
                    finishStart(FreeformProtocol.ERR_NO_ROOT)
                }
                return@Thread
            }
            main.post {
                if (state == State.STARTING) {
                    process = p
                    stdin = p.outputStream
                } else {
                    p.destroy()
                }
            }
            readLoop(p)
        }, "freeform-daemon-reader").apply { isDaemon = true }.start()
    }

    private fun readLoop(p: Process) {
        try {
            p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val parsed = FreeformProtocol.parseFromDaemon(line)
                    if (parsed == null) {
                        // e.g. "Permission denied" from su; kept for the failure log.
                        main.post { lastNoise = line.take(200) }
                        continue
                    }
                    main.post { onLine(p, parsed) }
                }
            }
        } catch (_: IOException) {
        }
        main.post { onEnded(p) }
    }

    private fun onLine(p: Process, line: FreeformProtocol.Line) {
        if (p !== process) return
        when (line) {
            is Ready -> if (state == State.STARTING) {
                main.removeCallbacks(startTimeout)
                state = State.READY
                finishStart(null)
            }
            is Reply -> {
                val call = calls.remove(line.id) ?: return
                main.removeCallbacks(call.timeout)
                call.callback(line)
                scheduleIdleStop()
            }
            is Event -> listener?.onDaemonEvent(line)
        }
    }

    private fun onEnded(p: Process) {
        if (p !== process) return
        val wasReady = state == State.READY
        if (!wasReady) Log.w(TAG, "Daemon exited before READY: ${lastNoise ?: "no output"}")
        val error = when {
            wasReady -> FreeformProtocol.ERR_DAEMON_LOST
            // Root was granted, but this system lacks an API the daemon needs.
            lastNoise?.startsWith("INIT_FAILED") == true -> FreeformProtocol.ERR_FAILED
            else -> FreeformProtocol.ERR_ROOT_DENIED
        }
        detach(p, error = error, notifyLost = wasReady)
    }

    /** Forgets [p]: fails what was waiting on it and, if it was needed, reports the loss. */
    private fun detach(p: Process, error: String, notifyLost: Boolean) {
        if (p !== process) return
        process = null
        stdin = null
        main.removeCallbacks(startTimeout)
        val wasStarting = state == State.STARTING
        state = State.STOPPED
        if (wasStarting) finishStart(error)
        val failed = calls.values.toList()
        calls.clear()
        for (call in failed) {
            main.removeCallbacks(call.timeout)
            call.callback(Reply(0, ok = false, code = error, args = emptyList()))
        }
        if (notifyLost && keepAlive) listener?.onDaemonLost()
    }

    private fun finishStart(error: String?) {
        if (error != null && state == State.STARTING) state = State.STOPPED
        val waiters = startWaiters.toList()
        startWaiters.clear()
        waiters.forEach { it(error) }
    }

    private fun write(line: String): Boolean {
        val out = stdin ?: return false
        return try {
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } catch (e: IOException) {
            Log.w(TAG, "Daemon pipe closed", e)
            false
        }
    }

    private fun kill() {
        val p = process
        if (p != null) {
            p.destroy()
            detach(p, FreeformProtocol.ERR_ROOT_DENIED, notifyLost = false)
        } else {
            state = State.STOPPED
        }
    }

    private fun scheduleIdleStop() {
        main.removeCallbacks(idleStop)
        if (!keepAlive && state != State.STOPPED) main.postDelayed(idleStop, IDLE_STOP_MS)
    }
}
