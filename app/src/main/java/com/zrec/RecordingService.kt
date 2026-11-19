package com.zrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.IntentCompat
import androidx.core.app.NotificationCompat

/**
 * Serviço em primeiro plano (tipo mediaProjection) que mantém a captura ativa
 * com o app em segundo plano e expõe uma notificação com botão "Parar".
 */
class RecordingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var engine: RecordingEngine? = null
    private var finishNotified = false
    private var startedAtMillis: Long = 0L
    private var projection: MediaProjection? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Consentimento revogado pelo usuário: finaliza com segurança.
            DebugLog.log(applicationContext, "projectionCallback.onStop")
            handleStop()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.log(applicationContext, "service.onCreate")
        Settings.init(applicationContext)
        createChannel()
        registerScreenOffReceiver()
        cleanupOrphanTempFiles()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLog.log(applicationContext, "service.onStartCommand action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> {
                DebugLog.log(applicationContext, "ação nula/desconhecida — stopSelf")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+: prazo do serviço expirou — encerra limpo.
        DebugLog.log(applicationContext, "service.onTimeout startId=$startId fgsType=$fgsType")
        handleStop()
    }

    override fun onDestroy() {
        // Se a sessão de projeção foi encerrada pelo sistema (ex.: tela bloqueada,
        // "Parar de compartilhar" na status bar), o Android destrói este FGS sem
        // finalizar a gravação. Nesse caso, recupera o estado da UI e descarta o
        // arquivo pendente para não deixar MP4 corrompido invisível no armazenamento.
        DebugLog.log(applicationContext, "service.onDestroy finishNotified=$finishNotified engine=${engine != null}")
        val current = engine
        engine = null
        if (!finishNotified) {
            if (current != null) {
                Thread({ current.forceFinish() }, "zrec-emergency-stop").start()
            } else {
                RecordingState.failed(getString(R.string.recording_interrupted))
            }
        }
        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        projection = null
        unregisterScreenOffReceiver()
        super.onDestroy()
    }

    private fun handleStart(intent: Intent) {
        DebugLog.log(applicationContext, "handleStart enter engine=${engine != null}")
        if (engine != null) return // já gravando — ignora STARTs duplicados

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            DebugLog.log(applicationContext, "handleStart abort: resultCode=$resultCode data=${data != null}")
            RecordingState.failed(getString(R.string.capture_denied))
            stopSelf()
            return
        }

        startedAtMillis = System.currentTimeMillis()
        val settings = Settings.snapshot()

        // Android 14+: startForeground com o tipo ANTES de obter o MediaProjection.
        val notification = buildNotification(startedAtMillis, settings)
        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            DebugLog.log(applicationContext, "startForeground ok (id=$NOTIFICATION_ID)")
        } catch (e: Exception) {
            DebugLog.log(applicationContext, "startForeground falhou: $e")
            RecordingState.failed(getString(R.string.service_start_failed))
            stopSelf()
            return
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = try {
            projectionManager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            DebugLog.log(applicationContext, "getMediaProjection lançou: $e")
            null
        }
        if (newProjection == null) {
            DebugLog.log(applicationContext, "getMediaProjection retornou null")
            RecordingState.failed(getString(R.string.capture_invalid))
            stopSelf()
            return
        }
        DebugLog.log(applicationContext, "projection obtida")
        newProjection.registerCallback(projectionCallback, handler)
        projection = newProjection

        val (width, height, dpi) = displayInfo()
        val newEngine = RecordingEngine(applicationContext, newProjection, width, height, dpi, settings)
        newEngine.onFinished = { _, error, name ->
            DebugLog.log(applicationContext, "onFinished error=$error name=$name")
            vibrate()
            try {
                newProjection.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }
            try {
                newProjection.stop()
            } catch (_: Exception) {
            }
            projection = null
            finishNotified = true
            if (error != null) {
                RecordingState.failed(error)
            } else {
                RecordingState.finished(name)
            }
            engine = null
            stopSelf()
        }
        engine = newEngine
        // engine.start() trata erros internamente e atualiza o estado via onFinished.
        val ok = newEngine.start()
        DebugLog.log(applicationContext, "engine.start() retornou ok=$ok")
        if (ok) {
            RecordingState.started(startedAtMillis)
            vibrate()
            DebugLog.log(applicationContext, "handleStart completo — GRAVANDO")
        }
    }

    private fun handleStop() {
        DebugLog.log(applicationContext, "handleStop engine=${engine != null}")
        val current = engine ?: run {
            DebugLog.log(applicationContext, "handleStop sem engine — stopSelf")
            stopSelf()
            return
        }
        // stop() bloqueia (joins com limite + finalização do MP4): fora da main thread.
        // engine não é nulo até onFinished: assim, se o sistema destruir o FGS no meio,
        // o caminho de emergência do onDestroy consegue agir.
        Thread({ current.stop() }, "zrec-stop").start()
    }

    /** Vibração curta ao iniciar/parar a gravação (se ativada nas Configurações). */
    private fun vibrate() {
        if (!Settings.snapshot().vibrateOnRecordActions) return
        try {
            val vibrator = applicationContext.getSystemService(android.os.Vibrator::class.java) ?: return
            vibrator.vibrate(
                android.os.VibrationEffect.createOneShot(
                    VIBRATE_MS,
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                ),
            )
        } catch (e: Exception) {
            DebugLog.log(applicationContext, "vibrate falhou: $e")
        }
    }

    /**
     * Dimensões reais da tela no instante da gravação, já considerando a rotação
     * atual (paisagem troca largura/altura). currentWindowMetrics de um contexto de
     * serviço ignora a rotação e sempre devolve o tamanho "natural" — por isso o
     * VirtualDisplay precisa ser calculado a partir de Display.mode + Display.rotation.
     * Sem isso, o espelho rotaciona o conteúdo para caber e o vídeo sai deitado.
     */
    private fun displayInfo(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        // Contexto de serviço não é associado a nenhum display: context.display lançaria
        // UnsupportedOperationException. defaultDisplay funciona (e reflete a rotação atual).
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        val mode = display.mode // tamanho físico na orientação natural
        var width = mode.physicalWidth
        var height = mode.physicalHeight
        @Suppress("DEPRECATION")
        val rotation = display.rotation
        if (rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270) {
            val tmp = width
            width = height
            height = tmp
        }
        // Encoders H.264 preferem dimensões pares.
        width -= width % 2
        height -= height % 2
        val dpi = resources.configuration.densityDpi
        return Triple(width, height, dpi)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Detecta o desligamento da tela (SCREEN_OFF). No Android 15+ o sistema pode
     * encerrar a sessão de MediaProjection ao bloquear a tela ("auto-stop on lock")
     * sem entregar o callback onStop(), deixando o FGS como um "zumbi" e um arquivo
     * temporario orfao no cache. Ao detectar o desligamento, finaliza a gravacao
     * limpo para salvar o MP4 ate aquele ponto.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            DebugLog.log(applicationContext, "screenOffReceiver onReceive action=$action")
            if (Intent.ACTION_SCREEN_OFF == action) {
                handleStop()
            }
        }
    }

    private fun registerScreenOffReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_EXPORTED)
            DebugLog.log(applicationContext, "screenOffReceiver registrado")
        } catch (e: Exception) {
            DebugLog.log(applicationContext, "registerScreenOffReceiver falhou: $e")
        }
    }

    private fun unregisterScreenOffReceiver() {
        try {
            unregisterReceiver(screenOffReceiver)
            DebugLog.log(applicationContext, "screenOffReceiver desregistrado")
        } catch (e: Exception) {
            // Pode ja ter sido desregistrado.
            DebugLog.log(applicationContext, "unregisterScreenOffReceiver falhou: $e")
        }
    }

    /** Remove arquivos temporarios de gravacao anteriores que ficaram orfaos (ex.: apos travamento/bloqueio). */
    private fun cleanupOrphanTempFiles() {
        try {
            val files = applicationContext.cacheDir.listFiles() ?: emptyArray()
            var removed = 0
            for (file in files) {
                if (file.name.startsWith("zrec_") && file.name.endsWith(".tmp.mp4")) {
                    DebugLog.log(
                        applicationContext,
                        "cleanupOrphanTempFiles: removendo ${file.name} (${file.length()} bytes)",
                    )
                    if (file.delete()) removed++
                }
            }
            if (removed > 0) {
                DebugLog.log(applicationContext, "cleanupOrphanTempFiles: $removed arquivo(s) removido(s)")
            }
        } catch (e: Exception) {
            DebugLog.log(applicationContext, "cleanupOrphanTempFiles falhou: $e")
        }
    }

    private fun buildNotification(startedAtMillis: Long, settings: ZrecSettings): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = android.app.PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(getString(R.string.notification_content_title))
            .setContentText(if (settings.audioInternal) getString(R.string.notification_content_audio) else getString(R.string.notification_content_noaudio))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setWhen(startedAtMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_stop_action), stopPendingIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.zrec.action.START"
        const val ACTION_STOP = "com.zrec.action.STOP"
        const val EXTRA_RESULT_CODE = "com.zrec.extra.RESULT_CODE"
        const val EXTRA_DATA = "com.zrec.extra.DATA"

        private const val CHANNEL_ID = "zrec_recording"
        private const val NOTIFICATION_ID = 4123
        private const val STOP_REQUEST_CODE = 1001
        private const val VIBRATE_MS = 70L
    }
}
