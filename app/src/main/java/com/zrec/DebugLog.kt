package com.zrec

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log de depuração persistente em Download/zrec-debug.log (MediaStore).
 * Legível via: adb shell cat /sdcard/Download/zrec-debug.log
 */
object DebugLog {
    val enabled: Boolean get() = BuildConfig.DEBUG

    private val lock = Any()
    private const val FILE_NAME = "zrec-debug.log"

    @Volatile
    private var logUri: Uri? = null

    /** Recria o arquivo de log (apagando anteriores do mesmo nome). No-op em release. Nunca lança. */
    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            logUri = null
            try {
                val resolver = context.contentResolver
                // Remove versões anteriores (o provider costuma salvar como "zrec-debug.log.txt").
                val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    selection,
                    arrayOf("$FILE_NAME%"),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        resolver.delete(
                            Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()),
                            null,
                            null,
                        )
                    }
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                logUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                logUri?.let { uri ->
                    append(uri, context, "=== zrec ${SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} ===\n")
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                }
            } catch (t: Throwable) {
                // Nunca derruba o app por causa do log de depuração.
                android.util.Log.w("Zrec", "DebugLog.init falhou", t)
                logUri = null
            }
        }
    }

    fun log(context: Context, message: String) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            val uri = logUri ?: return
            append(
                uri,
                context,
                "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}  $message\n",
            )
        }
    }

    private fun append(uri: Uri, context: Context, line: String) {
        try {
            context.contentResolver.openOutputStream(uri, "wa")?.use { out ->
                out.write(line.toByteArray())
            }
        } catch (_: Exception) {
        }
    }
}
