package io.github.chayanforyou.quickball.localsend

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager

/**
 * Invisible 1×1 activity that exists only to own the focused window for a moment.
 *
 * Since Android 10 only the focused app (or the default IME) may read the clipboard, and an
 * accessibility service is not exempt. Once this window gains focus we read the primary clip,
 * close immediately and hand the text to [LocalSendSender].
 */
class ClipboardSendActivity : Activity() {

    companion object {
        private const val FOCUS_TIMEOUT_MS = 1500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var handled = false
    private val giveUp = Runnable { finishQuietly() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.apply {
            setGravity(Gravity.START or Gravity.TOP)
            attributes = attributes.apply {
                width = 1
                height = 1
                x = 0
                y = 0
            }
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
        handler.postDelayed(giveUp, FOCUS_TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        handler.removeCallbacks(giveUp)

        val text = try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
        } catch (_: Exception) {
            null
        }

        finishQuietly()
        LocalSendSender.send(applicationContext, text)
    }

    override fun onDestroy() {
        handler.removeCallbacks(giveUp)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun finishQuietly() {
        if (isFinishing) return
        finish()
        overridePendingTransition(0, 0)
    }
}
