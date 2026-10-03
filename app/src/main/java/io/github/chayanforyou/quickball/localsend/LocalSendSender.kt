package io.github.chayanforyou.quickball.localsend

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.chayanforyou.quickball.R
import io.github.chayanforyou.quickball.domain.AppPreference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends text to the LocalSend device configured in settings and reports the outcome.
 *
 * LocalSend keeps a message request open until the receiver closes its dialog, so a short
 * "sent" notice is shown once the request has been pending for [ANNOUNCE_DELAY_MS] (meaning
 * the dialog is up on the other side); a later rejection still gets reported.
 */
object LocalSendSender {

    private const val ANNOUNCE_DELAY_MS = 900L

    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "localsend-send").apply { isDaemon = true }
    }

    /** Set by the accessibility service so results appear as QuickBall's overlay toast. */
    @Volatile
    var notifier: ((String) -> Unit)? = null

    fun send(context: Context, text: String?) {
        val app = context.applicationContext
        val target = AppPreference.getInstance(app).localSendTarget
        if (!target.isConfigured) {
            notify(app, app.getString(R.string.localsend_not_configured))
            return
        }
        if (text.isNullOrEmpty()) {
            notify(app, app.getString(R.string.localsend_clipboard_empty))
            return
        }

        val announced = AtomicBoolean(false)
        val announce = Runnable {
            announced.set(true)
            notify(app, app.getString(R.string.localsend_sent, target.displayName))
        }
        main.postDelayed(announce, ANNOUNCE_DELAY_MS)

        executor.execute {
            val result = LocalSendClient(app).sendText(target, text)
            main.post {
                main.removeCallbacks(announce)
                if (result == LocalSendResult.Delivered) {
                    if (!announced.get()) {
                        notify(app, app.getString(R.string.localsend_sent, target.displayName))
                    }
                } else {
                    notify(app, describe(app, result, target))
                }
            }
        }
    }

    /** Runs [block] off the main thread and delivers its value on the main thread. */
    fun <T> runAsync(block: () -> T, onResult: (T) -> Unit) {
        executor.execute {
            val value = block()
            main.post { onResult(value) }
        }
    }

    fun describe(context: Context, result: LocalSendResult, target: LocalSendTarget): String =
        when (result) {
            LocalSendResult.Delivered -> context.getString(R.string.localsend_sent, target.displayName)
            LocalSendResult.Rejected -> context.getString(R.string.localsend_rejected)
            LocalSendResult.PinRequired -> context.getString(R.string.localsend_pin_required)
            LocalSendResult.PinInvalid -> context.getString(R.string.localsend_pin_invalid)
            LocalSendResult.TooManyPinAttempts -> context.getString(R.string.localsend_pin_blocked)
            LocalSendResult.Busy -> context.getString(R.string.localsend_busy)
            LocalSendResult.FingerprintMismatch -> context.getString(R.string.localsend_fingerprint_mismatch)
            is LocalSendResult.Unreachable ->
                context.getString(R.string.localsend_unreachable, "${target.host}:${target.port}")
            is LocalSendResult.HttpError -> context.getString(R.string.localsend_http_error, result.code)
        }

    private fun notify(context: Context, message: String) {
        val sink = notifier
        if (sink != null) sink(message) else Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
