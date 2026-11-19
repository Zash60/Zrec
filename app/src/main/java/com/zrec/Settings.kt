package com.zrec

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Todas as configurações do Zrec, persistidas em SharedPreferences.
 * Os valores padrão preservam o comportamento original do app (1080p automática,
 * 8 Mbps, 30 fps, 128 kbps estéreo 48 kHz, tela ligada, sem rotação).
 */

/** Resolução máxima da gravação (aplicada ao lado maior do vídeo). */
enum class VideoResolution(val maxLongSide: Int?) {
    NATIVE(null),
    P1080(1920),
    P720(1280),
    P480(854),
}

/** Taxa de bits do vídeo H.264 ("qualidade"). */
enum class VideoBitrate(val bps: Int) {
    LOW(4_000_000),
    MEDIUM(8_000_000),
    HIGH(12_000_000),
    MAX(20_000_000),
}

enum class FrameRate(val fps: Int) {
    F24(24),
    F30(30),
    F60(60),
}

/**
 * Orientação do vídeo gravado. Automático segue a tela (retrato grava retrato,
 * paisagem grava paisagem). Retrato/Paisagem forçam o formato do ARQUIVO
 * (9:16 ou 16:9). Os pixels NUNCA são girados nem deformados: o espelho do
 * sistema encaixa o conteúdo no eixo com barras pretas (letterbox/pillarbox)
 * quando não cabe — o vídeo fica como a tela, sempre em pé.
 */
enum class VideoOrientation {
    AUTO,
    PORTRAIT,
    LANDSCAPE,
}

enum class IFrameInterval(val seconds: Int) {
    S1(1),
    S2(2),
    S5(5),
}

enum class AudioBitrate(val bps: Int) {
    KBPS_96(96_000),
    KBPS_128(128_000),
    KBPS_192(192_000),
    KBPS_256(256_000),
}

enum class AudioChannels {
    STEREO,
    MONO,
}

enum class AudioSampleRate(val hz: Int) {
    RATE_48(48_000),
    RATE_44(44_100),
}

/** Idioma da interface do app. */
enum class AppLanguage {
    EN,
    PT_BR;

    /** Locale correspondente. */
    fun toLocale(): Locale = when (this) {
        EN -> Locale.ENGLISH
        PT_BR -> Locale("pt", "BR")
    }

    fun label(context: Context): String = when (this) {
        EN -> context.getString(R.string.language_english)
        PT_BR -> context.getString(R.string.language_portuguese)
    }
}

data class ZrecSettings(
    // ------------------------------------------------------------- vídeo
    val videoResolution: VideoResolution = VideoResolution.NATIVE,
    val videoBitrate: VideoBitrate = VideoBitrate.MEDIUM,
    val frameRate: FrameRate = FrameRate.F30,
    val orientation: VideoOrientation = VideoOrientation.AUTO,
    val iFrameInterval: IFrameInterval = IFrameInterval.S2,
    // ------------------------------------------------------------- áudio
    /** Se desativado, o áudio interno não é capturado (vídeo sai mudo, sem aviso). */
    val audioInternal: Boolean = true,
    val audioBitrate: AudioBitrate = AudioBitrate.KBPS_128,
    val audioChannels: AudioChannels = AudioChannels.STEREO,
    val audioSampleRate: AudioSampleRate = AudioSampleRate.RATE_48,
    // ------------------------------------------------------------- geral
    /** Idioma da interface. Padrão: inglês. */
    val language: AppLanguage = AppLanguage.EN,
    /** Mantém a tela ligada durante a gravação (recomendado: evita o auto-stop do Android 15). */
    val keepScreenOn: Boolean = true,
    val vibrateOnRecordActions: Boolean = true,
)

object Settings {

    private const val PREFS = "zrec_settings"

    private const val KEY_RESOLUTION = "video_resolution"
    private const val KEY_VIDEO_BITRATE = "video_bitrate"
    private const val KEY_FRAME_RATE = "frame_rate"
    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_IFRAME = "i_frame_interval"
    private const val KEY_AUDIO_INTERNAL = "audio_internal"
    private const val KEY_AUDIO_BITRATE = "audio_bitrate"
    private const val KEY_AUDIO_CHANNELS = "audio_channels"
    private const val KEY_AUDIO_SAMPLE_RATE = "audio_sample_rate"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_LAST_APPLIED_LANGUAGE = "last_applied_language"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_VIBRATE = "vibrate_on_record_actions"

    private val _state = MutableStateFlow(ZrecSettings())

    /** Estado atual (sempre disponível após [init]; antes disso, os padrões). */
    val state: StateFlow<ZrecSettings> = _state.asStateFlow()

    @Volatile
    private var initialized = false

    /** Carrega as preferências (idempotente). Chamar com o applicationContext. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        _state.value = load(context.applicationContext)
    }

    /** Snapshot síncrono para o pipeline de gravação. */
    fun snapshot(): ZrecSettings = _state.value

    /** Aplica a transformação, persiste o resultado e notifica a UI. */
    fun update(context: Context, transform: (ZrecSettings) -> ZrecSettings) {
        val next = transform(_state.value)
        _state.value = next
        persist(context.applicationContext, next)
    }

    /** Restaura todos os padrões. */
    fun reset(context: Context) = update(context) { ZrecSettings() }

    /** Aplica o idioma escolhido ao contexto e recria a Activity, se for o caso. */
    fun applyLanguage(context: Context, language: AppLanguage) {
        val locale = language.toLocale()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastApplied = prefs.getString(KEY_LAST_APPLIED_LANGUAGE, null)
        // Evita recriar a Activity se o idioma já foi aplicado (previne loop infinito).
        if (lastApplied == language.name) {
            return
        }
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
        } else {
            config.locale = locale
        }
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        if (context is android.app.Activity) {
            prefs.edit().putString(KEY_LAST_APPLIED_LANGUAGE, language.name).apply()
            context.recreate()
        } else {
            prefs.edit().putString(KEY_LAST_APPLIED_LANGUAGE, language.name).apply()
        }
    }

    // ------------------------------------------------------------------ disco

    private fun load(context: Context): ZrecSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching {
            ZrecSettings(
                videoResolution = prefs.enum(KEY_RESOLUTION, VideoResolution.NATIVE),
                videoBitrate = prefs.enum(KEY_VIDEO_BITRATE, VideoBitrate.MEDIUM),
                frameRate = prefs.enum(KEY_FRAME_RATE, FrameRate.F30),
                orientation = prefs.enum(KEY_ORIENTATION, VideoOrientation.AUTO),
                iFrameInterval = prefs.enum(KEY_IFRAME, IFrameInterval.S2),
                audioInternal = prefs.getBoolean(KEY_AUDIO_INTERNAL, true),
                audioBitrate = prefs.enum(KEY_AUDIO_BITRATE, AudioBitrate.KBPS_128),
                audioChannels = prefs.enum(KEY_AUDIO_CHANNELS, AudioChannels.STEREO),
                audioSampleRate = prefs.enum(KEY_AUDIO_SAMPLE_RATE, AudioSampleRate.RATE_48),
                language = prefs.enum(KEY_LANGUAGE, AppLanguage.EN),
                keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
                vibrateOnRecordActions = prefs.getBoolean(KEY_VIBRATE, true),
            )
        }.getOrElse { ZrecSettings() }
    }

    private fun persist(context: Context, settings: ZrecSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_RESOLUTION, settings.videoResolution.name)
            putString(KEY_VIDEO_BITRATE, settings.videoBitrate.name)
            putString(KEY_FRAME_RATE, settings.frameRate.name)
            putString(KEY_ORIENTATION, settings.orientation.name)
            putString(KEY_IFRAME, settings.iFrameInterval.name)
            putBoolean(KEY_AUDIO_INTERNAL, settings.audioInternal)
            putString(KEY_AUDIO_BITRATE, settings.audioBitrate.name)
            putString(KEY_AUDIO_CHANNELS, settings.audioChannels.name)
            putString(KEY_AUDIO_SAMPLE_RATE, settings.audioSampleRate.name)
            putString(KEY_LANGUAGE, settings.language.name)
            putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            putBoolean(KEY_VIBRATE, settings.vibrateOnRecordActions)
            apply()
        }
    }

    private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enum(key: String, default: T): T {
        val raw = getString(key, null) ?: return default
        return enumValues<T>().firstOrNull { it.name == raw } ?: default
    }
}
