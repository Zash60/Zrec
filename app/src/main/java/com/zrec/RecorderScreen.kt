package com.zrec

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zrec.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RecordRed = Color(0xFFE5484D)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(
    onToggleRecording: () -> Unit,
    onOpen: (RecordingInfo) -> Unit,
    onShare: (RecordingInfo) -> Unit,
    onDelete: (RecordingInfo) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val uiState by RecordingState.state.collectAsStateWithLifecycle()
    val settings by Settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    var deleteTarget by remember { mutableStateOf<RecordingInfo?>(null) }

    // Mantém a tela ligada durante a gravação (se ativado nas Configurações): no
    // Android 15+, bloquear a tela encerra a captura automaticamente ("auto-stop on lock").
    LaunchedEffect(uiState.isRecording, settings.keepScreenOn) {
        view.keepScreenOn = uiState.isRecording && settings.keepScreenOn
    }

    val recordings by produceState(
        initialValue = emptyList<RecordingInfo>(),
        uiState.refreshTick,
    ) {
        value = withContext(Dispatchers.IO) { Recordings.query(context) }
    }

    // Cronômetro da gravação em andamento.
    var elapsedMs by remember(uiState.isRecording) { mutableLongStateOf(0L) }
    LaunchedEffect(uiState.isRecording, uiState.startedAtMillis) {
        if (!uiState.isRecording) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsedMs = System.currentTimeMillis() - uiState.startedAtMillis
            delay(1000)
        }
    }

    val infinite = rememberInfiniteTransition(label = "zrec-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "zrec-pulse-alpha",
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.toolbar_title),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_cd),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            // -------------------------------------------------- herói (botão)
            HeroSection(
                isRecording = uiState.isRecording,
                elapsedMs = elapsedMs,
                pulse = pulse,
                onToggleRecording = onToggleRecording,
            )

            // ---------------------------------------------- banners de estado
            uiState.error?.let { error ->
                Banner(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onError) },
                    text = error,
                    onDismiss = { RecordingState.clearError() },
                )
            }
            uiState.warning?.let { warning ->
                Banner(
                    containerColor = Color(0xFF3A2E12),
                    contentColor = Color(0xFFFFE082),
                    icon = { Icon(Icons.Filled.Info, contentDescription = null, tint = Color(0xFFFFE082)) },
                    text = warning,
                    onDismiss = { RecordingState.clearWarning() },
                )
            }
            uiState.savedFileName?.let { saved ->
                Banner(
                    containerColor = Color(0xFF173B2C),
                    contentColor = Color(0xFFB9F6CA),
                    icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFFB9F6CA)) },
                    text = context.resources.getString(R.string.saved_banner, saved),
                    onDismiss = { RecordingState.clearSaved() },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.6.dp)

            // -------------------------------------------------- lista
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.recordings_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${recordings.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = RecordRed,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { RecordingState.refresh() }) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.refresh_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (recordings.isEmpty()) {
                    item { EmptyState() }
                } else {
                    items(items = recordings, key = { it.id }) { item ->
                        RecordingCard(
                            item = item,
                            onOpen = onOpen,
                            onShare = onShare,
                            onDeleteRequest = { deleteTarget = item },
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------- diálogo de exclusão
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.delete_dialog_title)) },
            text = { Text(context.resources.getString(R.string.delete_dialog_body, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.delete_confirm), color = RecordRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.delete_cancel))
                }
            },
        )
    }
}

@Composable
private fun HeroSection(
    isRecording: Boolean,
    elapsedMs: Long,
    pulse: Float,
    onToggleRecording: () -> Unit,
) {
    val toggleCd = stringResource(R.string.toggle_cd)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Status
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(RecordRed.copy(alpha = pulse)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.status_recording),
                    color = RecordRed,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                )
            } else {
                Text(
                    text = stringResource(R.string.status_ready),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 3.sp,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Cronômetro
        Text(
            text = formatClock(elapsedMs),
            fontSize = 56.sp,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(14.dp))

        // Botão recorde
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(if (isRecording) RecordRed else Color(0xFF1F242E))
                .border(
                    width = 4.dp,
                    color = RecordRed.copy(alpha = if (isRecording) pulse else 1f),
                    shape = CircleShape,
                )
                .semantics {
                    // Rótulo grande e estável para testes via uiautomator/a11y.
                    contentDescription = toggleCd
                }
                .clickable(onClick = onToggleRecording),
            contentAlignment = Alignment.Center,
        ) {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(RecordRed),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = if (isRecording) stringResource(R.string.hint_stop) else stringResource(R.string.hint_start),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Banner(
    containerColor: Color,
    contentColor: Color,
    icon: @Composable () -> Unit,
    text: String,
    onDismiss: () -> Unit,
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                color = contentColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_cd), tint = contentColor)
            }
        }
    }
}

@Composable
private fun RecordingCard(
    item: RecordingInfo,
    onOpen: (RecordingInfo) -> Unit,
    onShare: (RecordingInfo) -> Unit,
    onDeleteRequest: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(item) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.play_cd),
                    tint = RecordRed,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        if (item.durationMs > 0) formatClock(item.durationMs) else null,
                        if (item.sizeBytes > 0) formatBytes(item.sizeBytes) else null,
                        formatDate(item.dateAddedSeconds),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onShare(item) }) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.share_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDeleteRequest) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.empty_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ------------------------------------------------------------------ formatos

private fun formatClock(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun formatDate(epochSeconds: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))
