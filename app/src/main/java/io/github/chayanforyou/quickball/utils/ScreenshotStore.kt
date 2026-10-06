package io.github.chayanforyou.quickball.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Crops a screenshot and writes it to the gallery or to a shareable cache file. */
@RequiresApi(Build.VERSION_CODES.R)
object ScreenshotStore {

    private const val CACHE_DIR = "shots"
    private const val CACHE_MAX_AGE_MS = 60 * 60 * 1000L

    /** Saves [rect] of [shot] to Pictures/Screenshots and returns its MediaStore uri. */
    @WorkerThread
    fun saveToGallery(context: Context, shot: Bitmap, rect: Rect): Uri {
        val cropped = crop(shot, rect)
        try {
            val resolver = context.contentResolver
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(now))
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + "Screenshots"
                )
                put(MediaStore.MediaColumns.DATE_TAKEN, now)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
            try {
                val stream = resolver.openOutputStream(uri) ?: throw IOException("No output stream")
                stream.use {
                    if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                        throw IOException("PNG encoding failed")
                    }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } finally {
            cropped.recycle()
        }
    }

    /** Writes [rect] of [shot] to the app cache and returns a FileProvider uri for it. */
    @WorkerThread
    fun saveToCache(context: Context, shot: Bitmap, rect: Rect): Uri {
        val cropped = crop(shot, rect)
        try {
            val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
            // Old files may still be read by the app they were shared to; drop only stale ones.
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { if (now - it.lastModified() > CACHE_MAX_AGE_MS) it.delete() }

            val file = File(dir, fileName(now))
            FileOutputStream(file).use {
                if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    throw IOException("PNG encoding failed")
                }
            }
            return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        } finally {
            cropped.recycle()
        }
    }

    /** Returns a new bitmap the caller owns (and recycles); [shot] itself is never recycled. */
    private fun crop(shot: Bitmap, rect: Rect): Bitmap {
        // A hardware bitmap cannot be read directly; copy it to memory once, then cut the region.
        val source = if (shot.config == Bitmap.Config.HARDWARE) {
            shot.copy(Bitmap.Config.ARGB_8888, false) ?: throw IOException("Bitmap copy failed")
        } else {
            shot
        }
        val l = rect.left.coerceIn(0, source.width - 1)
        val t = rect.top.coerceIn(0, source.height - 1)
        val w = rect.width().coerceIn(1, source.width - l)
        val h = rect.height().coerceIn(1, source.height - t)
        // createBitmap hands back the source itself when the region is the whole image.
        val out = Bitmap.createBitmap(source, l, t, w, h)
        return when {
            out !== source -> {
                if (source !== shot) source.recycle()
                out
            }
            source !== shot -> out
            else -> out.copy(Bitmap.Config.ARGB_8888, false) ?: throw IOException("Bitmap copy failed")
        }
    }

    private fun fileName(time: Long): String =
        "Screenshot_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(time)) + "_QuickBall.png"
}
