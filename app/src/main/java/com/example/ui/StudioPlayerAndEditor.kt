package com.example.ui

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.Recording
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "StudioPlayerAndEditor"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioPlayerAndEditorDialog(
    recording: Recording,
    onDismiss: () -> Unit,
    onTrimExecute: (startMs: Long, endMs: Long) -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val context = LocalContext.current
    var isPlayingState by remember { mutableStateOf(false) }
    var currentPositionState by remember { mutableStateOf(0) }
    var durationState by remember { mutableStateOf(recording.durationMs.toInt().coerceAtLeast(1)) }
    var isMuted by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var previewTrimOnly by remember { mutableStateOf(false) }

    var selectedTab by remember { mutableStateOf(0) } // 0 = Player, 1 = Editor Suite

    // Editor Range Slices
    val maxDuration = recording.durationMs.toFloat().coerceAtLeast(1000f)
    var trimRange by remember { mutableStateOf(0f..maxDuration) }
    var prevTrimRange by remember { mutableStateOf(0f..maxDuration) }

    // Android Player Refs
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }

    // Seek helper
    val seekToPosition: (Int) -> Unit = { pos ->
        try {
            videoViewRef?.let {
                it.seekTo(pos)
                currentPositionState = pos
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error seeking to $pos", e)
        }
    }

    // Toggle Play State helper
    val togglePlayback: () -> Unit = {
        videoViewRef?.let {
            if (it.isPlaying) {
                it.pause()
                isPlayingState = false
            } else {
                // If we are at the end, restart
                if (it.currentPosition >= it.duration - 200) {
                    it.seekTo(0)
                    currentPositionState = 0
                }
                it.start()
                isPlayingState = true
            }
        }
    }

    // Clean resource release on Dialog close
    DisposableEffect(Unit) {
        onDispose {
            try {
                videoViewRef?.stopPlayback()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping media playback on dispose", e)
            }
        }
    }

    // Reactively change playback speed
    LaunchedEffect(playbackSpeed, mediaPlayerRef) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                mediaPlayerRef?.let { mp ->
                    val params = mp.playbackParams ?: android.media.PlaybackParams()
                    params.speed = playbackSpeed
                    mp.playbackParams = params
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying dynamic speed $playbackSpeed", e)
            }
        }
    }

    // Periodic time-checker state sync
    LaunchedEffect(isPlayingState, previewTrimOnly, trimRange) {
        while (isPlayingState) {
            videoViewRef?.let { loader ->
                val current = loader.currentPosition
                currentPositionState = current
                val dur = loader.duration
                if (dur > 0) {
                    durationState = dur
                }

                // In Editor Trim limits monitoring
                if (previewTrimOnly && current >= trimRange.endInclusive.toInt()) {
                    loader.pause()
                    loader.seekTo(trimRange.start.toInt())
                    currentPositionState = trimRange.start.toInt()
                    isPlayingState = false
                }
            }
            delay(150)
        }
    }

    // Volume level reactive state sync
    LaunchedEffect(isMuted, mediaPlayerRef) {
        mediaPlayerRef?.let { mp ->
            try {
                val vol = if (isMuted) 0f else 1f
                mp.setVolume(vol, vol)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting media volume", e)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag("studio_screen_scaffold"),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "PRODUCTION STUDIO",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                            )
                            Text(
                                text = "Professional In-App View & Lossless Edit Workspace",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("studio_close_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close Studio")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(paddingValues)
            ) {
                // Video Screen Viewport Box with custom frame styling
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.1f)
                        .background(Color.Black)
                        .clickable { togglePlayback() },
                    contentAlignment = Alignment.Center
                ) {
                    val file = File(recording.filePath)
                    if (file.exists()) {
                        AndroidView(
                            factory = { ctx ->
                                FrameLayout(ctx).apply {
                                    val videoView = VideoView(ctx).apply {
                                        setVideoURI(Uri.fromFile(file))
                                        setOnPreparedListener { mp ->
                                            mediaPlayerRef = mp
                                            mp.isLooping = false
                                            durationState = duration
                                            
                                            // Handle volume safely on startup
                                            val currentVol = if (isMuted) 0f else 1f
                                            mp.setVolume(currentVol, currentVol)

                                            // Apply speed safely on prepared
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                try {
                                                    val params = mp.playbackParams ?: android.media.PlaybackParams()
                                                    params.speed = playbackSpeed
                                                    mp.playbackParams = params
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Prepared speed failure", e)
                                                }
                                            }
                                        }
                                        setOnCompletionListener {
                                            isPlayingState = false
                                        }
                                    }
                                    videoViewRef = videoView
                                    val lp = FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        Gravity.CENTER
                                    )
                                    addView(videoView, lp)
                                }
                            },
                            modifier = Modifier.fillMaxSize().testTag("studio_video_feed")
                        )
                    } else {
                        // Warning state for missing local file
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Video file was not found on local disk space.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            )
                        }
                    }

                    // On-screen overlay controllers (HUD)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isPlayingState) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${formatDuration(currentPositionState.toLong())} / ${formatDuration(durationState.toLong())}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }

                            // Dynamic playback speed badge indicator
                            Text(
                                text = "Speed: ${playbackSpeed}x",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Black
                                )
                            )
                        }
                    }
                }

                // Interactive Navigation Segment (Tabs) between Player Space and Video Cutter Slices
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("STUDIO PLAYER", fontWeight = FontWeight.Bold)
                            }
                        },
                        modifier = Modifier.testTag("studio_tab_player")
                    )

                    Tab(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            // Auto seek to trim start to align nicely
                            seekToPosition(trimRange.start.toInt())
                        },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("HD TIME CUTTER", fontWeight = FontWeight.Bold)
                            }
                        },
                        modifier = Modifier.testTag("studio_tab_cutter")
                    )
                }

                // Workspace Content Panel (Scrollable Card Deck)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (selectedTab == 0) {
                        // PLAYER CONTROLLER DECK
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Timeline slider Seekbar
                            Column {
                                Slider(
                                    value = currentPositionState.toFloat().coerceIn(0f, durationState.toFloat()),
                                    onValueChange = { seekToPosition(it.toInt()) },
                                    valueRange = 0f..durationState.toFloat(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.fillMaxWidth().testTag("studio_timeline_slider")
                                )
                            }

                            // Tactile Control Buttons (Rewind, Play Toggle, Forward, Mute Toggle)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Mute Button
                                FilledIconToggleButton(
                                    checked = isMuted,
                                    onCheckedChange = { isMuted = it },
                                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        checkedContainerColor = MaterialTheme.colorScheme.errorContainer,
                                        checkedContentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (isMuted) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                                        contentDescription = "Mute Toggle"
                                    )
                                }

                                // Rewind -10 SEC
                                IconButton(
                                    onClick = {
                                        val target = (currentPositionState - 10000).coerceAtLeast(0)
                                        seekToPosition(target)
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = MaterialTheme.colorScheme.primary)
                                }

                                // Main Play Pause Pill
                                Button(
                                    onClick = togglePlayback,
                                    modifier = Modifier.height(54.dp).width(120.dp).testTag("studio_play_toggle_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isPlayingState) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                    ),
                                    shape = RoundedCornerShape(27.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPlayingState) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (isPlayingState) "PAUSE" else "PLAY", fontWeight = FontWeight.Bold)
                                }

                                // Fast Forward +10 SEC
                                IconButton(
                                    onClick = {
                                        val target = (currentPositionState + 10000).coerceAtMost(durationState)
                                        seekToPosition(target)
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = MaterialTheme.colorScheme.primary)
                                }

                                // Direct Share
                                IconButton(
                                    onClick = onShareClick,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.primary)
                                }
                            }

                            // Professional Multi-Speed Selector
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Studio Playback Multiplier Speed",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val speeds = listOf(0.5f, 1.0f, 1.5f, 2.0f)
                                        speeds.forEach { speed ->
                                            val isSelected = playbackSpeed == speed
                                            Button(
                                                onClick = { playbackSpeed = speed },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                                ),
                                                modifier = Modifier.weight(1f).height(36.dp),
                                                contentPadding = PaddingValues(0.dp),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text("${speed}x", fontSize = 11.sp, fontWeight = FontWeight.Black)
                                            }
                                        }
                                    }
                                }
                            }

                            // Dynamic Info Card listing specs
                            VideoSpecsInspector(recording)
                        }
                    } else {
                        // TIME CUTTER SUITE (EDITOR WINDOW)
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)),
                                border = CardDefaults.outlinedCardBorder().copy(
                                    brush = Brush.horizontalGradient(
                                        listOf(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.primary)
                                    )
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ContentCut, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Precision Video Trimming Engine",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.tertiary)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "Select boundaries. Dragging handles automatically updates the video viewport below. Save as high definition lossless duplicate.",
                                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    )
                                }
                            }

                            // Interactive Timeline Slices RangeSlider with instant dragging seek trigger
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Trim Slices Selector",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black)
                                    )
                                    Text(
                                        "Clip Size: ${formatDuration((trimRange.endInclusive - trimRange.start).toLong())}",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                RangeSlider(
                                    value = trimRange,
                                    onValueChange = { range ->
                                        // Analyze which handle is dragged
                                        val startChanged = Math.abs(range.start - prevTrimRange.start) > 5.0f
                                        val endChanged = Math.abs(range.endInclusive - prevTrimRange.endInclusive) > 5.0f

                                        if (startChanged) {
                                            seekToPosition(range.start.toInt())
                                        } else if (endChanged) {
                                            seekToPosition(range.endInclusive.toInt())
                                        }
                                        prevTrimRange = range
                                        trimRange = range
                                    },
                                    valueRange = 0f..maxDuration,
                                    modifier = Modifier.fillMaxWidth().testTag("studio_range_slider")
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Start Frame: ${formatDuration(trimRange.start.toLong())}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    )
                                    Text(
                                        text = "End Frame: ${formatDuration(trimRange.endInclusive.toLong())}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    )
                                }
                            }

                            // Precision Millisecond Adjust Increments
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Micro-Precision Step Knobs (+/- 500ms)", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Start adjust buttons
                                        Button(
                                            onClick = {
                                                val nextStart = (trimRange.start - 500f).coerceAtLeast(0f)
                                                trimRange = nextStart..trimRange.endInclusive
                                                seekToPosition(nextStart.toInt())
                                            },
                                            contentPadding = PaddingValues(0.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                                            modifier = Modifier.weight(1f).height(32.dp)
                                        ) {
                                            Text("-0.5s Start", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
                                        }

                                        Button(
                                            onClick = {
                                                val nextStart = (trimRange.start + 500f).coerceAtMost(trimRange.endInclusive - 1000f)
                                                trimRange = nextStart..trimRange.endInclusive
                                                seekToPosition(nextStart.toInt())
                                            },
                                            contentPadding = PaddingValues(0.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                                            modifier = Modifier.weight(1f).height(32.dp)
                                        ) {
                                            Text("+0.5s Start", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
                                        }

                                        // End adjust buttons
                                        Button(
                                            onClick = {
                                                val nextEnd = (trimRange.endInclusive - 500f).coerceAtLeast(trimRange.start + 1000f)
                                                trimRange = trimRange.start..nextEnd
                                                seekToPosition(nextEnd.toInt())
                                            },
                                            contentPadding = PaddingValues(0.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                                            modifier = Modifier.weight(1f).height(32.dp)
                                        ) {
                                            Text("-0.5s End", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
                                        }

                                        Button(
                                            onClick = {
                                                val nextEnd = (trimRange.endInclusive + 500f).coerceAtMost(maxDuration)
                                                trimRange = trimRange.start..nextEnd
                                                seekToPosition(nextEnd.toInt())
                                            },
                                            contentPadding = PaddingValues(0.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                                            modifier = Modifier.weight(1f).height(32.dp)
                                        ) {
                                            Text("+0.5s End", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                }
                            }

                            // Filter to Play Trimmed Only
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .clickable { previewTrimOnly = !previewTrimOnly }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PinEnd, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("Preview Slice Slices Only", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                        Text("Loops playback strictly in selected cut space range", style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                                    }
                                }
                                Switch(
                                    checked = previewTrimOnly,
                                    onCheckedChange = { previewTrimOnly = it },
                                    modifier = Modifier.testTag("studio_preview_trim_switch")
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Large High Contrast Action Cutter Button
                            Button(
                                onClick = {
                                    onTrimExecute(trimRange.start.toLong(), trimRange.endInclusive.toLong())
                                },
                                enabled = (trimRange.endInclusive - trimRange.start) >= 1000f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .testTag("studio_execute_trim_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                )
                            ) {
                                Icon(Icons.Default.ContentCut, contentDescription = null)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("CUT & EXPORT LOSSLESS CLIPS", fontWeight = FontWeight.Black, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoSpecsInspector(recording: Recording) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Video Analytics & File specs",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SpecRow("Title Identifier", recording.title)
                SpecRow("HD Resolution Map", "${recording.width}x${recording.height}")
                SpecRow("Duration Span", formatDuration(recording.durationMs))
                SpecRow("Storage File Size", formatFileSize(recording.fileSize))
                SpecRow("Standard Framerate", "${recording.fps} FPS")
                SpecRow("Export Quality Bitrate", "${(recording.bitrate / 1000000.0 * 10).toInt() / 10.0} Mbps")
                SpecRow("Disk Absolute Path", recording.filePath)
            }
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp)
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSecs = durationMs / 1000
    val minutes = totalSecs / 60
    val seconds = totalSecs % 60
    val milliseconds = (durationMs % 1000) / 100
    return String.format(Locale.getDefault(), "%02d:%02d.%d", minutes, seconds, milliseconds)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
