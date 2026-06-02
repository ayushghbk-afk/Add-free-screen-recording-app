package com.example.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.Recording
import com.example.data.RecordingRepository
import com.example.service.ScreenRecordService
import com.example.utils.VideoTrimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ScreenRecorderViewModel(private val repository: RecordingRepository) : ViewModel() {

    // Resolution Presets
    val resolutionPresets = listOf(
        ResolutionPreset("Ultra 1080p", 1080, "Max quality & crisp full-grid detail. Ideal for YouTube & TVs."),
        ResolutionPreset("Smooth 720p", 720, "Smooth HD format, recommended standard balance."),
        ResolutionPreset("Compact 540p", 540, "Compact detail, perfect for small screens & chat apps.")
    )

    private val _selectedPreset = MutableStateFlow(resolutionPresets[1]) // Default 720p
    val selectedPreset: StateFlow<ResolutionPreset> = _selectedPreset.asStateFlow()

    private val _selectedFps = MutableStateFlow(30)
    val selectedFps: StateFlow<Int> = _selectedFps.asStateFlow()

    private val _audioEnabled = MutableStateFlow(true)
    val audioEnabled: StateFlow<Boolean> = _audioEnabled.asStateFlow()

    private val _floatingControlsEnabled = MutableStateFlow(false) // Default to false to ensure permission is requested first
    val floatingControlsEnabled: StateFlow<Boolean> = _floatingControlsEnabled.asStateFlow()

    // Recording status observer from service
    val activeRecordingState = ScreenRecordService.recordingState

    // Recordings History Database
    val recordingsHistory = repository.allRecordings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Trimming State
    private val _trimState = MutableStateFlow<TrimState>(TrimState.Idle)
    val trimState: StateFlow<TrimState> = _trimState.asStateFlow()

    fun selectPreset(preset: ResolutionPreset) {
        _selectedPreset.value = preset
    }

    fun selectFps(fps: Int) {
        _selectedFps.value = fps
    }

    fun toggleAudioState(enabled: Boolean) {
        _audioEnabled.value = enabled
    }

    fun toggleFloatingControls(enabled: Boolean) {
        _floatingControlsEnabled.value = enabled
    }

    fun startScreenRecorder(context: Context, resultCode: Int, data: android.content.Intent) {
        val displayMetrics = context.resources.displayMetrics
        val rawWidth = displayMetrics.widthPixels
        val rawHeight = displayMetrics.heightPixels
        val rawDpi = displayMetrics.densityDpi

        // Avoid stretching or invalid frame structures by matching the real aspect ratio:
        val aspect = rawHeight.toFloat() / rawWidth
        val selectedTargetWidth = _selectedPreset.value.targetWidthLimit
        
        // Ensure standard dimensions are multiple of 16 (strict H264 hardware encoder requirement on emulators & physical chips)
        var finalTargetWidth = (selectedTargetWidth / 16) * 16
        if (finalTargetWidth == 0) {
            finalTargetWidth = 720
        }
        
        var finalTargetHeight = (finalTargetWidth * aspect).toInt()
        finalTargetHeight = (finalTargetHeight / 16) * 16
        if (finalTargetHeight == 0) {
            finalTargetHeight = 1280
        }

        // Calculate HD Bitrate based on preset and FPS
        val baseBitrate = when (selectedTargetWidth) {
            1080 -> 12000000 // 12 Mbps
            720 -> 6000000   // 6 Mbps
            else -> 3000000  // 3 Mbps
        }
        // Multiply by 1.5 if 60 FPS is requested for extra clean HD bandwidth representation:
        val finalBitrate = if (_selectedFps.value == 60) (baseBitrate * 1.5).toInt() else baseBitrate

        Log.d("ScreenRecVM", "Starting Rec: ${finalTargetWidth}x${finalTargetHeight}, ${_selectedFps.value}fps, ${finalBitrate}bps")

        ScreenRecordService.start(
            context = context,
            resultCode = resultCode,
            resultData = data,
            width = finalTargetWidth,
            height = finalTargetHeight,
            dpi = rawDpi,
            fps = _selectedFps.value,
            bitrate = finalBitrate,
            audioEnabled = _audioEnabled.value,
            floatingControls = _floatingControlsEnabled.value
        )
    }

    fun clearActiveStateError() {
        ScreenRecordService.clearError()
    }

    fun stopScreenRecorder(context: Context) {
        ScreenRecordService.stop(context)
    }

    fun pauseScreenRecorder(context: Context) {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    fun resumeScreenRecorder(context: Context) {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_RESUME
        }
        context.startService(intent)
    }

    fun deleteRecording(recording: Recording) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(recording.filePath)
                if (file.exists()) {
                    file.delete()
                }
                repository.deleteRecordingById(recording.id)
            } catch (e: Exception) {
                Log.e("ScreenRecVM", "Error deleting recording", e)
            }
        }
    }

    fun trimRecording(
        recording: Recording,
        startMs: Long,
        endMs: Long
    ) {
        _trimState.value = TrimState.Trimming(0f)
        viewModelScope.launch(Dispatchers.IO) {
            val srcFile = File(recording.filePath)
            if (!srcFile.exists()) {
                _trimState.value = TrimState.Error("Source file not found")
                return@launch
            }

            // Create output file
            val parentDir = srcFile.parentFile ?: srcFile
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val outName = "${recording.title.substringBefore("_trimmed")}_trimmed_${timeStamp}.mp4"
            val outFile = File(parentDir, outName)

            val success = VideoTrimmer.trimVideo(
                inputPath = srcFile.absolutePath,
                outputPath = outFile.absolutePath,
                startMs = startMs,
                endMs = endMs,
                onProgress = { progress ->
                    _trimState.value = TrimState.Trimming(progress)
                }
            )

            if (success) {
                val duration = endMs - startMs
                val fileSize = outFile.length()
                
                // Save new trimmed entry into Room
                val newRecording = Recording(
                    title = outFile.nameWithoutExtension,
                    filePath = outFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    durationMs = duration,
                    fileSize = fileSize,
                    width = recording.width,
                    height = recording.height,
                    bitrate = recording.bitrate,
                    fps = recording.fps,
                    isTrimmed = true
                )
                repository.insertRecording(newRecording)
                _trimState.value = TrimState.Success(outFile.absolutePath)
            } else {
                _trimState.value = TrimState.Error("Lossless processing failed or aborted.")
            }
        }
    }

    fun resetTrimState() {
        _trimState.value = TrimState.Idle
    }
}

data class ResolutionPreset(
    val label: String,
    val targetWidthLimit: Int,
    val description: String
)

sealed class TrimState {
    object Idle : TrimState()
    data class Trimming(val progress: Float) : TrimState()
    data class Success(val outputFilePath: String) : TrimState()
    data class Error(val message: String) : TrimState()
}
