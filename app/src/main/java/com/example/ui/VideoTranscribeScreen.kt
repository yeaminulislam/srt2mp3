package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.util.SubtitleEntry
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VideoTranscribeScreen(
    viewModel: DocumentViewModel,
    onNavigateToDubbing: () -> Unit,
    onNavigateToTranslator: () -> Unit
) {
    val context = LocalContext.current

    val videoUri by viewModel.transcribeVideoUri.collectAsStateWithLifecycle()
    val videoFile by viewModel.transcribeVideoFile.collectAsStateWithLifecycle()
    val videoName by viewModel.transcribeVideoName.collectAsStateWithLifecycle()
    val videoDurationMs by viewModel.transcribeVideoDurationMs.collectAsStateWithLifecycle()
    val videoSizeBytes by viewModel.transcribeVideoSizeBytes.collectAsStateWithLifecycle()

    val isTranscribing by viewModel.isTranscribing.collectAsStateWithLifecycle()
    val progressText by viewModel.transcribeStatusText.collectAsStateWithLifecycle()
    val progressFraction by viewModel.transcribeProgress.collectAsStateWithLifecycle()
    val progressPercent by viewModel.transcribePercent.collectAsStateWithLifecycle()
    val transcribePhase by viewModel.transcribePhase.collectAsStateWithLifecycle()
    val currentChunk by viewModel.transcribeCurrentChunk.collectAsStateWithLifecycle()
    val totalChunks by viewModel.transcribeTotalChunks.collectAsStateWithLifecycle()

    val targetLanguage by viewModel.transcribeTargetLang.collectAsStateWithLifecycle()
    val srtContent by viewModel.transcribedSrtContent.collectAsStateWithLifecycle()
    val subtitles by viewModel.transcribedSubtitles.collectAsStateWithLifecycle()
    val savedFilePath by viewModel.savedSubtitleFilePath.collectAsStateWithLifecycle()

    val showHiddenFiles by viewModel.showHiddenFiles.collectAsStateWithLifecycle()
    val restoredBanner by viewModel.restoredSessionBanner.collectAsStateWithLifecycle()
    val historyTasks by viewModel.mediaHistoryTasks.collectAsStateWithLifecycle()

    var showInAppVideoPicker by remember { mutableStateOf(false) }
    var showInAppSaveDirPicker by remember { mutableStateOf(false) }
    var showLanguageDropdown by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var editingSubtitleIndex by remember { mutableStateOf<Int?>(null) }
    var editingSubtitleText by remember { mutableStateOf("") }

    val systemMediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = getFileNameFromUriHelper(context, uri)
            viewModel.setTranscribeVideo(uri, null, name)
        }
    }

    val systemMultiMediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val firstUri = uris.first()
            val firstName = getFileNameFromUriHelper(context, firstUri)
            viewModel.setTranscribeVideo(firstUri, null, firstName)

            if (uris.size > 1) {
                viewModel.loadMultipleVideosForDubbing(uris)
                Toast.makeText(context, "${uris.size} files loaded!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val supportedLanguages = listOf(
        "Original" to "Original Spoken (Auto-Detect)",
        "en" to "English",
        "bn" to "Bengali",
        "hi" to "Hindi",
        "ar" to "Arabic",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "ur" to "Urdu",
        "ja" to "Japanese"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Studio Top Bar with Quick History Access
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "AI Subtitle & Transcribe",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Auto-Persistence & Recovery Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                FilledTonalButton(
                    onClick = { showHistoryDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = "History",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "History (${historyTasks.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Auto-Restored Session Notification Banner
            if (restoredBanner != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Restore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Previous session restored",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "${restoredBanner!!.title} • ${if (restoredBanner!!.subtitleCount > 0) "${restoredBanner!!.subtitleCount} subtitles" else "Saved File"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { viewModel.dismissRestoredBanner() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Dismiss",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Step 1: Media File Selection Card (Audio or Video)
            item {
                VideoSelectionCard(
                    videoName = videoName,
                    videoFile = videoFile,
                    videoDurationMs = videoDurationMs,
                    videoSizeBytes = videoSizeBytes,
                    isProcessing = isTranscribing,
                    onBrowseDevice = { showInAppVideoPicker = true },
                    onBrowseSystem = {
                        try {
                            systemMediaPickerLauncher.launch("*/*")
                        } catch (e: Exception) {
                            systemMediaPickerLauncher.launch("video/*")
                        }
                    },
                    onBrowseMultiSystem = {
                        try {
                            systemMultiMediaPickerLauncher.launch("video/*")
                        } catch (e: Exception) {
                            systemMultiMediaPickerLauncher.launch("*/*")
                        }
                    },
                    onOpenHistory = { showHistoryDialog = true },
                    onClearVideo = { viewModel.clearTranscribeSession() }
                )
            }

            // Step 2: Language & AI Model Settings
            item {
                TranscribeOptionsCard(
                    selectedLang = targetLanguage,
                    supportedLanguages = supportedLanguages,
                    showDropdown = showLanguageDropdown,
                    onToggleDropdown = { showLanguageDropdown = !showLanguageDropdown },
                    onSelectLang = {
                        viewModel.setTranscribeTargetLang(it)
                        showLanguageDropdown = false
                    },
                    isProcessing = isTranscribing
                )
            }

            // Step 3: Action & Progress Card with Real-time Percentage & Chunk tracking
            item {
                TranscribeActionProgressCard(
                    hasVideo = videoUri != null || videoFile != null,
                    isTranscribing = isTranscribing,
                    statusText = progressText,
                    progress = progressFraction,
                    percent = progressPercent,
                    phase = transcribePhase,
                    currentChunk = currentChunk,
                    totalChunks = totalChunks,
                    accumulatedCount = subtitles.size,
                    onStartTranscribe = { viewModel.startVideoTranscription() },
                    onCancel = { viewModel.cancelTranscription() }
                )
            }

            // Step 4: Transcribed Output & Subtitle Preview
            if (subtitles.isNotEmpty()) {
                item {
                    SubtitleOutputHeaderCard(
                        subtitleCount = subtitles.size,
                        savedPath = savedFilePath,
                        srtContent = srtContent,
                        onSaveAs = { showInAppSaveDirPicker = true },
                        onSendToDubbing = {
                            if (viewModel.sendTranscribedToDubbing()) {
                                onNavigateToDubbing()
                            }
                        },
                        onSendToTranslator = {
                            if (viewModel.sendTranscribedToTranslator()) {
                                onNavigateToTranslator()
                            }
                        },
                        onCopySrt = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("SRT Subtitles", srtContent))
                            Toast.makeText(context, "Subtitles copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        onShare = {
                            viewModel.shareSrt(srtContent, "${videoName.substringBeforeLast('.')}.srt")
                        }
                    )
                }

                itemsIndexed(subtitles) { idx, entry ->
                    SubtitleEntryCard(
                        entry = entry,
                        isEditing = editingSubtitleIndex == entry.index,
                        editText = editingSubtitleText,
                        onStartEdit = {
                            editingSubtitleIndex = entry.index
                            editingSubtitleText = entry.text
                        },
                        onCancelEdit = {
                            editingSubtitleIndex = null
                            editingSubtitleText = ""
                        },
                        onSaveEdit = { newText ->
                            viewModel.updateTranscribedSubtitleText(entry.index, newText)
                            editingSubtitleIndex = null
                            editingSubtitleText = ""
                        }
                    )
                }
            }
        }
    }

    // In-App File Explorer for Video Selection
    if (showInAppVideoPicker) {
        InAppFileExplorerDialog(
            mode = FilePickerMode.VIDEO,
            initialDirectory = videoFile?.parentFile ?: viewModel.getEffectiveOutputDir(),
            showHiddenFiles = showHiddenFiles,
            onDismiss = { showInAppVideoPicker = false },
            onFileSelected = { selectedFile ->
                viewModel.setTranscribeVideo(Uri.fromFile(selectedFile), selectedFile, selectedFile.name)
                showInAppVideoPicker = false
            }
        )
    }

    // In-App File Explorer for Custom Save Directory
    if (showInAppSaveDirPicker) {
        InAppFileExplorerDialog(
            mode = FilePickerMode.DIRECTORY,
            initialDirectory = videoFile?.parentFile ?: viewModel.getEffectiveOutputDir(),
            showHiddenFiles = showHiddenFiles,
            onDismiss = { showInAppSaveDirPicker = false },
            onFileSelected = { selectedDir ->
                val baseName = videoName.ifBlank { "subtitles" }.substringBeforeLast('.')
                viewModel.saveTranscribedSrt(selectedDir, "$baseName.srt")
                showInAppSaveDirPicker = false
            }
        )
    }

    if (showHistoryDialog) {
        MediaTaskHistoryDialog(
            viewModel = viewModel,
            onDismiss = { showHistoryDialog = false },
            onLoadInTranscribe = { showHistoryDialog = false },
            onSendToDubbing = {
                showHistoryDialog = false
                onNavigateToDubbing()
            }
        )
    }

}

/**
 * Step 1: Media File Selection Card (Audio or Video)
 */
@Composable
private fun VideoSelectionCard(
    videoName: String,
    videoFile: File?,
    videoDurationMs: Long,
    videoSizeBytes: Long,
    isProcessing: Boolean,
    onBrowseDevice: () -> Unit,
    onBrowseSystem: () -> Unit,
    onBrowseMultiSystem: () -> Unit,
    onOpenHistory: () -> Unit,
    onClearVideo: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        ),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.AudioFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "1. Select Audio or Video",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (videoName.isNotBlank() && !isProcessing) {
                    TextButton(
                        onClick = onClearVideo,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Clear", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (videoName.isBlank()) {
                // Empty state: Upload options with Multi-Select and History
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onBrowseDevice,
                            enabled = !isProcessing,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("transcribe_browse_device_button"),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("In-App Folder", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = onBrowseSystem,
                            enabled = !isProcessing,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("transcribe_browse_system_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Rounded.VideoLibrary, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Browse File", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onBrowseMultiSystem,
                            enabled = !isProcessing,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Rounded.VideoCall, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Multiple", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = onOpenHistory,
                            enabled = !isProcessing,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Saved History", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                // Video loaded info card
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = videoName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (videoDurationMs > 0) {
                                        Text(
                                            text = formatDuration(videoDurationMs),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text("•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                    if (videoSizeBytes > 0) {
                                        Text(
                                            text = formatFileSize(videoSizeBytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        if (videoFile != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Path: ${videoFile.parentFile?.absolutePath ?: "Internal"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onBrowseDevice,
                                enabled = !isProcessing,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Change Media", fontSize = 11.sp)
                            }

                            FilledTonalButton(
                                onClick = onBrowseMultiSystem,
                                enabled = !isProcessing,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(Icons.Rounded.VideoCall, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add More", fontSize = 11.sp)
                            }

                            IconButton(
                                onClick = onOpenHistory,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.History,
                                    contentDescription = "History",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Step 2: Language & Mode Configuration Card
 */
@Composable
private fun TranscribeOptionsCard(
    selectedLang: String,
    supportedLanguages: List<Pair<String, String>>,
    showDropdown: Boolean,
    onToggleDropdown: () -> Unit,
    onSelectLang: (String) -> Unit,
    isProcessing: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        ),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Translate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "2. Subtitle Language",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedCard(
                    onClick = { if (!isProcessing) onToggleDropdown() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Target Subtitle Language",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val display = supportedLanguages.firstOrNull { it.first == selectedLang }?.second ?: selectedLang
                            Text(
                                text = display,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }

                DropdownMenu(
                    expanded = showDropdown,
                    onDismissRequest = onToggleDropdown,
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    supportedLanguages.forEach { (code, name) ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (code == selectedLang) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = name,
                                        fontWeight = if (code == selectedLang) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            },
                            onClick = { onSelectLang(code) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Step 3: Main Action & Progress Indicator with Real-Time Percentage & Chunk Tracking
 */
@Composable
private fun TranscribeActionProgressCard(
    hasVideo: Boolean,
    isTranscribing: Boolean,
    statusText: String,
    progress: Float,
    percent: Int,
    phase: String,
    currentChunk: Int,
    totalChunks: Int,
    accumulatedCount: Int,
    onStartTranscribe: () -> Unit,
    onCancel: () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "transcribe_progress"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse_anim")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val isError = !isTranscribing && statusText.startsWith("Error", ignoreCase = true)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                isTranscribing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                else -> MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
            }
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            when {
                isTranscribing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (isTranscribing) {
                // Header with Percentage and Live Pulse
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00C853).copy(alpha = pulseAlpha))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (phase) {
                                "EXTRACTING" -> "Extracting Audio..."
                                "TRANSCRIBING" -> "Generating Subtitles..."
                                else -> "Processing..."
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Large Visible Percentage
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = "$percent%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Smooth Progress Bar
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Badges: Part count & Live Subtitles Count
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (totalChunks > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Part $currentChunk / $totalChunks",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    if (accumulatedCount > 0) {
                        Surface(
                            color = Color(0xFFE8F5E9),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "✅ $accumulatedCount subtitles ready",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF2E7D32),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Detailed Status Message
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Button(
                    onClick = onStartTranscribe,
                    enabled = hasVideo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("start_transcribe_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Extract Audio & Transcribe",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (!hasVideo) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Select an audio or video file above to begin",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                if (isError) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Step 4: Subtitle Output Toolbar & Auto-Save Information
 */
@Composable
private fun SubtitleOutputHeaderCard(
    subtitleCount: Int,
    savedPath: String?,
    srtContent: String,
    onSaveAs: () -> Unit,
    onSendToDubbing: () -> Unit,
    onSendToTranslator: () -> Unit,
    onCopySrt: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        ),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Subtitles, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Subtitles Generated",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                    Text("$subtitleCount Cards", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                }
            }

            if (savedPath != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Saved: $savedPath",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons Row 1: Save As & Copy/Share
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSaveAs,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .testTag("transcribe_save_as_button")
                ) {
                    Icon(Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save to Folder", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onCopySrt,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy SRT", fontSize = 11.sp)
                }

                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(38.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share", modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons Row 2: Workflow Connectors (Dubbing Studio & Translator)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSendToDubbing,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .testTag("send_to_dubbing_button"),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Send to Dubbing", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }

                OutlinedButton(
                    onClick = onSendToTranslator,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Rounded.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Send to Translator", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

/**
 * Individual Subtitle Entry Preview Card with inline edit
 */
@Composable
private fun SubtitleEntryCard(
    entry: SubtitleEntry,
    isEditing: Boolean,
    editText: String,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: (String) -> Unit
) {
    var localText by remember(isEditing, editText) { mutableStateOf(editText) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = "#${entry.index}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = "${formatTimestamp(entry.startTimeMs)} ➔ ${formatTimestamp(entry.endTimeMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }

                val durationSec = ((entry.endTimeMs - entry.startTimeMs) / 1000.0)
                Text(
                    text = String.format(Locale.US, "%.1fs", durationSec),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (isEditing) {
                OutlinedTextField(
                    value = localText,
                    onValueChange = { localText = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancelEdit) {
                        Text("Cancel", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { onSaveEdit(localText) },
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Save", fontSize = 11.sp)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onStartEdit,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit Text", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// Helpers
private fun formatTimestamp(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = ms % 1000
    return String.format(Locale.US, "%02d:%02d,%03d", minutes, seconds, millis)
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", min, sec)
}

private fun formatFileSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", mb)
}

private fun getFileNameFromUriHelper(context: Context, uri: Uri): String {
    var name = "video.mp4"
    try {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex) ?: name
                    }
                }
            }
        } else if (uri.scheme == "file") {
            name = uri.lastPathSegment ?: name
        }
    } catch (e: Exception) {}
    return name
}
