package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.data.Recording
import com.example.data.RecordingDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ScreenRecordService : Service() {

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null

    private var serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var timerJob: Job? = null

    private var startTimestampSec: Long = 0
    private var durationMs: Long = 0
    private var currentFile: File? = null

    // Params
    private var targetWidth = 720
    private var targetHeight = 1280
    private var fps = 30
    private var bitrate = 5000000
    private var audioEnabled = true
    private var dpi = 240

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val action = intent.action
        if (action == ACTION_STOP) {
            stopRecordingAndSave()
            stopSelf()
            return START_NOT_STICKY
        }

        // 1. Immediately post notification and go foreground (Android requirement)
        createNotificationChannel()
        val notification = createNotification("00:00")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2. Set recording configuration
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        
        targetWidth = intent.getIntExtra(EXTRA_WIDTH, 720)
        targetHeight = intent.getIntExtra(EXTRA_HEIGHT, 1280)
        fps = intent.getIntExtra(EXTRA_FPS, 30)
        bitrate = intent.getIntExtra(EXTRA_BITRATE, 5000000)
        audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO, true)
        dpi = intent.getIntExtra(EXTRA_DPI, 240)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "Invalid result code or null intent data. Stopping service.")
            _recordingState.value = ServiceState.Error("Screen capture permission was not granted.")
            stopSelf()
            return START_NOT_STICKY
        }

        // 3. Start recording actual media
        serviceScope.launch {
            try {
                startRecording(resultCode, resultData)
            } catch (e: Exception) {
                Log.e(TAG, "Failed or crashed starting recording", e)
                _recordingState.value = ServiceState.Error("Error initializing: ${e.message}")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, resultData: Intent) {
        val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        if (!moviesDir.exists()) {
            moviesDir.mkdirs()
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "REC_${timeStamp}.mp4"
        val file = File(moviesDir, fileName)
        currentFile = file

        // Create MediaRecorder
        @Suppress("DEPRECATION")
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            MediaRecorder()
        }

        mediaRecorder?.apply {
            if (audioEnabled) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(file.absolutePath)

            // Video specs
            setVideoSize(targetWidth, targetHeight)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (audioEnabled) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
            }
            setVideoEncodingBitRate(bitrate)
            setVideoFrameRate(fps)

            prepare()
        }

        // MediaProjection
        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
        if (mediaProjection == null) {
            throw IllegalStateException("Failed to create MediaProjection.")
        }

        // VirtualDisplay
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecorderVirtualDisplay",
            targetWidth,
            targetHeight,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder?.surface,
            null,
            null
        )

        mediaRecorder?.start()
        startTimestampSec = System.currentTimeMillis()
        _recordingState.value = ServiceState.Recording(0, "00:00", file.absolutePath)

        // Start timer
        startTimer()
    }

    private fun stopRecordingAndSave() {
        timerJob?.cancel()
        
        val recordTime = System.currentTimeMillis() - startTimestampSec
        durationMs = if (recordTime > 0) recordTime else 0

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mediaRecorder (potentially too short)", e)
            currentFile?.delete()
            currentFile = null
        } finally {
            mediaRecorder?.release()
            mediaRecorder = null
        }

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.stop()
        mediaProjection = null

        val finalFile = currentFile
        if (finalFile != null && finalFile.exists() && finalFile.length() > 0) {
            // Save metadata in background
            val size = finalFile.length()
            val path = finalFile.absolutePath
            val title = finalFile.nameWithoutExtension

            serviceScope.launch(Dispatchers.IO) {
                val db = RecordingDatabase.getDatabase(applicationContext)
                db.recordingDao().insertRecording(
                    Recording(
                        title = title,
                        filePath = path,
                        timestamp = System.currentTimeMillis(),
                        durationMs = durationMs,
                        fileSize = size,
                        width = targetWidth,
                        height = targetHeight,
                        bitrate = bitrate,
                        fps = fps,
                        isTrimmed = false
                    )
                )
                withContext(Dispatchers.Main) {
                    _recordingState.value = ServiceState.Idle
                }
            }
        } else {
            _recordingState.value = ServiceState.Idle
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                val elapsed = System.currentTimeMillis() - startTimestampSec
                val totalSecs = elapsed / 1000
                val minutes = totalSecs / 60
                val seconds = totalSecs % 60
                val elapsedStr = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                _recordingState.value = ServiceState.Recording(elapsed, elapsedStr, currentFile?.absolutePath ?: "")
                updateNotification(elapsedStr)
            }
        }
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotification(timeText: String): Notification {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 
            0, 
            stopIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Launch app on click
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Recording Active")
            .setContentText("Duration: $timeText")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(launchPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop Recording", stopPendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recorder Control Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel displaying active screen recording controls"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    sealed class ServiceState {
        object Idle : ServiceState()
        data class Recording(val durationMs: Long, val elapsedStr: String, val filePath: String) : ServiceState()
        data class Error(val message: String) : ServiceState()
    }

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val NOTIFICATION_ID = 48512
        private const val CHANNEL_ID = "screen_record_channel"

        const val ACTION_STOP = "com.example.service.STOP"
        
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_DPI = "dpi"

        private val _recordingState = MutableStateFlow<ServiceState>(ServiceState.Idle)
        val recordingState: StateFlow<ServiceState> = _recordingState.asStateFlow()

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            width: Int,
            height: Int,
            dpi: Int,
            fps: Int,
            bitrate: Int,
            audioEnabled: Boolean
        ) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_DPI, dpi)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_BITRATE, bitrate)
                putExtra(EXTRA_AUDIO, audioEnabled)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
