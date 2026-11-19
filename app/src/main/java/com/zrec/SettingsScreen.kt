package com.zrec

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zrec.R

/** Especificação de um diálogo de múltipla escolha aberto na tela. */
private data class DialogSpec(
    val title: String,
    val options: List<String>,
    val selected: Int,
    val onPickIndex: (Int) -> Unit,
)

/**
 * Tela de configurações do Zrec. As opções são aplicadas na próxima gravação;
 * nada muda em uma gravação em andamento.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by Settings.state.collectAsStateWithLifecycle()
    var activeDialog by remember { mutableStateOf<DialogSpec?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    fun <T> choiceDialog(
        title: String,
        values: List<T>,
        selected: T,
        label: (T) -> String,
        onPick: (T) -> Unit,
    ): DialogSpec = DialogSpec(
        title = title,
        options = values.map(label),
        selected = values.indexOf(selected),
        onPickIndex = { index -> onPick(values[index]) },
    )

    // ------------------------------------------------------------------ rótulos dos enums

    fun videoResolutionLabel(r: VideoResolution): String = context.resources.getString(
        when (r) {
            VideoResolution.NATIVE -> R.string.res_native
            VideoResolution.P1080 -> R.string.res_1080p
            VideoResolution.P720 -> R.string.res_720p
            VideoResolution.P480 -> R.string.res_480p
        }
    )

    fun videoBitrateLabel(b: VideoBitrate): String = context.resources.getString(
        when (b) {
            VideoBitrate.LOW -> R.string.bitrate_low
            VideoBitrate.MEDIUM -> R.string.bitrate_medium
            VideoBitrate.HIGH -> R.string.bitrate_high
            VideoBitrate.MAX -> R.string.bitrate_max
        }
    )

    fun frameRateLabel(f: FrameRate): String = context.resources.getString(
        when (f) {
            FrameRate.F24 -> R.string.fps_24
            FrameRate.F30 -> R.string.fps_30
            FrameRate.F60 -> R.string.fps_60
        }
    )

    fun videoOrientationLabel(o: VideoOrientation): String = context.resources.getString(
        when (o) {
            VideoOrientation.AUTO -> R.string.orientation_auto
            VideoOrientation.PORTRAIT -> R.string.orientation_portrait
            VideoOrientation.LANDSCAPE -> R.string.orientation_landscape
        }
    )

    fun iframeLabel(i: IFrameInterval): String = context.resources.getString(
        when (i) {
            IFrameInterval.S1 -> R.string.iframe_1s
            IFrameInterval.S2 -> R.string.iframe_2s
            IFrameInterval.S5 -> R.string.iframe_5s
        }
    )

    fun audioBitrateLabel(b: AudioBitrate): String = context.resources.getString(
        when (b) {
            AudioBitrate.KBPS_96 -> R.string.audio_bitrate_96
            AudioBitrate.KBPS_128 -> R.string.audio_bitrate_128
            AudioBitrate.KBPS_192 -> R.string.audio_bitrate_192
            AudioBitrate.KBPS_256 -> R.string.audio_bitrate_256
        }
    )

    fun audioChannelsLabel(c: AudioChannels): String = context.resources.getString(
        when (c) {
            AudioChannels.STEREO -> R.string.audio_channels_stereo
            AudioChannels.MONO -> R.string.audio_channels_mono
        }
    )

    fun audioSampleRateLabel(s: AudioSampleRate): String = context.resources.getString(
        when (s) {
            AudioSampleRate.RATE_48 -> R.string.audio_sample_rate_48
            AudioSampleRate.RATE_44 -> R.string.audio_sample_rate_44
        }
    )

    fun languageLabel(l: AppLanguage): String = context.resources.getString(
        when (l) {
            AppLanguage.EN -> R.string.language_english
            AppLanguage.PT_BR -> R.string.language_portuguese
        }
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
        ) {
            // ------------------------------------------------------------ VÍDEO
            item { SectionHeader(stringResource(R.string.section_video)) }
            item {
                val title = stringResource(R.string.resolution_title)
                val summary = stringResource(R.string.resolution_summary)
                ChoiceRow(
                    title = title,
                    summary = summary,
                    value = videoResolutionLabel(settings.videoResolution),
                    onClick = {
                        activeDialog = choiceDialog(
                            title = title,
                            values = VideoResolution.entries,
                            selected = settings.videoResolution,
                            label = ::videoResolutionLabel,
                            onPick = { pick -> Settings.update(context) { it.copy(videoResolution = pick) } },
                        )
                    },
                )
            }
            item {
                val title = stringResource(R.string.bitrate_title)
                val summary = stringResource(R.string.bitrate_summary)
                ChoiceRow(
                    title = title,
                    summary = summary,
                    value = videoBitrateLabel(settings.videoBitrate),
                    onClick = {
                        activeDialog = choiceDialog(
                            title = title,
                            values = VideoBitrate.entries,
                            selected = settings.videoBitrate,
                            label = ::videoBitrateLabel,
                            onPick = { pick -> Settings.update(context) { it.copy(videoBitrate = pick) } },
                        )
                    },
                )
            }
            item {
                val title = stringResource(R.string.framerate_title)
                val summary = stringResource(R.string.framerate_summary)
                ChoiceRow(
                    title = title,
                    summary = summary,
                    value = frameRateLabel(settings.frameRate),
                    onClick = {
                        activeDialog = choiceDialog(
                            title = title,
                            values = FrameRate.entries,
                            selected = settings.frameRate,
                            label = ::frameRateLabel,
                            onPick = { pick -> Settings.update(context) { it.copy(frameRate = pick) } },
                        )
                    },
                )
            }
            item {
                val title = stringResource(R.string.orientation_title)
                val summary = stringResource(R.string.orientation_summary)
                ChoiceRow(
                    title = title,
                    summary = summary,
                    value = videoOrientationLabel(settings.orientation),
                    onClick = {
                        activeDialog = choiceDialog(
                            title = title,
                            values = VideoOrientation.entries,
                            selected = settings.orientation,
                            label = ::videoOrientationLabel,
                            onPick = { pick -> Settings.update(context) { it.copy(orientation = pick) } },
                        )
                    },
                )
            }
            item {
                val title = stringResource(R.string.iframe_title)
                val summary = stringResource(R.string.iframe_summary)
                ChoiceRow(
                    title = title,
                    summary = summary,
                    value = iframeLabel(settings.iFrameInterval),
                    onClick = {
                        activeDialog = choiceDialog(
                            title = title,
                            values = IFrameInterval.entries,
                            selected = settings.iFrameInterval,
                            label = ::iframeLabel,
                            onPick = { pick -> Settings.update(context) { it.copy(iFrameInterval = pick) } },
                        )
                    },
                )
            }

            // ------------------------------------------------------------ ÁUDIO
            item { SectionHeader(stringResource(R.string.section_audio)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.audio_internal_title),
                    summary = stringResource(R.string.audio_internal_summary),
                    checked = settings.audioInternal,
                    onCheckedChange = { value -> Settings.update(context) { it.copy(audioInternal = value) } },
                )
            }
            item {
                val title = stringResource(R.string.audio_bitrate_title)
                val summary = stringResource(R.string.audio_bitrate_summary)
                ChoiceRow(
                    title = title,
                    summary = summary,
                    value = audioBitrateLabel(settings.audioBitrate),
                    onClick = {
                        activeDialog = choiceDialog(
                            title = title,
                            values = AudioBitrate.entries,
                            selected = settings.audioBitrate,
                            label = ::audioBitrateLabel,
                            onPick = { pick -> Settings.update(context) { it.copy(audioBitrate = pick) } },
                        )
                    },
                )
            }
            item {
                val title = stringResource(R.string.audio_channels_title)
                val summary = stringResource(R.string.audio_channels_summary)
                ChoiceRow(
                    title = title,
                    summary = summary,
                    value = audioChannelsLabel(settings.audioChannels),
                    onClick = {
                        activeDialog = choiceDialog(
                            title = title,
                            values = AudioChannels.entries,
                            selected = settings.audioChannels,
                            label = ::audioChannelsLabel,
                            onPick = { pick -> Settings.update(context) { it.copy(audioChannels = pick) } },
                        )
                    },
                )
            }
            item {
                val title = stringResource(R.string.audio_sample_rate_title)
                val summary = stringResource(R.string.audio_sample_rate_summary)
                ChoiceRow(
                    title = title,
                    summary = summary,
                    value = audioSampleRateLabel(settings.audioSampleRate),
                    onClick = {
                        activeDialog = choiceDialog(
                            title = title,
                            values = AudioSampleRate.entries,
                            selected = settings.audioSampleRate,
                            label = ::audioSampleRateLabel,
                            onPick = { pick -> Settings.update(context) { it.copy(audioSampleRate = pick) } },
                        )
                    },
                )
            }

            // ------------------------------------------------------------ GERAL
            item { SectionHeader(stringResource(R.string.section_general)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.keep_screen_on_title),
                    summary = stringResource(R.string.keep_screen_on_summary),
                    checked = settings.keepScreenOn,
                    onCheckedChange = { value -> Settings.update(context) { it.copy(keepScreenOn = value) } },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.vibrate_title),
                    summary = stringResource(R.string.vibrate_summary),
                    checked = settings.vibrateOnRecordActions,
                    onCheckedChange = { value -> Settings.update(context) { it.copy(vibrateOnRecordActions = value) } },
                )
            }
            item {
                val title = stringResource(R.string.language_title)
                val summary = stringResource(R.string.language_summary)
                ChoiceRow(
                    title = title,
                    summary = summary,
                    value = languageLabel(settings.language),
                    onClick = {
                        activeDialog = choiceDialog(
                            title = title,
                            values = AppLanguage.entries,
                            selected = settings.language,
                            label = ::languageLabel,
                            onPick = { pick ->
                                Settings.update(context) { it.copy(language = pick) }
                                Settings.applyLanguage(context, pick)
                                activeDialog = null
                            },
                        )
                    },
                )
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.4.dp)
                RestoreDefaultsRow(
                    title = stringResource(R.string.restore_title),
                    summary = stringResource(R.string.restore_summary),
                    onClick = { showResetConfirm = true },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.changes_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }

    // ----------------------------------------------------------- diálogos
    activeDialog?.let { spec ->
        AlertDialog(
            onDismissRequest = { activeDialog = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(spec.title) },
            text = {
                Column {
                    spec.options.forEachIndexed { index, label ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    spec.onPickIndex(index)
                                    activeDialog = null
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = index == spec.selected,
                                onClick = {
                                    spec.onPickIndex(index)
                                    activeDialog = null
                                },
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeDialog = null }) {
                    Text(stringResource(R.string.dialog_close))
                }
            },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.reset_title)) },
            text = { Text(stringResource(R.string.reset_message)) },
            confirmButton = {
                TextButton(onClick = {
                    Settings.reset(context)
                    showResetConfirm = false
                }) {
                    Text(
                        stringResource(R.string.reset_confirm),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.reset_cancel))
                }
            },
        )
    }
}

// ------------------------------------------------------------------ linhas

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp, end = 20.dp),
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    summary: String? = null,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RestoreDefaultsRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
