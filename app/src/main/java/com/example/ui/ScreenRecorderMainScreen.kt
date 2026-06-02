package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Recording
import com.example.service.ScreenRecordService
import com.example.viewmodel.ResolutionPreset
import com.example.viewmodel.ScreenRecorderViewModel
import com.example.viewmodel.TrimState
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenRecorderMainScreen(
    viewModel: ScreenRecorderViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val recordings by viewModel.recordingsHistory.collectAsStateWithLifecycle()
    val activeState by viewModel.activeRecordingState.collectAsStateWithLifecycle()
    val selectedPreset by viewModel.selectedPreset.collectAsStateWithLifecycle()
    val selectedFps by viewModel.selectedFps.collectAsStateWithLifecycle()
    val audioEnabled by viewModel.audioEnabled.collectAsStateWithLifecycle()
    val floatingControlsEnabled by viewModel.floatingControlsEnabled.collectAsStateWithLifecycle()
    val trimState by viewModel.trimState.collectAsStateWithLifecycle()

    var recordingToTrim by remember { mutableStateOf<Recording?>(null) }
    var recordingToDelete by remember { mutableStateOf<Recording?>(null) }

    // Projections management
    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startScreenRecorder(context, result.resultCode, result.data!!)
        } else {
            Toast.makeText(context, "Screen recording permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (audioEnabled && !recordAudioGranted) {
            Toast.makeText(context, "Microphone permission required for audio recording", Toast.LENGTH_SHORT).show()
            viewModel.toggleAudioState(false)
        }
        
        try {
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            projectionLauncher.launch(captureIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Error starting capture helper: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(activeState) {
        val state = activeState
        if (state is ScreenRecordService.ServiceState.Error) {
            Toast.makeText(context, "Error: ${state.message}", Toast.LENGTH_LONG).show()
            viewModel.clearActiveStateError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "SCREEN RECORDER",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.2.sp,
                                fontFamily = FontFamily.SansSerif
                            )
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            // Full width control button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                val isRecording = activeState is ScreenRecordService.ServiceState.Active
                
                Button(
                    onClick = {
                        if (isRecording) {
                            viewModel.stopScreenRecorder(context)
                        } else {
                            // Gather permissions
                            val requiredPerms = mutableListOf<String>()
                            if (audioEnabled) {
                                requiredPerms.add(Manifest.permission.RECORD_AUDIO)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                requiredPerms.add(Manifest.permission.POST_NOTIFICATIONS)
                            }

                            if (requiredPerms.isNotEmpty()) {
                                permissionLauncher.launch(requiredPerms.toTypedArray())
                            } else {
                                try {
                                    val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                                    projectionLauncher.launch(captureIntent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Start error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .testTag("record_trigger_button"),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor = if (isRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isRecording) "STOP RECORDING" else "START SCREEN RECORDING",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        )
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    )
                ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Live Status Banner if recording or paused
            item {
                AnimatedVisibility(
                    visible = activeState is ScreenRecordService.ServiceState.Active,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val recordingInfo = activeState as? ScreenRecordService.ServiceState.Active
                    val isPaused = activeState is ScreenRecordService.ServiceState.Paused
                    RecordingStatusCard(
                        elapsedStr = recordingInfo?.elapsedStr ?: "00:00",
                        settingsStr = "${selectedPreset.label} @ ${selectedFps}FPS",
                        isPaused = isPaused,
                        onPauseToggleClick = {
                            if (isPaused) {
                                viewModel.resumeScreenRecorder(context)
                            } else {
                                viewModel.pauseScreenRecorder(context)
                            }
                        },
                        onStopClick = { viewModel.stopScreenRecorder(context) }
                    )
                }
            }

            // Options Configurations Cards (Only visible if not actively recording)
            item {
                AnimatedVisibility(
                    visible = activeState !is ScreenRecordService.ServiceState.Active,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    ConfigurationPanel(
                        presets = viewModel.resolutionPresets,
                        selectedPreset = selectedPreset,
                        onPresetSelected = { viewModel.selectPreset(it) },
                        selectedFps = selectedFps,
                        onFpsSelected = { viewModel.selectFps(it) },
                        audioEnabled = audioEnabled,
                        onAudioToggled = { viewModel.toggleAudioState(it) },
                        floatingControlsEnabled = floatingControlsEnabled,
                        onFloatingControlsToggled = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(context)) {
                                val intent = Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                                Toast.makeText(context, "Grant overlay drawing permission to display the floating control bar.", Toast.LENGTH_LONG).show()
                            } else {
                                viewModel.toggleFloatingControls(enabled)
                            }
                        }
                    )
                }
            }

            // Action section title
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RECORDINGS GALLERY",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .height(1.dp)
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${recordings.size} files",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            // Empty state placeholder
            if (recordings.isEmpty()) {
                item {
                    EmptyHistoryPlaceholder()
                }
            } else {
                items(recordings, key = { it.id }) { item ->
                    RecordingGalleryItem(
                        recording = item,
                        onPlayClick = { playVideoFile(context, item) },
                        onShareClick = { shareVideoFile(context, item) },
                        onTrimClick = { recordingToTrim = item },
                        onDeleteClick = { recordingToDelete = item }
                    )
                }
            }
        }

        // Dialog for Trimming
        recordingToTrim?.let { recording ->
            TrimDialog(
                recording = recording,
                onDismiss = { recordingToTrim = null },
                onTrimExecute = { start, end ->
                    viewModel.trimRecording(recording, start, end)
                    recordingToTrim = null
                }
            )
        }

        // Dialog for confirming file deletion
        recordingToDelete?.let { recording ->
            AlertDialog(
                onDismissRequest = { recordingToDelete = null },
                title = { Text("Delete Recording") },
                text = { Text("Are you sure you want to permanently delete '${recording.title}' from storage? This cannot be undone.") },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            viewModel.deleteRecording(recording)
                            recordingToDelete = null
                        }
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { recordingToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Status of active or complete trimming
        when (val state = trimState) {
            is TrimState.Trimming -> {
                TrimmingProgressDialog(progress = state.progress)
            }
            is TrimState.Success -> {
                AlertDialog(
                    onDismissRequest = { viewModel.resetTrimState() },
                    icon = { Icon(Icons.Default.Done, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                    title = { Text("Export Completed Lossless", textAlign = TextAlign.Center) },
                    text = { 
                        Text(
                            "Your video was trimmed successfully with full HD format parameters! The file is saved inside your local videos collection.",
                            textAlign = TextAlign.Center
                        ) 
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val trimmedFile = File(state.outputFilePath)
                                if (trimmedFile.exists()) {
                                    val dummyRecording = Recording(
                                        title = trimmedFile.nameWithoutExtension,
                                        filePath = state.outputFilePath,
                                        timestamp = System.currentTimeMillis(),
                                        durationMs = 0,
                                        fileSize = trimmedFile.length(),
                                        width = 0,
                                        height = 0,
                                        bitrate = 0,
                                        fps = 0
                                    )
                                    shareVideoFile(context, dummyRecording)
                                }
                                viewModel.resetTrimState()
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share / Export")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.resetTrimState() }) {
                            Text("Done")
                        }
                    }
                )
            }
            is TrimState.Error -> {
                AlertDialog(
                    onDismissRequest = { viewModel.resetTrimState() },
                    title = { Text("Export failed") },
                    text = { Text(state.message) },
                    confirmButton = {
                        Button(onClick = { viewModel.resetTrimState() }) {
                            Text("Dismiss")
                        }
                    }
                )
            }
            TrimState.Idle -> {}
        }
    }
}

@Composable
fun RecordingStatusCard(
    elapsedStr: String,
    settingsStr: String,
    isPaused: Boolean,
    onPauseToggleClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("active_status_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPaused) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
            },
            contentColor = if (isPaused) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            }
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(
                    if (isPaused) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    MaterialTheme.colorScheme.primary
                )
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (isPaused) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isPaused) "RECORDING PAUSED" else "RECORDING BACKGROUND",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isPaused) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = elapsedStr,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                )
                Text(
                    text = settingsStr,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onPauseToggleClick,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPaused) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = if (isPaused) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Resume Recording" else "Pause Recording",
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onStopClick,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop Recording",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ConfigurationPanel(
    presets: List<ResolutionPreset>,
    selectedPreset: ResolutionPreset,
    onPresetSelected: (ResolutionPreset) -> Unit,
    selectedFps: Int,
    onFpsSelected: (Int) -> Unit,
    audioEnabled: Boolean,
    onAudioToggled: (Boolean) -> Unit,
    floatingControlsEnabled: Boolean,
    onFloatingControlsToggled: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "HD RECORDING PRO PARAMETERS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp
                )
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Preset Selection
            Text(
                text = "Resolution Format",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(8.dp))
            presets.forEach { preset ->
                val isSelected = preset == selectedPreset
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else Color.Transparent
                        )
                        .clickable { onPresetSelected(preset) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onPresetSelected(preset) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            )
                            if (preset.targetWidthLimit == 1080) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                    Text("Full HD", color = MaterialTheme.colorScheme.onPrimary, fontSize = 9.sp, modifier = Modifier.padding(2.dp))
                                }
                            }
                        }
                        Text(
                            text = preset.description,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(16.dp))

            // Row for FPS and Sound
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Audio Toggle
                Column(modifier = Modifier.weight(1.1f)) {
                    Text(
                        text = "Record Audio",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Include mic narration with recording",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }

                Switch(
                    checked = audioEnabled,
                    onCheckedChange = onAudioToggled,
                    thumbContent = {
                        Icon(
                            imageVector = if (audioEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(16.dp))

            // Floating Controls Toggle Option
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1.1f)) {
                    Text(
                        text = "Floating Controls Overlay",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Show responsive mini control pill over other apps",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }

                Switch(
                    checked = floatingControlsEnabled,
                    onCheckedChange = onFloatingControlsToggled,
                    thumbContent = {
                        Icon(
                            imageVector = if (floatingControlsEnabled) Icons.Filled.FeaturedVideo else Icons.Outlined.FeaturedVideo,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1.1f)) {
                    Text(
                        text = "Frame Rate (FPS)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Enable 60 FPS for extra fluid HD footage",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }

                // Smooth Segmented buttons
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp)
                ) {
                    listOf(30, 60).forEach { rate ->
                        val isFpsSelected = rate == selectedFps
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isFpsSelected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .clickable { onFpsSelected(rate) }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${rate} FPS",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isFpsSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyHistoryPlaceholder() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.VideoCameraBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "No recordings yet",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Hit the big record button below to capture your workspace or screen events, trim video frames, and export losslessly in high definition formats.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            )
        }
    }
}

@Composable
fun RecordingGalleryItem(
    recording: Recording,
    onPlayClick: () -> Unit,
    onShareClick: () -> Unit,
    onTrimClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("gallery_item_${recording.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Media Type Icon Indicator
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (recording.isTrimmed) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (recording.isTrimmed) Icons.Default.ContentCut else Icons.Default.Movie,
                        contentDescription = null,
                        tint = if (recording.isTrimmed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = recording.title,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (recording.isTrimmed) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(
                                    "TRIMMED",
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatTimestamp(recording.timestamp),
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    // Technical Tags
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${recording.width}x${recording.height}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                        Text(
                            text = formatDuration(recording.durationMs),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Text(
                            text = formatFileSize(recording.fileSize),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            // Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Floating Preview
                TextButton(
                    onClick = onPlayClick,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("PLAY VIDEO", fontWeight = FontWeight.Bold)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Export/Share
                    IconButton(
                        onClick = onShareClick,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share/Export File",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Trim File Option
                    IconButton(
                        onClick = onTrimClick,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCut,
                            contentDescription = "Trim Video Slices",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Delete Video
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete File",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimDialog(
    recording: Recording,
    onDismiss: () -> Unit,
    onTrimExecute: (startMs: Long, endMs: Long) -> Unit
) {
    var rangeValue by remember { 
        mutableStateOf(0f..(recording.durationMs.toFloat().coerceAtLeast(1000f))) 
    }

    val maxDuration = recording.durationMs.toFloat().coerceAtLeast(1000f)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TRIM HD VIDEO",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Detail Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(16.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Lossless Exporter specs",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Source File: ${recording.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Total Original Length: ${formatDuration(recording.durationMs)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Output Quality: Preservation of original HD dimensions (${recording.width}x${recording.height})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Trimming Selection Slider
                Text(
                    text = "Select Slices Range",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(8.dp))

                RangeSlider(
                    value = rangeValue,
                    onValueChange = { rangeValue = it },
                    valueRange = 0f..maxDuration,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Start: ${formatDuration(rangeValue.start.toLong())}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    )
                    Text(
                        text = "End: ${formatDuration(rangeValue.endInclusive.toLong())}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                val newDuration = (rangeValue.endInclusive - rangeValue.start).toLong()
                
                // Estimate output duration Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Estimated trimmed duration:", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = formatDuration(newDuration),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.secondary,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("CANCEL")
                    }

                    Button(
                        onClick = {
                            onTrimExecute(rangeValue.start.toLong(), rangeValue.endInclusive.toLong())
                        },
                        modifier = Modifier.weight(1f),
                        enabled = newDuration >= 1000f // must be at least 1 second
                    ) {
                        Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("CUT & EXPORT")
                    }
                }
            }
        }
    }
}

@Composable
fun TrimmingProgressDialog(progress: Float) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Card(
            modifier = Modifier
                .width(280.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(54.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Saving Lossless Slices...",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${(progress * 100).toInt()}% extracted",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }
    }
}

// System triggers logic files utilities
private fun playVideoFile(context: Context, recording: Recording) {
    try {
        val file = File(recording.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist in disk storage.", Toast.LENGTH_SHORT).show()
            return
        }

        val authority = "${context.packageName}.fileprovider"
        val videoUri = FileProvider.getUriForFile(context, authority, file)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(videoUri, "video/mp4")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e("ScreenRecorderUI", "Failed to launch video player", e)
        Toast.makeText(context, "No media player found to play MP4 file.", Toast.LENGTH_SHORT).show()
    }
}

private fun shareVideoFile(context: Context, recording: Recording) {
    try {
        val file = File(recording.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "Video file missing.", Toast.LENGTH_SHORT).show()
            return
        }

        val authority = "${context.packageName}.fileprovider"
        val videoUri = FileProvider.getUriForFile(context, authority, file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, videoUri)
            putExtra(Intent.EXTRA_SUBJECT, recording.title)
            putExtra(Intent.EXTRA_TEXT, "Exported screen record file: ${recording.title}")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(Intent.createChooser(intent, "Export HD Video"))
    } catch (e: Exception) {
        Log.e("ScreenRecorderUI", "Failed to share video file", e)
        Toast.makeText(context, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
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
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024f
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024f
    return String.format(Locale.getDefault(), "%.1f MB", mb)
}

private fun formatTimestamp(epochMs: Long): String {
    val date = Date(epochMs)
    val formatter = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
    return formatter.format(date)
}
