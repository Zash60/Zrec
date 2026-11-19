package com.zrec

import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pipeline de captura de tela + áudio INTERNO em um único MP4:
 *
 *   MediaProjection ──► VirtualDisplay ──► MediaCodec(H.264, superfície) ─┐
 *   MediaProjection ──► AudioPlaybackCaptureConfiguration ──► AudioRecord  ┤
 *                                                                          ▼
 *   AudioRecord ──► MediaCodec(AAC) ──────────────────────────────► MediaMuxer ──► MediaStore
 *
 * O microfone NÃO é utilizado em nenhuma etapa.
 */
class RecordingEngine(
    private val context: Context,
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val settings: ZrecSettings,
) {

    /** (uri do arquivo salvo, mensagem de erro, nome de exibição) — exatamente um dos dois primeiros não nulos. */
    var onFinished: ((Uri?, String?, String?) -> Unit)? = null

    private val contentResolver = context.contentResolver

    @Volatile
    private var stopRequested = false

    /** Garante que [finishAndNotify] execute exatamente uma vez. */
    private val finishLock = Any()

    @Volatile
    private var finishStarted = false

    private var outputName: String? = null
    private var pfd: ParcelFileDescriptor? = null

    private val muxerLock = Any()
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    /** true quando o caminho MediaRecorder (vídeo-only) está em uso. */
    private var usingMediaRecorder = false
    private var mediaRecorderFailed = false

    private var videoThread: Thread? = null
    private var audioDrainThread: Thread? = null
    private var audioFeedThread: Thread? = null

    // PTS monotônico por faixa, normalizado a partir do PTS do encoder menos o do 1º frame.
    private var samplesWrittenVideo = 0
    private var samplesWrittenAudio = 0
    private var samplesDropped = 0
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L

    /** Configuração de áudio efetivamente usada (pode diferir da preferida). */
    private var audioSampleRate = settings.audioSampleRate.hz
    private var audioChannelMask = if (settings.audioChannels == AudioChannels.MONO) {
        AudioFormat.CHANNEL_IN_MONO
    } else {
        AUDIO_CHANNEL_MASK
    }

    /** true quando o encoder AAC + AudioRecord de playback foram criados com sucesso. */
    private var audioCaptured = false

    /**
     * Dimensões base da gravação, conforme Orientação + Resolução nas Configurações:
     * Automático => as da tela, sem mexer em nada.
     * Retrato/Paisagem forçados => a resolução exata do preset de Resolução para o eixo
     * escolhido (ex.: Paisagem + 1080p => 1920x1080). O conteúdo NUNCA gira: o espelho
     * do sistema encaixa a tela no eixo com barras pretas quando não cabe.
     * Com Resolução=Automática no eixo forçado, usa as dimensões nativas do eixo.
     */
    private fun outputAxes(): Pair<Int, Int> {
        val nativePortrait = height >= width
        fun nativeFor(portrait: Boolean) =
            if (portrait == nativePortrait) width to height else height to width
        return when (settings.orientation) {
            VideoOrientation.AUTO -> width to height
            VideoOrientation.PORTRAIT -> when (settings.videoResolution) {
                VideoResolution.NATIVE -> nativeFor(true)
                VideoResolution.P1080 -> 1080 to 1920
                VideoResolution.P720 -> 720 to 1280
                VideoResolution.P480 -> 480 to 854
            }
            VideoOrientation.LANDSCAPE -> when (settings.videoResolution) {
                VideoResolution.NATIVE -> nativeFor(false)
                VideoResolution.P1080 -> 1920 to 1080
                VideoResolution.P720 -> 1280 to 720
                VideoResolution.P480 -> 854 to 480
            }
        }
    }

    /** Retorna true se a gravação começou com sucesso; false se falhou (estado já atualizado via onFinished). */
    fun start(): Boolean {        DebugLog.log(
            context,
            "engine.start enter ${width}x${height}@${densityDpi}dpi " +
                "settings res=${settings.videoResolution} bitrate=${settings.videoBitrate.bps} " +
                "fps=${settings.frameRate.fps} orientação=${settings.orientation} " +
                "iFrame=${settings.iFrameInterval.seconds}s audio=${settings.audioInternal} " +
                "aac=${settings.audioBitrate.bps} ${settings.audioChannels} ${settings.audioSampleRate}",
        )
        return try {
            createOutput()
            DebugLog.log(context, "engine.createOutput ok name=$outputName")
            // Áudio primeiro: o build do AudioRecord de captura de playback é a parte
            // mais frágil em ROMs Xiaomi. Se o aparelho recusar, sobra vídeo-sem-áudio
            // com aviso claro na UI (nunca usamos o microfone como fallback).
            // Se o usuário desativou o áudio interno nas Configurações, pula direto
            // para o caminho de vídeo (também sem aviso: foi escolha explícita).
            val audioOk = if (settings.audioInternal) {
                try {
                    setupAudio()
                    DebugLog.log(context, "engine.setupAudio ok (record state=${audioRecord?.state})")
                    true
                } catch (t: Throwable) {
                    DebugLog.log(context, "engine.setupAudio indisponível: $t — seguindo sem áudio interno")
                    Log.w(TAG, "Áudio interno indisponível; gravando só vídeo", t)
                    RecordingState.warn(
                        context.getString(R.string.audio_unavailable_warn)
                    )
                    false
                }
            } else {
                DebugLog.log(context, "engine.setupAudio pulado (áudio interno desativado nas Configurações)")
                false
            }
            audioCaptured = audioOk
            if (audioOk) {
                // Caminho manual (vídeo + áudio interno): cria o muxer aqui, com o fd no offset 0.
                muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                setupVideo()
                DebugLog.log(context, "engine.setupVideo ok (caminho manual)")
                videoThread = Thread({ drainVideo() }, "zrec-video-drain").apply { start() }
                if (audioOk) {
                    audioDrainThread = Thread({ drainAudio() }, "zrec-audio-drain").apply { start() }
                    audioFeedThread = Thread({ feedAudio() }, "zrec-audio-feed").apply { start() }
                }
                DebugLog.log(context, "engine threads iniciadas (áudio=$audioOk)")
            } else {
                startMediaRecorder()
                DebugLog.log(context, "MediaRecorder iniciado (vídeo sem áudio)")
            }
            true
        } catch (t: Throwable) {
            DebugLog.log(context, "engine.start falhou: $t")
            Log.e(TAG, "Falha ao iniciar gravação", t)
            finishAndNotify(t.message ?: context.getString(R.string.start_failed))
            false
        }
    }

    /**
     * Bloqueante com limites rígidos: finaliza todos os encodes e fecha o arquivo.
     * Sempre termina chamando [onFinished], mesmo em caso de erro inesperado.
     */
    fun stop() {
        DebugLog.log(context, "engine.stop enter usingMediaRecorder=$usingMediaRecorder")
        if (stopRequested) {
            DebugLog.log(context, "engine.stop já solicitado — retornando")
            return
        }
        stopRequested = true
        if (usingMediaRecorder) {
            try {
                stopMediaRecorder()
            } catch (t: Throwable) {
                DebugLog.log(context, "engine.stop exceção no caminho MediaRecorder: $t")
                Log.e(TAG, "Erro inesperado em stop()", t)
            } finally {
                finishAndNotify(if (mediaRecorderFailed) context.getString(R.string.no_media_captured) else null)
            }
            return
        }
        try {
            // 1) Libera a VirtualDisplay primeiro: para de produzir frames para o encoder.
            try {
                virtualDisplay?.release()
            } catch (e: Exception) {
                Log.w(TAG, "virtualDisplay.release() falhou", e)
            }
            virtualDisplay = null
            DebugLog.log(context, "engine.stop: virtualDisplay liberada")

            // 2) Para a captura de áudio de forma limitada (MIUI pode travar essa chamada):
            //    roda em outra thread e espera no máximo 1,5s.
            stopAudioRecordBounded()
            DebugLog.log(context, "engine.stop: audioRecord parado")

            // 3) EOS no encoder de vídeo.
            try {
                videoEncoder?.signalEndOfInputStream()
            } catch (e: Exception) {
                Log.w(TAG, "signalEndOfInputStream falhou", e)
            }
            DebugLog.log(context, "engine.stop: EOS sinalizado")

            try {
                audioFeedThread?.join(FEED_JOIN_TIMEOUT_MS)
                DebugLog.log(context, "engine.stop: feed join ok")
                videoThread?.join(DRAIN_JOIN_TIMEOUT_MS)
                DebugLog.log(context, "engine.stop: video join ok")
                audioDrainThread?.join(DRAIN_JOIN_TIMEOUT_MS)
                DebugLog.log(context, "engine.stop: audio drain join ok")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        } catch (t: Throwable) {
            DebugLog.log(context, "engine.stop exceção: $t")
            Log.e(TAG, "Erro inesperado em stop()", t)
        } finally {
            finishAndNotify(null)
        }
    }

    /**
     * Caminho de emergência usado pelo serviço quando o Android encerra a sessão
     * de projeção ou destrói o FGS sem finalizar a gravação: descarta o arquivo
     * pendente para não deixar um MP4 corrompido invisível no MediaStore.
     */
    fun forceFinish() {
        // Libera a VirtualDisplay para parar o fluxo de frames antes de finalizar.
        // finishAndNotify garante execução única mesmo se stop() estiver em andamento.
        DebugLog.log(context, "engine.forceFinish enter usingMediaRecorder=$usingMediaRecorder")
        stopRequested = true
        if (usingMediaRecorder) {
            try {
                stopMediaRecorder()
            } catch (_: Exception) {
            }
            // Se o MediaRecorder finalizou o MP4 corretamente, salva o que foi capturado.
            if (!mediaRecorderFailed) {
                DebugLog.log(context, "engine.forceFinish: MediaRecorder finalizou — salvando arquivo")
                finishAndNotify(null)
            } else {
                finishAndNotify(context.getString(R.string.recording_interrupted))
            }
            return
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        finishAndNotify(context.getString(R.string.recording_interrupted))
    }

    private fun stopAudioRecordBounded() {
        val record = audioRecord ?: return
        val worker = Thread({
            try {
                record.stop()
            } catch (e: Exception) {
                Log.w(TAG, "audioRecord.stop() falhou", e)
            }
        }, "zrec-audio-stop")
        try {
            worker.start()
            worker.join(AUDIO_STOP_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ------------------------------------------------------------------ setup

    private var tempFile: File? = null

    private fun createOutput() {
        // Grava em arquivo privado do app (POSIX nativo) e só no final copia para o
        // MediaStore. FUSE/FD do MediaStore corrompe a finalização nesse aparelho.
        val file = File(context.cacheDir, "zrec_${System.currentTimeMillis()}.tmp.mp4")
        tempFile = file
        outputName = "Zrec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        pfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
        // O MediaMuxer é criado apenas no caminho manual (vídeo+áudio interno),
        // com o fd no offset 0, antes de qualquer outra escrita.
    }

    /**
     * Backend MediaRecorder (vídeo apenas, sem áudio interno): caminho à prova de falhas
     * usado quando o aparelho recusa a captura de playback. Ideal para ROMs travadas.
     */
    private fun startMediaRecorder() {
        val descriptor = pfd?.fileDescriptor
            ?: throw IllegalStateException(context.getString(R.string.output_not_open))
        val (encW, encH, codecName) = resolveVideoTarget()
        val videoBitrate = clampBitrate(settings.videoBitrate.bps, codecName)
        DebugLog.log(context, "engine.startMediaRecorder: ${encW}x${encH} bitrate=$videoBitrate")
        val rec = MediaRecorder(context)
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)

        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setVideoSize(encW, encH)
        rec.setVideoFrameRate(settings.frameRate.fps)
        rec.setVideoEncodingBitRate(videoBitrate)

        rec.setOutputFile(descriptor)
        rec.prepare()
        usingMediaRecorder = true
        virtualDisplay = projection.createVirtualDisplay(
            "ZrecCapture",
            encW,
            encH,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            rec.surface,
            /* callback = */ null,
            /* handler = */ null,
        )
        rec.start()
        recorder = rec
    }

    private fun stopMediaRecorder() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "virtualDisplay.release() falhou", e)
        }
        virtualDisplay = null
        val rec = recorder
        if (rec != null) {
            try {
                rec.stop()
                DebugLog.log(context, "MediaRecorder.stop ok")
            } catch (e: Exception) {
                DebugLog.log(context, "MediaRecorder.stop FALHOU: $e")
                Log.w(TAG, "MediaRecorder.stop falhou", e)
                mediaRecorderFailed = true
            }
            try {
                rec.reset()
            } catch (_: Exception) {
            }
            rec.release()
            recorder = null
        }
    }

    private fun setupVideo() {
        val (encW, encH, codecName) = resolveVideoTarget()
        DebugLog.log(context, "engine.setupVideo: ${encW}x${encH} via ${codecName ?: "default"}")
        codecName?.let { name ->
            runCatching {
                val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .firstOrNull { it.name == name }
                val caps = info?.getCapabilitiesForType(MIME_VIDEO)
                DebugLog.log(
                    context,
                    "engine.caps($name): colorFormats=${caps?.colorFormats?.joinToString()} profileLevels=${caps?.profileLevels?.joinToString { "P${it.profile}/L${it.level}" }}",
                )
            }
        }
        val videoBitrate = clampBitrate(settings.videoBitrate.bps, codecName)
        val format = MediaFormat.createVideoFormat(MIME_VIDEO, encW, encH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.frameRate.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, settings.iFrameInterval.seconds)
        }
        DebugLog.log(
            context,
            "engine.setupVideo: formato ${encW}x${encH} bitrate=$videoBitrate fps=${settings.frameRate.fps} iFrame=${settings.iFrameInterval.seconds}s",
        )
        val encoder = if (codecName != null) {
            MediaCodec.createByCodecName(codecName)
        } else {
            MediaCodec.createEncoderByType(MIME_VIDEO)
        }
        DebugLog.log(context, "engine.setupVideo: encoder ${encoder.name} criado")
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        DebugLog.log(context, "engine.setupVideo: encoder configurado (surface=$encW x $encH)")
        val inputSurface = encoder.createInputSurface()
        DebugLog.log(context, "engine.setupVideo: inputSurface criada")
        // A VirtualDisplay reflete o eixo escolhido em Configurações; o conteúdo que
        // não cabe no eixo é encaixado pelo sistema com barras pretas (letterbox),
        // sem girar e sem deformar a imagem.
        virtualDisplay = projection.createVirtualDisplay(
            "ZrecCapture",
            encW,
            encH,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            /* callback = */ null,
            /* handler = */ null,
        )
        DebugLog.log(context, "engine.setupVideo: virtualDisplay criada (${encW}x${encH})")
        encoder.start()
        DebugLog.log(context, "engine.setupVideo: encoder iniciado")
        videoEncoder = encoder
    }

    /**
     * Escolhe o tamanho e o encoder H.264 compatíveis com o aparelho.
     * Aplica primeiro o limite de resolução das Configurações (lado maior do vídeo)
     * e depois o limite de tamanho do encoder (ex.: 1088 no goldfish): se nem o
     * tamanho nativo nem o reduzido cabem, reduz mantendo a proporção.
     */
    private fun resolveVideoTarget(): Triple<Int, Int, String?> {
        // Base: tamanho escolhido na Orientação (tela como está / 9:16 / 16:9),
        // reduzido pelo preset de resolução escolhido. Encoders H.264 exigem
        // dimensões pares — arredonda para o PAR MAIS PRÓXIMO.
        fun Int.toEven(): Int = (this + this % 2).coerceAtLeast(2)
        val (forcedW, forcedH) = outputAxes()
        var baseW = forcedW
        var baseH = forcedH
        settings.videoResolution.maxLongSide?.let { cap ->
            val longSide = maxOf(baseW, baseH)
            if (longSide > cap) {
                val scale = cap.toFloat() / longSide
                baseW = (baseW * scale).toInt().toEven()
                baseH = (baseH * scale).toInt().toEven()
                DebugLog.log(
                    context,
                    "engine.resolveVideoTarget: preset ${settings.videoResolution} — alvo ${forcedW}x${forcedH} -> ${baseW}x${baseH}",
                )
            }
        }

        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encoder = list.codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MIME_VIDEO, ignoreCase = true) }
        } ?: return Triple(baseW, baseH, null)

        val capabilities = runCatching {
            encoder.getCapabilitiesForType(MIME_VIDEO).videoCapabilities
        }.getOrNull() ?: return Triple(baseW, baseH, encoder.name)

        if (capabilities.isSizeSupported(baseW, baseH)) {
            return Triple(baseW, baseH, encoder.name)
        }
        // Reduz mantendo a proporção até o tamanho caber nos limites do encoder.
        val maxW = capabilities.supportedWidths.upper
        val maxH = capabilities.supportedHeights.upper
        var w = baseW
        var h = baseH
        val scale = minOf(1f, maxW.toFloat() / baseW, maxH.toFloat() / baseH)
        if (scale < 1f) {
            w = (baseW * scale).toInt().toEven()
            h = (baseH * scale).toInt().toEven()
        }
        // Garante par (encoders H.264 exigem 2x2) e tenta caber nos limites.
        while (!capabilities.isSizeSupported(w, h) && w >= 16 && h >= 16) {
            w -= 32
            h -= 32
            w = w and 0x7FFFFFFE
            h = h and 0x7FFFFFFE
        }
        DebugLog.log(context, "engine.resolveVideoTarget: nativo ${width}x${height} -> encoder ${w}x${h} (limite ${maxW}x${maxH})")
        return Triple(w, h, encoder.name)
    }

    /**
     * Limita a taxa de bits escolhida às capacidades reais do encoder, para que
     * "Máxima" em um aparelho com limite inferior não faça configure() lançar.
     */
    private fun clampBitrate(requested: Int, codecName: String?): Int {
        if (codecName == null) return requested
        return runCatching {
            val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.name == codecName }
            val range = info?.getCapabilitiesForType(MIME_VIDEO)?.videoCapabilities?.bitrateRange
            if (range != null) requested.coerceIn(range.lower, range.upper) else requested
        }.getOrElse { requested }
    }

    private fun setupAudio() {
        val attempts = buildAttempts()

        var record: AudioRecord? = null
        var chosen: Attempt? = null
        var lastError: Throwable? = null
        for (attempt in attempts) {
            try {
                val candidate = buildPlaybackRecord(attempt)
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    candidate.startRecording()
                    record = candidate
                    chosen = attempt
                    DebugLog.log(context, "setupAudio: OK com [${attempt.label}]")
                    break
                }
                DebugLog.log(context, "setupAudio: tentativa [${attempt.label}] state=${candidate.state}")
                candidate.release()
            } catch (t: Throwable) {
                lastError = t
                DebugLog.log(context, "setupAudio: tentativa [${attempt.label}] falhou: $t")
            }
        }
        if (record == null || chosen == null) {
            throw IOException(
                "Áudio interno indisponível neste aparelho (a política de áudio recusou a captura)",
                lastError,
            )
        }
        audioRecord = record
        audioSampleRate = chosen.sampleRate
        audioChannelMask = chosen.channelMask

        // Encoder configurado com a MESMA taxa/canais da captura obtida.
        val channelCount = if (chosen.channelMask == AudioFormat.CHANNEL_IN_MONO) 1 else 2
        val format = MediaFormat.createAudioFormat(MIME_AUDIO, chosen.sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, settings.audioBitrate.bps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_AUDIO_INPUT_SIZE)
        }
        val encoder = MediaCodec.createEncoderByType(MIME_AUDIO)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        audioEncoder = encoder
    }

    /**
     * Monta as tentativas de captura na ordem: a preferência das Configurações primeiro
     * e combinações alternativas depois, sempre com o conjunto de usages amplo e, por
     * fim, subconjuntos reduzidos. Nenhuma combinação usa o microfone.
     */
    private fun buildAttempts(): List<Attempt> {
        fun rateLabel(rate: Int) = if (rate == 44_100) "44.1k" else "${rate / 1000}k"
        fun channelLabel(mask: Int) = if (mask == AUDIO_CHANNEL_MASK) "stereo" else "mono"
        val preferredRate = settings.audioSampleRate.hz
        val rates = listOf(preferredRate) + listOf(48_000, 44_100).filter { it != preferredRate }
        val preferredMask = if (settings.audioChannels == AudioChannels.MONO) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AUDIO_CHANNEL_MASK
        }
        val otherMask = if (preferredMask == AUDIO_CHANNEL_MASK) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AUDIO_CHANNEL_MASK
        }
        val masks = listOf(preferredMask, otherMask)
        val attempts = mutableListOf<Attempt>()
        for (rate in rates) {
            for (mask in masks) {
                attempts += Attempt(
                    rate,
                    mask,
                    listOf(1, 2, 3),
                    "media+game+unknown ${channelLabel(mask)} ${rateLabel(rate)}",
                )
            }
        }
        // Uso reduzido apenas para a combinação preferida (compatibilidade extra).
        attempts += Attempt(preferredRate, preferredMask, listOf(1), "media ${channelLabel(preferredMask)} ${rateLabel(preferredRate)}")
        attempts += Attempt(preferredRate, preferredMask, listOf(1, 2), "media+game ${channelLabel(preferredMask)} ${rateLabel(preferredRate)}")
        attempts += Attempt(preferredRate, otherMask, listOf(1), "media ${channelLabel(otherMask)} ${rateLabel(preferredRate)}")
        return attempts
    }

    private fun buildPlaybackRecord(attempt: Attempt): AudioRecord {
        val usages = attempt.usages.map { usage ->
            when (usage) {
                1 -> AudioAttributes.USAGE_MEDIA
                2 -> AudioAttributes.USAGE_GAME
                else -> AudioAttributes.USAGE_UNKNOWN
            }
        }
        val captureBuilder = AudioPlaybackCaptureConfiguration.Builder(projection)
        usages.forEach { captureBuilder.addMatchingUsage(it) }
        val captureConfig = captureBuilder.build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(attempt.sampleRate)
            .setChannelMask(attempt.channelMask)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(attempt.sampleRate, attempt.channelMask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(maxOf(minBuffer, MIN_AUDIO_BUFFER_BYTES) * BUFFER_MULTIPLIER)
            .build()
    }

    private data class Attempt(
        val sampleRate: Int,
        val channelMask: Int,
        val usages: List<Int>,
        val label: String,
    )

    // ------------------------------------------------------------- pump loops

    /** Alimenta o encoder AAC a partir do AudioRecord de captura de playback. */
    private fun feedAudio() {
        val record = audioRecord ?: return
        val encoder = audioEncoder ?: return
        val buffer = ByteArray(AUDIO_CHUNK_BYTES)
        val bytesPerFrame = (if (audioChannelMask == AudioFormat.CHANNEL_IN_MONO) 1 else 2) * 2
        var queuedFrames = 0L
        try {
            while (!stopRequested) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    // read() bloqueado ou erro transitório; ao parar o record, read retorna e o laço sai.
                    Thread.sleep(POLL_SLEEP_MS)
                    continue
                }
                val index = encoder.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    val input = encoder.getInputBuffer(index)
                    if (input != null) {
                        input.clear()
                        input.put(buffer, 0, read)
                        val pts = queuedFrames * MICROS_PER_SECOND / audioSampleRate
                        encoder.queueInputBuffer(index, 0, read, pts, 0)
                        queuedFrames += read / bytesPerFrame
                    }
                }
                // Se o encoder estiver ocupado, o chunk é descartado e o relógio PCM não avança:
                // o áudio fica rente ao A/V e sem corrupção de frames parciais.
            }
            val index = encoder.dequeueInputBuffer(EOS_INPUT_TIMEOUT_US)
            if (index >= 0) {
                val pts = queuedFrames * MICROS_PER_SECOND / audioSampleRate
                encoder.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Falha no laço de alimentação de áudio", t)
        }
    }

    private fun drainVideo() {
        val encoder = videoEncoder ?: return
        val info = MediaCodec.BufferInfo()
        var emptyPolls = 0
        try {
            outer@
            while (true) {
                when (val index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        emptyPolls = 0
                        addTrackLocked { current ->
                            videoTrack = current.addTrack(encoder.outputFormat)
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (stopRequested) {
                            emptyPolls++
                            if (emptyPolls >= EMPTY_POLLS_TO_EXIT) break@outer
                        }
                    }
                    else -> {
                        emptyPolls = 0
                        if (index >= 0) {
                            val buffer = encoder.getOutputBuffer(index)
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (buffer != null && !isConfig && info.size > 0) {
                                val pts = monotonicVideoPtsUs(info.presentationTimeUs)
                                if (DebugLog.enabled && samplesWrittenVideo + samplesDropped < 8) {
                                    DebugLog.log(
                                        context,
                                        "drainVideo: amostra#$samplesWrittenVideo pts=$pts flags=${info.flags} size=${info.size} track=$videoTrack",
                                    )
                                }
                                try {
                                    writeSample(buffer, info, videoTrack, pts)
                                } catch (t: Throwable) {
                                    samplesDropped++
                                    if (samplesDropped <= 5) {
                                        DebugLog.log(
                                            context,
                                            "drainVideo: writeSample FALHA#$samplesDropped pts=$pts flags=${info.flags} size=${info.size} track=$videoTrack: $t",
                                        )
                                    }
                                }
                            }
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(index, false)
                            if (eos) {
                                DebugLog.log(context, "drain: EOS recebido")
                                break@outer
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Falha no laço de vídeo", t)
            DebugLog.log(context, "drainVideo exceção fatal: $t")
        }
    }

    private fun drainAudio() {
        val encoder = audioEncoder ?: return
        val info = MediaCodec.BufferInfo()
        var emptyPolls = 0
        try {
            outer@
            while (true) {
                when (val index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        emptyPolls = 0
                        addTrackLocked { current ->
                            audioTrack = current.addTrack(encoder.outputFormat)
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (stopRequested) {
                            emptyPolls++
                            if (emptyPolls >= EMPTY_POLLS_TO_EXIT) break@outer
                        }
                    }
                    else -> {
                        emptyPolls = 0
                        if (index >= 0) {
                            val buffer = encoder.getOutputBuffer(index)
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (buffer != null && !isConfig && info.size > 0) {
                                val pts = monotonicAudioPtsUs(info.presentationTimeUs)
                                try {
                                    writeSample(buffer, info, audioTrack, pts)
                                } catch (t: Throwable) {
                                    samplesDropped++
                                    if (samplesDropped <= 3) {
                                        DebugLog.log(context, "drainAudio: writeSample falhou: $t")
                                    }
                                }
                            }
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(index, false)
                            if (eos) {
                                DebugLog.log(context, "drain: EOS recebido")
                                break@outer
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Falha no laço de áudio", t)
            DebugLog.log(context, "drainAudio exceção fatal: $t")
        }
    }

    private inline fun addTrackLocked(block: (MediaMuxer) -> Unit) {
        synchronized(muxerLock) {
            val current = muxer ?: return
            block(current)
            val audioReady = !audioCaptured || audioTrack >= 0
            if (!muxerStarted && videoTrack >= 0 && audioReady) {
                // A matriz de rotação (Configurações → Orientação) é definida antes de
                // start(); com pixels não girados, o player aplica a matriz ao exibir.
                current.start()
                muxerStarted = true
                DebugLog.log(context, "MediaMuxer iniciado videoTrack=$videoTrack audioTrack=$audioTrack áudio=$audioCaptured")
            }
        }
    }

    /** PTS de vídeo: o PTS do encoder (monotônico com Baseline profile) menos o PTS do 1º frame, com clamp. */
    private var videoBasePtsUs = -1L

    private fun monotonicVideoPtsUs(rawPtsUs: Long): Long {
        if (videoBasePtsUs < 0) videoBasePtsUs = rawPtsUs
        var pts = rawPtsUs - videoBasePtsUs
        if (pts < 0) pts = 0
        val last = lastVideoPtsUs
        if (last >= 0 && pts <= last) pts = last + 1
        lastVideoPtsUs = pts
        return pts
    }

    private var audioBasePtsUs = -1L

    /** PTS de áudio: o PTS do encoder menos o do 1º frame de áudio, com clamp monotônico. */
    private fun monotonicAudioPtsUs(rawPtsUs: Long): Long {
        if (audioBasePtsUs < 0) audioBasePtsUs = rawPtsUs
        var pts = (rawPtsUs - audioBasePtsUs).coerceAtLeast(0)
        val last = lastAudioPtsUs
        if (last >= 0 && pts <= last) pts = last + 1
        lastAudioPtsUs = pts
        return pts
    }

    private fun writeSample(buffer: java.nio.ByteBuffer, source: MediaCodec.BufferInfo, track: Int, ptsUs: Long) {
        synchronized(muxerLock) {
            if (!muxerStarted || track < 0 || ptsUs < 0) return
            val info = MediaCodec.BufferInfo().apply {
                offset = 0
                size = source.size
                presentationTimeUs = ptsUs
                flags = source.flags
            }
            buffer.position(source.offset)
            buffer.limit(source.offset + source.size)
            muxer!!.writeSampleData(track, buffer, info)
            if (track == videoTrack) samplesWrittenVideo++ else samplesWrittenAudio++
        }
    }

    // ------------------------------------------------------------ teardown

    /** Finaliza ou descarta o arquivo pendente e notifica o chamador. Sempre libera tudo. */
    private fun finishAndNotify(error: String?) {
        synchronized(finishLock) {
            if (finishStarted) return
            finishStarted = true
        }

        var failure = error
        if (failure == null) {
            // Sem nenhum frame multiplexado, o MP4 sairia vazio/corrompido: descarta.
            if (!usingMediaRecorder) {
                val started = synchronized(muxerLock) { muxerStarted }
                if (!started) failure = context.getString(R.string.no_media_captured)
            } else if (mediaRecorderFailed) {
                failure = context.getString(R.string.no_media_captured)
            }
        }
        DebugLog.log(
            context,
            "engine.finishAndNotify failure=$failure muxerStarted=${synchronized(muxerLock) { muxerStarted }}",
        )

        // ---------------------------------------------------- etapa 1: finaliza e fecha
        virtualDisplay?.release()
        virtualDisplay = null
        videoEncoder?.let { encoder ->
            try { encoder.stop() } catch (_: Exception) {}
            encoder.release()
        }
        videoEncoder = null
        audioEncoder?.let { encoder ->
            try { encoder.stop() } catch (_: Exception) {}
            encoder.release()
        }
        audioEncoder = null
        audioRecord?.release()
        audioRecord = null

        synchronized(muxerLock) {
            val current = muxer
            muxer = null
            muxerStarted = false
            videoTrack = -1
            audioTrack = -1
            DebugLog.log(
                context,
                "finishAndNotify: amostras video=$samplesWrittenVideo audio=$samplesWrittenAudio drops=$samplesDropped falha=$failure",
            )
            if (current != null) {
                if (failure == null) {
                    try {
                        current.stop()
                        DebugLog.log(context, "MediaMuxer.stop ok")
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaMuxer.stop falhou", e)
                        DebugLog.log(context, "MediaMuxer.stop FALHOU: $e")
                        failure = context.getString(R.string.mp4_finalize_failed)
                    }
                }
                current.release()
            }
        }

        try { pfd?.close() } catch (_: Exception) {}
        pfd = null

        // --------------------------------- etapa 2: copia o temporário para o MediaStore
        val nameForCallback = outputName
        val temp = tempFile
        val uriForCallback: Uri?
        if (failure == null && temp != null && nameForCallback != null) {
            val uri = saveToMediaStore(temp, nameForCallback)
            if (uri != null) {
                uriForCallback = uri
            } else {
                failure = context.getString(R.string.gallery_save_failed)
                uriForCallback = null
            }
        } else {
            uriForCallback = null
        }

        // ----------------------------------------------- etapa 3: limpeza de tudo
        temp?.let { try { it.delete() } catch (_: Exception) {} }
        tempFile = null
        outputName = null

        try {
            onFinished?.invoke(uriForCallback, failure, nameForCallback)
        } catch (t: Throwable) {
            Log.e(TAG, "onFinished lançou exceção", t)
        }
    }

    private fun saveToMediaStore(file: File, name: String): Uri? {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            // Salvo em Movies/Zrec para aparecer na galeria, sem permissão de armazenamento.
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Zrec")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: run {
            DebugLog.log(context, "saveToMediaStore: insert retornou null")
            return null
        }
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, COPY_BUFFER_BYTES) }
            } ?: throw IOException(context.getString(R.string.output_open_failed))
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            DebugLog.log(context, "saveToMediaStore ok uri=$uri size=${file.length()}")
            uri
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao salvar no MediaStore", e)
            DebugLog.log(context, "saveToMediaStore falhou: $e")
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            null
        }
    }

    companion object {
        private const val TAG = "Zrec"

        private const val MIME_VIDEO = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val MIME_AUDIO = MediaFormat.MIMETYPE_AUDIO_AAC

        private const val AUDIO_CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_CHUNK_BYTES = 4_096
        private const val MIN_AUDIO_BUFFER_BYTES = 4_096
        private const val BUFFER_MULTIPLIER = 4
        private const val MAX_AUDIO_INPUT_SIZE = 16 * 1024

        private const val MICROS_PER_SECOND = 1_000_000L
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val INPUT_DEQUEUE_TIMEOUT_US = 10_000L
        private const val EOS_INPUT_TIMEOUT_US = 1_000_000L
        private const val POLL_SLEEP_MS = 2L
        private const val FEED_JOIN_TIMEOUT_MS = 5_000L
        private const val DRAIN_JOIN_TIMEOUT_MS = 8_000L
        private const val AUDIO_STOP_TIMEOUT_MS = 1_500L
        private const val EMPTY_POLLS_TO_EXIT = 300
        private const val COPY_BUFFER_BYTES = 256 * 1024
    }
}
