package com.zrec

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    /** Recebe o consentimento de captura de tela e inicia o serviço de gravação. */
    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            DebugLog.log(this, "captureLauncher resultCode=${result.resultCode} data=${result.data != null}")
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_START
                    putExtra(RecordingService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(RecordingService.EXTRA_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, intent)
            } else {
                RecordingState.failed(getString(R.string.capture_denied))
            }
        }

    /** Solicita RECORD_AUDIO (exigido pelo Android para áudio interno) e POST_NOTIFICATIONS. */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (audioGranted) {
                requestCaptureConsent()
            } else {
                // O Android exige RECORD_AUDIO mesmo que o microfone não seja usado:
                // é o que autoriza a captura do playback interno.
                RecordingState.failed(
                    getString(R.string.audio_permission_msg)
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(this)
        Settings.init(this)
        // Aplica o idioma salvo antes de compor a UI, para que as strings apareçam
        // no idioma correto desde o primeiro frame.
        Settings.applyLanguage(this, Settings.snapshot().language)
        DebugLog.log(this, "MainActivity.onCreate")
        enableEdgeToEdge()
        setContent {
            ZrecTheme {
                var showSettings by rememberSaveable { mutableStateOf(false) }
                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    RecorderScreen(
                        onToggleRecording = ::toggleRecording,
                        onOpen = ::openRecording,
                        onShare = ::shareRecording,
                        onDelete = ::deleteRecording,
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ ações

    private fun toggleRecording() {
        val state = RecordingState.state.value
        DebugLog.log(this, "toggleRecording isRecording=${state.isRecording}")
        if (state.isRecording) {
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            }
            startService(intent)
            return
        }
        val missing = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                missing += Manifest.permission.POST_NOTIFICATIONS
            }
        }
        val ungranted = missing.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            permissionLauncher.launch(ungranted.toTypedArray())
            return
        }
        requestCaptureConsent()
    }

    private fun requestCaptureConsent() {
        DebugLog.log(this, "requestCaptureConsent")
        val manager = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openRecording(recording: RecordingInfo) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(recording.uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.open_chooser)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.open_no_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareRecording(recording: RecordingInfo) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, recording.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_chooser)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.share_no_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteRecording(recording: RecordingInfo) {
        val deleted = Recordings.delete(this, recording.uri)
        if (deleted) {
            RecordingState.refresh()
            Toast.makeText(this, getString(R.string.delete_success), Toast.LENGTH_SHORT).show()
        } else {
            RecordingState.failed(getString(R.string.delete_error))
        }
    }
}
