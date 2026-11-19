package com.zrec

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Estado global da gravação, exposto para a UI como [StateFlow]. */
data class RecordingUiState(
    val isRecording: Boolean = false,
    val startedAtMillis: Long = 0L,
    val error: String? = null,
    /** Aviso não-fatal (ex.: aparelho bloqueou a captura de áudio interno). */
    val warning: String? = null,
    /** Nome do arquivo salvo por último (ex.: "Zrec_20261113_142355.mp4"). */
    val savedFileName: String? = null,
    /** Incrementa sempre que a lista de vídeos deve ser recarregada. */
    val refreshTick: Int = 0,
)

object RecordingState {
    private val _state = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    fun started(atMillis: Long) = _state.update {
        it.copy(
            isRecording = true,
            startedAtMillis = atMillis,
            error = null,
            savedFileName = null,
            // warning é definido pela engine antes do started(); preserva
        )
    }

    fun failed(message: String) = _state.update {
        it.copy(isRecording = false, startedAtMillis = 0L, error = message, refreshTick = it.refreshTick + 1)
    }

    fun warn(message: String) = _state.update { it.copy(warning = message) }

    fun clearWarning() = _state.update { it.copy(warning = null) }

    fun finished(savedName: String?) = _state.update {
        it.copy(
            isRecording = false,
            startedAtMillis = 0L,
            savedFileName = savedName,
            // warning (se houver) permanece visível até o usuário fechar
            refreshTick = it.refreshTick + 1,
        )
    }

    fun refresh() = _state.update { it.copy(refreshTick = it.refreshTick + 1) }

    fun clearError() = _state.update { it.copy(error = null) }

    fun clearSaved() = _state.update { it.copy(savedFileName = null) }
}
