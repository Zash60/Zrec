package com.zrec

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/** Vídeo salvo em Movies/Zrec pelo Zrec. */
data class RecordingInfo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSeconds: Long,
)

object Recordings {

    /** Lista todos os vídeos do Zrec, do mais recente para o mais antigo. */
    fun query(context: Context): List<RecordingInfo> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("Movies/Zrec%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        DebugLog.log(context, "Recordings.query enter selection=$selection args=${selectionArgs.joinToString()}")
        val result = mutableListOf<RecordingInfo>()
        try {
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                DebugLog.log(context, "Recordings.query: ${cursor.count} linhas")
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "gravacao.mp4"
                    DebugLog.log(context, "Recordings.query: linha id=$id name=$name size=${cursor.getLong(sizeCol)} dur=${cursor.getLong(durationCol)} date=${cursor.getLong(dateCol)}")
                    result += RecordingInfo(
                        id = id,
                        uri = Uri.withAppendedPath(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id.toString(),
                        ),
                        name = name,
                        sizeBytes = cursor.getLong(sizeCol),
                        durationMs = cursor.getLong(durationCol),
                        dateAddedSeconds = cursor.getLong(dateCol),
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("Zrec", "Falha ao consultar gravações", e)
            DebugLog.log(context, "Recordings.query FALHOU: $e")
        }
        return result
    }

    /**
     * Exclui uma gravação criada pelo próprio Zrec.
     * Em versões recentes do Android, arquivos próprios não exigem consentimento extra.
     */
    fun delete(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.delete(uri, null, null) > 0
    } catch (e: Exception) {
        Log.e("Zrec", "Falha ao excluir gravação", e)
        false
    }
}
