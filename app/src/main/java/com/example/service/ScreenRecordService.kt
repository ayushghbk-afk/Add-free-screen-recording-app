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

    // Window manager for floating controller overlay
    private var windowManager: android.view.WindowManager? = null
    private var floatingView: android.view.View? = null
    private var floatingCountTextView: android.widget.TextView? = null
    private var floatingControlsEnabled = false

    private var isExpanded = true
    private var accumulatedDurationMs = 0L
    private val idleHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val collapseRunnable = Runnable {
        collapseFloatingControls()
    }

    // Pen overlay fields
    private var isPenActive = false
    private var drawingCanvasView: DrawingCanvasView? = null
    private var penColor = android.graphics.Color.RED
    private var penWidth = 10f
    private var isPenInteractClickThrough = false

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
        if (action == ACTION_DISCARD) {
            discardRecording()
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_PAUSE) {
            pauseRecording()
            return START_NOT_STICKY
        }
        if (action == ACTION_RESUME) {
            resumeRecording()
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
        floatingControlsEnabled = intent.getBooleanExtra(EXTRA_FLOATING_CONTROLS, false)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "Invalid result code or null intent data. Stopping service.")
            _recordingState.value = ServiceState.Error("Screen capture permission was not granted.")
            stopSelf()
            return START_NOT_STICKY
        }

        // 3. STRICT REQUIREMENT on Android 14+: Retrieve the MediaProjection object synchronously
        // inside onStartCommand BEFORE it returns. Doing it inside an async coroutine throws SecurityException!
        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed secure projection acquisition on Android 14+", e)
            _recordingState.value = ServiceState.Error("Failed to initiate media screen projection: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to create MediaProjection.")
            _recordingState.value = ServiceState.Error("Failed to establish screen projection session.")
            stopSelf()
            return START_NOT_STICKY
        }

        // 4. Mandatory for Android 14 (API 34) and higher: You must register a callback
        // before invoking createVirtualDisplay(), otherwise a SecurityException will be thrown.
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection session stopped automatically.")
                serviceScope.launch {
                    stopRecordingAndSave()
                    stopSelf()
                }
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))

        // 5. Start recording actual media
        serviceScope.launch {
            try {
                startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Failed or crashed starting recording", e)
                _recordingState.value = ServiceState.Error("Error starting recording: ${e.message}")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startRecording() {
        val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        if (!moviesDir.exists()) {
            moviesDir.mkdirs()
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "REC_${timeStamp}.mp4"
        val file = File(moviesDir, fileName)
        currentFile = file

        // Securely check for microphone permission to avoid crashes on emulators or permissions mismatch
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val useAudio = audioEnabled && hasAudioPermission

        // Create MediaRecorder
        @Suppress("DEPRECATION")
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            MediaRecorder()
        }

        mediaRecorder?.apply {
            if (useAudio) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(file.absolutePath)

            // Video specs - standardized to multiple of 16 inside ViewModel
            setVideoSize(targetWidth, targetHeight)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (useAudio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
            }
            setVideoEncodingBitRate(bitrate)
            setVideoFrameRate(fps)

            prepare()
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
        accumulatedDurationMs = 0L
        isExpanded = true
        _recordingState.value = ServiceState.Recording(0, "00:00", file.absolutePath)

        // Start timer
        startTimer()

        // Display overlay controller if enabled
        if (floatingControlsEnabled) {
            showFloatingControls()
            resetIdleTimer()
        }
    }

    private fun stopRecordingAndSave() {
        timerJob?.cancel()
        hideDrawingCanvas()
        hideFloatingControls()
        
        val recordTime = if (_recordingState.value is ServiceState.Paused) {
            accumulatedDurationMs
        } else {
            accumulatedDurationMs + (System.currentTimeMillis() - startTimestampSec)
        }
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
                val currentSegmentTime = System.currentTimeMillis() - startTimestampSec
                val elapsed = accumulatedDurationMs + currentSegmentTime
                val totalSecs = elapsed / 1000
                val minutes = totalSecs / 60
                val seconds = totalSecs % 60
                val elapsedStr = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                _recordingState.value = ServiceState.Recording(elapsed, elapsedStr, currentFile?.absolutePath ?: "")
                updateNotification(elapsedStr)
                // Dynamically update elapsed duration text inside overlay
                floatingCountTextView?.text = elapsedStr
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

        // Added Discard Action: allow user to end recording and delete files directly from notifications
        val discardIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_DISCARD
        }
        val discardPendingIntent = PendingIntent.getService(
            this,
            2,
            discardIntent,
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

        val soundTypeStr = if (audioEnabled) "Mic Audio On" else "Muted"
        val formatStr = "${targetWidth}x${targetHeight} @ ${fps}FPS"

        val isNowPaused = _recordingState.value is ServiceState.Paused
        val stateTitle = if (isNowPaused) "Screen Recording Paused" else "Screen Recording Active"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(stateTitle)
            .setContentText("Duration: $timeText  •  $soundTypeStr  •  $formatStr")
            .setSmallIcon(com.example.R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(launchPendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Add Pause or Resume action dynamically
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isNowPaused) {
                val resumeIntent = Intent(this, ScreenRecordService::class.java).apply {
                    action = ACTION_RESUME
                }
                val resumePendingIntent = PendingIntent.getService(
                    this,
                    3,
                    resumeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_media_play, "Resume", resumePendingIntent)
            } else {
                val pauseIntent = Intent(this, ScreenRecordService::class.java).apply {
                    action = ACTION_PAUSE
                }
                val pausePendingIntent = PendingIntent.getService(
                    this,
                    4,
                    pauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent)
            }
        }

        // Add standard Stop and Discard
        builder.addAction(android.R.drawable.ic_media_pause, "Save", stopPendingIntent)
        builder.addAction(android.R.drawable.ic_menu_delete, "Discard", discardPendingIntent)

        return builder.build()
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
        hideDrawingCanvas()
        hideFloatingControls()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun initializeDrawingCanvas() {
        if (drawingCanvasView != null) return
        windowManager = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
        
        val canvas = DrawingCanvasView(this).apply {
            activeColor = penColor
            activeStrokeWidth = penWidth
            visibility = android.view.View.GONE
        }
        drawingCanvasView = canvas

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            android.view.WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            type,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(canvas, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing drawing canvas", e)
        }
    }

    private fun updateDrawingCanvasState() {
        val canvas = drawingCanvasView ?: return
        val wm = windowManager ?: return
        
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            android.view.WindowManager.LayoutParams.TYPE_PHONE
        }

        if (isPenActive) {
            canvas.visibility = android.view.View.VISIBLE
            
            val flags = if (isPenInteractClickThrough) {
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            } else {
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }

            val params = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                type,
                flags,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            try {
                wm.updateViewLayout(canvas, params)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating draw canvas params", e)
            }
        } else {
            canvas.visibility = android.view.View.GONE
            canvas.clearCanvas()
            
            val params = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                type,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            try {
                wm.updateViewLayout(canvas, params)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating draw canvas params to hidden", e)
            }
        }
    }

    private fun togglePenDrawing() {
        isPenActive = !isPenActive
        if (isPenActive) {
            isPenInteractClickThrough = false
        }
        updateDrawingCanvasState()
        updateFloatingControlsUI()
    }

    private fun hideDrawingCanvas() {
        drawingCanvasView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing drawing canvas", e)
            }
        }
        drawingCanvasView = null
    }

    private fun togglePenTouchThrough() {
        isPenInteractClickThrough = !isPenInteractClickThrough
        updateDrawingCanvasState()
        updateFloatingControlsUI()
    }

    private fun stopRecordingAndExport() {
        timerJob?.cancel()
        hideDrawingCanvas()
        hideFloatingControls()
        
        val recordTime = if (_recordingState.value is ServiceState.Paused) {
            accumulatedDurationMs
        } else {
            accumulatedDurationMs + (System.currentTimeMillis() - startTimestampSec)
        }
        durationMs = if (recordTime > 0) recordTime else 0

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mediaRecorder during export", e)
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
                    
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            applicationContext,
                            "$packageName.fileprovider",
                            finalFile
                        )
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val chooserIntent = Intent.createChooser(sendIntent, "Export Media File").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(chooserIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error launching export menu", e)
                    }
                }
            }
        } else {
            _recordingState.value = ServiceState.Idle
        }
    }

    private fun addSpacing(layout: android.widget.LinearLayout, dp: Int) {
        val spacer = android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(dp), dpToPx(1))
        }
        layout.addView(spacer)
    }

    private fun showFloatingControls() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Floating control permission NOT granted.")
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager

        // Initialize drawing canvas first, placing it below floatingView in Z-order
        initializeDrawingCanvas()

        // Create main container view (LinearLayout)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        // Setup WindowManager LayoutParams with FLAG_SECURE so it is completely excluded/hidden from video recording
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            android.view.WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            x = 0
            y = 200 // Position it slightly below status bar in safe zone
        }

        // Initial rendering of internal controls
        rebuildFloatingViews(container)

        // Add Draggability and Click-to-Expand support to container
        container.setOnTouchListener(object : android.view.View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false
            private var startTime = 0L

            override fun onTouch(view: android.view.View, event: android.view.MotionEvent): Boolean {
                resetIdleTimer() // Interactive touch events keep it expanded
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        startTime = System.currentTimeMillis()
                        idleHideHandler.removeCallbacks(collapseRunnable) // keep expanded during active drag
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val diffX = event.rawX - initialTouchX
                        val diffY = event.rawY - initialTouchY
                        if (Math.abs(diffX) > 10 || Math.abs(diffY) > 10 || isDragging) {
                            isDragging = true
                            params.x = (initialX + diffX).toInt()
                            params.y = (initialY + diffY).toInt()
                            windowManager?.updateViewLayout(container, params)
                        }
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val clickDuration = System.currentTimeMillis() - startTime
                        val diffX = event.rawX - initialTouchX
                        val diffY = event.rawY - initialTouchY
                        val isClick = clickDuration < 300 && Math.abs(diffX) < 10 && Math.abs(diffY) < 10

                        if (isClick) {
                            if (!isExpanded) {
                                expandFloatingControls()
                            } else {
                                resetIdleTimer()
                            }
                        } else {
                            resetIdleTimer()
                        }
                        return true
                    }
                }
                return false
            }
        })

        floatingView = container
        try {
            windowManager?.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay floatingView", e)
        }
    }

    private fun rebuildFloatingViews(container: android.widget.LinearLayout) {
        container.removeAllViews()
        val isNowPaused = _recordingState.value is ServiceState.Paused

        if (!isExpanded) {
            // COLLAPSED mini pill layout (low-profile tiny status indicator)
            container.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))

            val backgroundDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#B31B1E21")) // High translucent midnight slate (70%)
                cornerRadius = dpToPx(28).toFloat()
                setStroke(dpToPx(2), android.graphics.Color.parseColor("#00E676")) // Vibrant border accent
            }
            container.background = backgroundDrawable

            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // Small indicator dot
            val dotView = android.view.View(this).apply {
                val layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(10), dpToPx(10))
                this.layoutParams = layoutParams
                val dotDrawable = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (isNowPaused) android.graphics.Color.parseColor("#FFD600") else android.graphics.Color.RED)
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                }
                background = dotDrawable
            }
            if (!isNowPaused) {
                val anim = android.view.animation.AlphaAnimation(0.3f, 1.0f).apply {
                    duration = 500
                    repeatMode = android.view.animation.Animation.REVERSE
                    repeatCount = android.view.animation.Animation.INFINITE
                }
                dotView.startAnimation(anim)
            }
            row.addView(dotView)

            // Live Timer displayed in collapsed view
            val collapsedTime = when (val state = _recordingState.value) {
                is ServiceState.Active -> state.elapsedStr
                else -> "00:00"
            }
            val tinyTimeText = android.widget.TextView(this).apply {
                text = collapsedTime
                setTextColor(android.graphics.Color.WHITE)
                textSize = 10f
                setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                val layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dpToPx(6)
                }
                this.layoutParams = layoutParams
            }
            row.addView(tinyTimeText)

            container.addView(row)

        } else {
            // EXPANDED dynamic dashboard studio controls
            container.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))

            val backgroundDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#EE1B1E21")) // Glossy dark card slate
                cornerRadius = dpToPx(24).toFloat()
                setStroke(dpToPx(1), android.graphics.Color.parseColor("#495057"))
            }
            container.background = backgroundDrawable

            // Row 1: Primary Control Elements
            val row1 = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 1. Status Indicator dot
            val dotView = android.view.View(this).apply {
                val layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).apply {
                    rightMargin = dpToPx(8)
                }
                this.layoutParams = layoutParams
                val dotDrawable = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (isNowPaused) android.graphics.Color.parseColor("#FFD600") else android.graphics.Color.RED)
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                }
                background = dotDrawable
            }
            if (!isNowPaused) {
                val anim = android.view.animation.AlphaAnimation(0.3f, 1.0f).apply {
                    duration = 600
                    repeatMode = android.view.animation.Animation.REVERSE
                    repeatCount = android.view.animation.Animation.INFINITE
                }
                dotView.startAnimation(anim)
            }
            row1.addView(dotView)

            // 2. Continuous dynamic timer label
            val stateElapsedText = when (val state = _recordingState.value) {
                is ServiceState.Active -> state.elapsedStr
                else -> "00:00"
            }

            floatingCountTextView = android.widget.TextView(this).apply {
                text = stateElapsedText
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                val layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = dpToPx(10)
                }
                this.layoutParams = layoutParams
            }
            row1.addView(floatingCountTextView)

            // 3. Spacing vertical separator lines
            val dividerView = android.view.View(this).apply {
                val layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(1), dpToPx(16)).apply {
                    rightMargin = dpToPx(10)
                }
                this.layoutParams = layoutParams
                setBackgroundColor(android.graphics.Color.parseColor("#495057"))
            }
            row1.addView(dividerView)

            // 4. Grouped action buttons
            val btnLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // Pause/Resume Button
            val pauseResumeBtn = if (isNowPaused) {
                createFloatActionButton("▶", "#00E676") {
                    resumeRecording()
                }
            } else {
                createFloatActionButton("⏸", "#29B6F6") {
                    pauseRecording()
                }
            }
            btnLayout.addView(pauseResumeBtn)
            addSpacing(btnLayout, 6)

            // STOP & SAVE Button (■)
            val saveBtn = createFloatActionButton("■", "#FF1744") {
                stopRecordingAndSave()
                stopSelf()
            }
            btnLayout.addView(saveBtn)
            addSpacing(btnLayout, 6)

            // EXPORT & SHARE (📤)
            val exportBtn = createFloatActionButton("📤", "#FF9100") {
                stopRecordingAndExport()
                stopSelf()
            }
            btnLayout.addView(exportBtn)
            addSpacing(btnLayout, 6)

            // PEN/ANNOTATION TOOL (✏️)
            val penBtnColor = if (isPenActive) "#E040FB" else "#90A4AE"
            val penBtn = createFloatActionButton("✏️", penBtnColor) {
                togglePenDrawing()
            }
            btnLayout.addView(penBtn)
            addSpacing(btnLayout, 6)

            // Discard Button (✕)
            val discardBtn = createFloatActionButton("✕", "#757575") {
                discardRecording()
                stopSelf()
            }
            btnLayout.addView(discardBtn)
            addSpacing(btnLayout, 6)

            // Collapse Button (➖)
            val collapseBtn = createFloatActionButton("➖", "#ECEFF1") {
                collapseFloatingControls()
            }
            btnLayout.addView(collapseBtn)

            row1.addView(btnLayout)
            container.addView(row1)

            // Row 2: Pen Tools Draw Suite (visible ONLY if pen drawing mode is active)
            if (isPenActive) {
                // Divider horizontal line
                val penDivider = android.view.View(this).apply {
                    val layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(1)
                    ).apply {
                        topMargin = dpToPx(8)
                        bottomMargin = dpToPx(8)
                    }
                    this.layoutParams = layoutParams
                    setBackgroundColor(android.graphics.Color.parseColor("#424242"))
                }
                container.addView(penDivider)

                val row2 = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                // Title Label
                val penLabel = android.widget.TextView(this).apply {
                    text = "DRAW:"
                    setTextColor(android.graphics.Color.parseColor("#E040FB"))
                    textSize = 10f
                    setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                    val layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        rightMargin = dpToPx(8)
                    }
                    this.layoutParams = layoutParams
                }
                row2.addView(penLabel)

                // Color selectors
                val colors = listOf(
                    android.graphics.Color.RED to "🔴",
                    android.graphics.Color.GREEN to "🟢",
                    android.graphics.Color.BLUE to "🔵",
                    android.graphics.Color.YELLOW to "🟡",
                    android.graphics.Color.WHITE to "⚪"
                )

                for ((color, symbol) in colors) {
                    val isColorSelected = penColor == color
                    val chipBtn = android.widget.TextView(this).apply {
                        text = symbol
                        textSize = 13f
                        setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
                        gravity = android.view.Gravity.CENTER
                        
                        val bg = android.graphics.drawable.GradientDrawable().apply {
                            setColor(if (isColorSelected) android.graphics.Color.parseColor("#424242") else android.graphics.Color.TRANSPARENT)
                            cornerRadius = dpToPx(8).toFloat()
                            if (isColorSelected) {
                                setStroke(dpToPx(1), android.graphics.Color.parseColor("#E040FB"))
                            }
                        }
                        background = bg
                        
                        setOnClickListener {
                            penColor = color
                            drawingCanvasView?.activeColor = color
                            updateFloatingControlsUI()
                        }
                    }
                    row2.addView(chipBtn)
                    addSpacing(row2, 4)
                }

                // Canvas Eraser / Sweep button (🧹)
                val clearBtn = createFloatActionButton("🧹 Clear", "#E040FB") {
                    drawingCanvasView?.clearCanvas()
                }
                row2.addView(clearBtn)
                addSpacing(row2, 6)

                // Draw / Clickunder Toggle
                val statusText = if (isPenInteractClickThrough) {
                    "👆 Clickable"
                } else {
                    "✍️ Draw Mode"
                }
                val modeBtnColor = if (isPenInteractClickThrough) "#FFB300" else "#29B6F6"
                val interactToggleBtn = createFloatActionButton(statusText, modeBtnColor) {
                    togglePenTouchThrough()
                }
                row2.addView(interactToggleBtn)

                container.addView(row2)
            }
        }
    }

    private fun createFloatActionButton(text: String, colorHex: String, onClick: () -> Unit): android.widget.TextView {
        return android.widget.TextView(this).apply {
            this.text = text
            setTextColor(android.graphics.Color.parseColor(colorHex))
            textSize = 13f
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            gravity = android.view.Gravity.CENTER

            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#2C2F33")) // Sleek grey button pill
                cornerRadius = dpToPx(12).toFloat()
            }
            background = bg

            isClickable = true
            isFocusable = true

            setOnClickListener {
                resetIdleTimer()
                onClick()
            }
        }
    }

    private fun updateFloatingWindowSize() {
        val container = floatingView as? android.widget.LinearLayout ?: return
        val params = container.layoutParams as? android.view.WindowManager.LayoutParams ?: return
        rebuildFloatingViews(container)
        try {
            windowManager?.updateViewLayout(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating overlay layout", e)
        }
    }

    private fun resetIdleTimer() {
        idleHideHandler.removeCallbacks(collapseRunnable)
        if (floatingControlsEnabled && isExpanded) {
            idleHideHandler.postDelayed(collapseRunnable, 4000)
        }
    }

    private fun collapseFloatingControls() {
        if (!isExpanded) return
        isExpanded = false
        updateFloatingWindowSize()
    }

    private fun expandFloatingControls() {
        if (isExpanded) return
        isExpanded = true
        updateFloatingWindowSize()
        resetIdleTimer()
    }

    private fun updateFloatingControlsUI() {
        val container = floatingView as? android.widget.LinearLayout ?: return
        container.post {
            updateFloatingWindowSize()
        }
    }

    private fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder?.pause()
                timerJob?.cancel()

                val currentSegmentTime = System.currentTimeMillis() - startTimestampSec
                accumulatedDurationMs += currentSegmentTime

                val totalSecs = accumulatedDurationMs / 1000
                val minutes = totalSecs / 60
                val seconds = totalSecs % 60
                val elapsedStr = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

                _recordingState.value = ServiceState.Paused(accumulatedDurationMs, elapsedStr, currentFile?.absolutePath ?: "")
                updateNotification(elapsedStr)
                updateFloatingControlsUI()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pause mediarecorder", e)
            }
        }
    }

    private fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder?.resume()
                startTimestampSec = System.currentTimeMillis()
                startTimer()

                val totalSecs = accumulatedDurationMs / 1000
                val minutes = totalSecs / 60
                val seconds = totalSecs % 60
                val elapsedStr = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

                _recordingState.value = ServiceState.Recording(accumulatedDurationMs, elapsedStr, currentFile?.absolutePath ?: "")
                updateNotification(elapsedStr)
                updateFloatingControlsUI()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume mediarecorder", e)
            }
        }
    }

    private fun hideFloatingControls() {
        idleHideHandler.removeCallbacks(collapseRunnable)
        if (floatingView != null && windowManager != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floatingView", e)
            } finally {
                floatingView = null
                floatingCountTextView = null
            }
        }
    }

    private fun discardRecording() {
        timerJob?.cancel()
        hideDrawingCanvas()
        hideFloatingControls()
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder on discard capture", e)
        } finally {
            mediaRecorder?.release()
            mediaRecorder = null
        }
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        
        currentFile?.delete()
        currentFile = null
        _recordingState.value = ServiceState.Idle
    }

    sealed class ServiceState {
        object Idle : ServiceState()
        interface Active {
            val durationMs: Long
            val elapsedStr: String
            val filePath: String
        }
        data class Recording(override val durationMs: Long, override val elapsedStr: String, override val filePath: String) : ServiceState(), Active
        data class Paused(override val durationMs: Long, override val elapsedStr: String, override val filePath: String) : ServiceState(), Active
        data class Error(val message: String) : ServiceState()
    }

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val NOTIFICATION_ID = 48512
        private const val CHANNEL_ID = "screen_record_channel"

        const val ACTION_STOP = "com.example.service.STOP"
        const val ACTION_DISCARD = "com.example.service.DISCARD"
        const val ACTION_PAUSE = "com.example.service.PAUSE"
        const val ACTION_RESUME = "com.example.service.RESUME"
        
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_DPI = "dpi"
        const val EXTRA_FLOATING_CONTROLS = "floating_controls"

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
            audioEnabled: Boolean,
            floatingControls: Boolean = false
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
                putExtra(EXTRA_FLOATING_CONTROLS, floatingControls)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun clearError() {
            if (_recordingState.value is ServiceState.Error) {
                _recordingState.value = ServiceState.Idle
            }
        }
    }
}

data class ColoredPath(val path: android.graphics.Path, val color: Int, val strokeWidth: Float)

class DrawingCanvasView(context: Context) : android.view.View(context) {
    private val paths = java.util.ArrayList<ColoredPath>()
    private var currentPath = android.graphics.Path()
    
    var activeColor = android.graphics.Color.RED
    var activeStrokeWidth = 10f

    fun clearCanvas() {
        paths.clear()
        currentPath.reset()
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        
        // Draw historic paths
        for (coloredPath in paths) {
            val paint = android.graphics.Paint().apply {
                color = coloredPath.color
                isAntiAlias = true
                strokeWidth = coloredPath.strokeWidth
                style = android.graphics.Paint.Style.STROKE
                strokeJoin = android.graphics.Paint.Join.ROUND
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            canvas.drawPath(coloredPath.path, paint)
        }
        
        // Draw active path
        val currentPaint = android.graphics.Paint().apply {
            color = activeColor
            isAntiAlias = true
            strokeWidth = activeStrokeWidth
            style = android.graphics.Paint.Style.STROKE
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        canvas.drawPath(currentPath, currentPaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                currentPath = android.graphics.Path()
                currentPath.moveTo(x, y)
                invalidate()
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                currentPath.lineTo(x, y)
                invalidate()
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                currentPath.lineTo(x, y)
                paths.add(ColoredPath(currentPath, activeColor, activeStrokeWidth))
                currentPath = android.graphics.Path() // reset reference
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
