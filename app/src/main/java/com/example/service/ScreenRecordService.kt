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
        hideFloatingControls()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun showFloatingControls() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Floating control permission NOT granted.")
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager

        // Create main container view (LinearLayout)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
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
            container.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))

            val backgroundDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#B31B1E21")) // High translucent midnight slate (70%)
                cornerRadius = dpToPx(28).toFloat()
                setStroke(dpToPx(2), android.graphics.Color.parseColor("#E0E0E0")) // High-contrast border highlight
            }
            container.background = backgroundDrawable

            // Small indicator dot
            val dotView = android.view.View(this).apply {
                val layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(14), dpToPx(14))
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
            container.addView(dotView)

        } else {
            // EXPANDED dynamic dashboard studio controls
            container.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))

            val backgroundDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#F21B1E21")) // Glossy midnight slate
                cornerRadius = dpToPx(28).toFloat()
                setStroke(dpToPx(1), android.graphics.Color.parseColor("#343A40"))
            }
            container.background = backgroundDrawable

            // 1. Status Indicator dot
            val dotView = android.view.View(this).apply {
                val layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).apply {
                    rightMargin = dpToPx(10)
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
            container.addView(dotView)

            // 2. Continuous dynamic timer label
            val stateElapsedText = when (val state = _recordingState.value) {
                is ServiceState.Active -> state.elapsedStr
                else -> "00:00"
            }

            floatingCountTextView = android.widget.TextView(this).apply {
                text = stateElapsedText
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                val layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = dpToPx(12)
                }
                this.layoutParams = layoutParams
            }
            container.addView(floatingCountTextView)

            // 3. Spacing vertical separator lines
            val dividerView = android.view.View(this).apply {
                val layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(1), dpToPx(18)).apply {
                    rightMargin = dpToPx(12)
                }
                this.layoutParams = layoutParams
                setBackgroundColor(android.graphics.Color.parseColor("#495057"))
            }
            container.addView(dividerView)

            // 4. Grouped action button layouts
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
                createFloatActionButton("⏸", "#00E676") {
                    pauseRecording()
                }
            }
            btnLayout.addView(pauseResumeBtn)

            // Padding space
            val space1 = android.view.View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(8), dpToPx(1))
            }
            btnLayout.addView(space1)

            // STOP & SAVE Button (■)
            val saveBtn = createFloatActionButton("■", "#FF1744") {
                stopRecordingAndSave()
                stopSelf()
            }
            btnLayout.addView(saveBtn)

            // Padding space
            val space2 = android.view.View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(8), dpToPx(1))
            }
            btnLayout.addView(space2)

            // Discard Button (✕)
            val discardBtn = createFloatActionButton("✕", "#90A4AE") {
                discardRecording()
                stopSelf()
            }
            btnLayout.addView(discardBtn)

            container.addView(btnLayout)
        }
    }

    private fun createFloatActionButton(text: String, colorHex: String, onClick: () -> Unit): android.widget.TextView {
        return android.widget.TextView(this).apply {
            this.text = text
            setTextColor(android.graphics.Color.parseColor(colorHex))
            textSize = 14f
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
