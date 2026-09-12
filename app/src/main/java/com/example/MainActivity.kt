@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.DocumentViewModel
import com.example.ui.BatchDubItem
import com.example.ui.VideoTranscribeScreen
import com.example.ui.MediaTaskHistoryDialog
import com.example.ui.theme.MyApplicationTheme
import com.example.util.SubtitleTranslator
import com.example.util.LanguageOption
import com.example.util.SubtitleEntry
import com.example.ui.InAppFileExplorerDialog
import com.example.ui.FilePickerMode
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: DocumentViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val isDark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            MyApplicationTheme(darkTheme = isDark) {
                var showSettingsDialog by remember { mutableStateOf(false) }
                var selectedTabIndex by remember { mutableIntStateOf(0) } // 0 = Dubbing Studio, 1 = Subtitle Translator

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        CompactTopHeader(
                            viewModel = viewModel,
                            selectedTabIndex = selectedTabIndex,
                            onTabSelected = { selectedTabIndex = it },
                            onOpenSettings = { showSettingsDialog = true }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        AnimatedContent(
                            targetState = selectedTabIndex,
                            transitionSpec = {
                                fadeIn() togetherWith fadeOut()
                            },
                            label = "TabTransition"
                        ) { tab ->
                            when (tab) {
                                0 -> BatchDubScreen(viewModel = viewModel)
                                1 -> StandaloneSubtitleTranslatorScreen(
                                    viewModel = viewModel,
                                    onNavigateToDubbing = { selectedTabIndex = 0 }
                                )
                                2 -> VideoTranscribeScreen(
                                    viewModel = viewModel,
                                    onNavigateToDubbing = { selectedTabIndex = 0 },
                                    onNavigateToTranslator = { selectedTabIndex = 1 }
                                )
                            }
                        }

                        if (showSettingsDialog) {
                            SettingsModalDialog(
                                viewModel = viewModel,
                                onDismiss = { showSettingsDialog = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact, modern, space-efficient Top Bar & Navigation Tab Row
 */
@Composable
fun CompactTopHeader(
    viewModel: DocumentViewModel,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    onOpenSettings: () -> Unit
) {
    val backgroundProcessing by viewModel.backgroundProcessing.collectAsStateWithLifecycle()
    val isGlobalProcessing by viewModel.isGlobalProcessing.collectAsStateWithLifecycle()

    Surface(
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Sleek Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.RecordVoiceOver,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "BalaSpeak Studio",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "AI Video Dubbing & Translation",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Quick background processing toggle badge
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                viewModel.setBackgroundProcessing(!backgroundProcessing)
                            },
                        color = if (backgroundProcessing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(
                            1.dp,
                            if (backgroundProcessing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (backgroundProcessing) Icons.Rounded.Bolt else Icons.Outlined.PowerOff,
                                contentDescription = null,
                                tint = if (backgroundProcessing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (backgroundProcessing) "BG Engine" else "BG Off",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (backgroundProcessing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("hamburger_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Menu,
                            contentDescription = "Studio Settings & Performance",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Compact Navigation Tab Row
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = Color.Transparent,
                divider = { HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) }
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { onTabSelected(0) },
                    modifier = Modifier.height(42.dp),
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Movie,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Video Dubber",
                                fontWeight = if (selectedTabIndex == 0) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    }
                )

                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { onTabSelected(1) },
                    modifier = Modifier.height(42.dp),
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Translate,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Subtitle Translator",
                                fontWeight = if (selectedTabIndex == 1) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    }
                )

                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { onTabSelected(2) },
                    modifier = Modifier.height(42.dp),
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "AI Transcriber",
                                fontWeight = if (selectedTabIndex == 2) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    }
                )
            }
        }
    }
}

/**
 * Screen 1: Batch Video Dubbing Studio
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BatchDubScreen(
    viewModel: DocumentViewModel
) {
    val context = LocalContext.current
    val batchItems by viewModel.batchItems.collectAsStateWithLifecycle()
    val isGlobalProcessing by viewModel.isGlobalProcessing.collectAsStateWithLifecycle()
    val friendlyVoicesList by viewModel.friendlyVoicesList.collectAsStateWithLifecycle()
    val showHiddenFiles by viewModel.showHiddenFiles.collectAsStateWithLifecycle()

    // Keep screen on during active dubbing
    val window = (context as? android.app.Activity)?.window
    DisposableEffect(window) {
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var activePickingItemId by remember { mutableStateOf<String?>(null) }
    var inAppVideoPickerTargetId by remember { mutableStateOf<String?>(null) }
    var inAppSrtPickerTargetId by remember { mutableStateOf<String?>(null) }
    var voiceSelectorItemTarget by remember { mutableStateOf<BatchDubItem?>(null) }
    var translateItemTarget by remember { mutableStateOf<BatchDubItem?>(null) }
    var showDubbingHistoryDialog by remember { mutableStateOf(false) }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val itemId = activePickingItemId
        if (uri != null && itemId != null) {
            viewModel.loadVideoForBatchItem(itemId, uri)
        }
        activePickingItemId = null
    }

    val multiVideoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.loadMultipleVideosForDubbing(uris)
        }
    }

    val srtPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val itemId = activePickingItemId
        if (uri != null && itemId != null) {
            viewModel.loadSrtForBatchItem(itemId, uri)
        }
        activePickingItemId = null
    }

    LaunchedEffect(key1 = true) {
        viewModel.toastMessage.collectLatest { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Compact Dashboard Actions Header
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Dubbing Queue (${batchItems.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Auto-Sync Speed Fit Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // History button
                    IconButton(
                        onClick = { showDubbingHistoryDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = "Saved History",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Add multiple videos button
                    FilledTonalButton(
                        onClick = {
                            try {
                                multiVideoPickerLauncher.launch("video/*")
                            } catch (e: Exception) {
                                multiVideoPickerLauncher.launch("*/*")
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp),
                        enabled = !isGlobalProcessing
                    ) {
                        Icon(Icons.Rounded.VideoLibrary, contentDescription = "Add Videos", modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Videos", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    // Add new task slot
                    OutlinedButton(
                        onClick = { viewModel.addBatchItem() },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("add_item_button"),
                        enabled = !isGlobalProcessing
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Task", modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Task", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    // Process all ready items
                    val readyCount = batchItems.count { it.videoFile != null && it.srtSubtitles.isNotEmpty() }
                    Button(
                        onClick = {
                            if (readyCount == 0) {
                                Toast.makeText(context, "Please select video and subtitle files first!", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.startAllBatchDubbing()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (readyCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("start_all_button"),
                        enabled = !isGlobalProcessing
                    ) {
                        if (isGlobalProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Dub All", modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (isGlobalProcessing) "Dubbing..." else "Dub All ($readyCount)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Batch Queue Items list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 20.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = batchItems,
                key = { _, item -> item.id }
            ) { index, item ->
                BatchDubItemCard(
                    index = index,
                    item = item,
                    totalItems = batchItems.size,
                    friendlyVoicesList = friendlyVoicesList,
                    isGlobalProcessing = isGlobalProcessing,
                    onPickVideo = {
                        inAppVideoPickerTargetId = item.id
                    },
                    onPickSrt = {
                        inAppSrtPickerTargetId = item.id
                    },
                    onOpenTranslator = {
                        translateItemTarget = item
                    },
                    onOpenVoiceSelector = {
                        voiceSelectorItemTarget = item
                    },
                    onUpdateVideoVolume = { viewModel.updateVideoVolume(item.id, it) },
                    onUpdateTtsVolume = { viewModel.updateTtsVolume(item.id, it) },
                    onStartDubbing = { viewModel.startVideoDubbingForBatchItem(item.id) },
                    onReset = { viewModel.resetBatchItem(item.id) },
                    onRemove = { viewModel.removeBatchItem(item.id) },
                    onShare = { viewModel.shareDubbedVideoForBatchItem(item.id) }
                )
            }
        }
    }

    // In-App File Explorer Dialog for Video
    val pickingVideoId = inAppVideoPickerTargetId
    if (pickingVideoId != null) {
        InAppFileExplorerDialog(
            mode = FilePickerMode.VIDEO,
            showHiddenFiles = showHiddenFiles,
            onFileSelected = { file ->
                viewModel.loadVideoFileDirect(pickingVideoId, file)
                inAppVideoPickerTargetId = null
            },
            onDismiss = { inAppVideoPickerTargetId = null },
            onOpenSystemPicker = {
                activePickingItemId = pickingVideoId
                inAppVideoPickerTargetId = null
                videoPickerLauncher.launch("video/*")
            }
        )
    }

    // In-App File Explorer Dialog for Subtitle
    val pickingSrtId = inAppSrtPickerTargetId
    if (pickingSrtId != null) {
        InAppFileExplorerDialog(
            mode = FilePickerMode.SUBTITLE,
            showHiddenFiles = showHiddenFiles,
            onFileSelected = { file ->
                viewModel.loadSrtForBatchItem(pickingSrtId, Uri.fromFile(file))
                inAppSrtPickerTargetId = null
            },
            onDismiss = { inAppSrtPickerTargetId = null },
            onOpenSystemPicker = {
                activePickingItemId = pickingSrtId
                inAppSrtPickerTargetId = null
                srtPickerLauncher.launch("*/*")
            }
        )
    }

    // Modal Voice selector dialog with Voice Audition Preview
    val voiceTarget = voiceSelectorItemTarget
    if (voiceTarget != null) {
        VoiceSelectionDialog(
            viewModel = viewModel,
            currentTarget = voiceTarget,
            friendlyVoicesList = friendlyVoicesList,
            onSelectVoice = { voiceName ->
                viewModel.updateItemVoice(voiceTarget.id, voiceName)
                voiceSelectorItemTarget = null
            },
            onDismiss = {
                viewModel.stopVoicePreview()
                voiceSelectorItemTarget = null
            }
        )
    }

    // Modal In-Card Subtitle Translation Dialog
    val transTarget = translateItemTarget
    if (transTarget != null) {
        SubtitleTranslationDialog(
            viewModel = viewModel,
            item = transTarget,
            onTranslate = { targetLangCode, targetLangName ->
                viewModel.translateSubtitlesForBatchItem(transTarget.id, targetLangCode, targetLangName)
                translateItemTarget = null
            },
            onDismiss = {
                viewModel.stopVoicePreview()
                translateItemTarget = null
            }
        )
    }

    if (showDubbingHistoryDialog) {
        MediaTaskHistoryDialog(
            viewModel = viewModel,
            onDismiss = { showDubbingHistoryDialog = false },
            onSendToDubbing = {
                showDubbingHistoryDialog = false
            }
        )
    }
}

@Composable
fun BatchDubItemCard(
    index: Int,
    item: BatchDubItem,
    totalItems: Int,
    friendlyVoicesList: List<com.example.tts.FriendlyVoice>,
    isGlobalProcessing: Boolean,
    onPickVideo: () -> Unit,
    onPickSrt: () -> Unit,
    onOpenTranslator: () -> Unit,
    onOpenVoiceSelector: () -> Unit,
    onUpdateVideoVolume: (Float) -> Unit,
    onUpdateTtsVolume: (Float) -> Unit,
    onStartDubbing: () -> Unit,
    onReset: () -> Unit,
    onRemove: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("batch_item_card_$index"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (item.isProcessing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Card Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Task #${index + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (totalItems > 1) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(28.dp),
                        enabled = !item.isProcessing && !isGlobalProcessing
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove task",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // File selection containers (Video & Subtitle)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. VIDEO SELECTION CONTAINER
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(86.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (item.videoFile != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (item.videoFile != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable(enabled = !item.isProcessing) { onPickVideo() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(6.dp)
                    ) {
                        if (item.videoFile != null) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                item.videoName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "Video Loaded",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Movie,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "1. Select Video",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "MP4 / MKV / MOV",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 2. SUBTITLE SELECTION CONTAINER
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(86.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (item.srtSubtitles.isNotEmpty()) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (item.srtSubtitles.isNotEmpty()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable(enabled = !item.isProcessing) { onPickSrt() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(6.dp)
                    ) {
                        if (item.srtSubtitles.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                item.srtName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "${item.srtSubtitles.size} Subtitle Lines",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Subtitles,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "2. Select Subtitle",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                "SRT / VTT files",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Auto-Detected Language Badge
            if (item.detectedLanguage != null && item.srtSubtitles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Language: ${item.detectedLanguage.name} (${item.detectedLanguage.nativeName})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                "Auto-Voice Model",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            // Quick In-Card Translation Tool Bar
            if (item.srtSubtitles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Translate Subtitles Online",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    FilledTonalButton(
                        onClick = onOpenTranslator,
                        enabled = !item.isProcessing && !item.isTranslating,
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        if (item.isTranslating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                strokeWidth = 1.5.dp
                            )
                        } else {
                            Text("Translate", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // DUAL AUDIO VOLUME CONTROLS (Video Sound & Dub Voice)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Video Sound (Background sound)
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Video Sound", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (item.videoVolume <= 0.001f) "0% (Turbo)" else "${(item.videoVolume * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (item.videoVolume <= 0.001f) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = item.videoVolume,
                        onValueChange = onUpdateVideoVolume,
                        valueRange = 0f..1f,
                        enabled = !item.isProcessing,
                        modifier = Modifier.height(24.dp)
                    )
                }

                // 2. Dub Voice (TTS Sound)
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Dub Voice Sound", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${(item.ttsVolume * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = item.ttsVolume,
                        onValueChange = onUpdateTtsVolume,
                        valueRange = 0.1f..2.0f,
                        enabled = !item.isProcessing,
                        modifier = Modifier.height(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // VOICE SELECTION & AUTO-ADJUST SPEED ROW
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Voice selector chip
                AssistChip(
                    onClick = onOpenVoiceSelector,
                    enabled = !item.isProcessing,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.RecordVoiceOver,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    label = {
                        val currentVoiceName = item.itemVoiceName ?: "System Voice"
                        val displayVoice = friendlyVoicesList.firstOrNull { it.id == currentVoiceName }?.displayName ?: currentVoiceName
                        Text(
                            displayVoice,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.weight(1.2f)
                )

                // Smart Auto-Adjust Speed Indicator
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Column {
                            Text(
                                "Auto-Adjust Speed",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                            Text(
                                "Subtitle Sync Active",
                                fontSize = 8.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // STATUS & ACTION FOOTER
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            item.dubbedVideoFile != null -> Color(0xFF10B981)
                                            item.videoFile != null && item.srtSubtitles.isNotEmpty() -> MaterialTheme.colorScheme.primary
                                            else -> Color.Gray
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                item.status,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (item.isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = onReset,
                                    modifier = Modifier.size(26.dp),
                                    enabled = !item.isProcessing && !isGlobalProcessing
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Reset",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                Button(
                                    onClick = onStartDubbing,
                                    enabled = item.videoFile != null && item.srtSubtitles.isNotEmpty() && !isGlobalProcessing,
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("Dub Video", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    if (item.progressText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            item.progressText,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Share Dubbed Output
            item.dubbedVideoFile?.let { file ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            file.name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Screen 2: Dedicated Online Subtitle Translator Screen
 */
@Composable
fun StandaloneSubtitleTranslatorScreen(
    viewModel: DocumentViewModel,
    onNavigateToDubbing: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val srtFileName by viewModel.standaloneSrtFileName.collectAsStateWithLifecycle()
    val showHiddenFiles by viewModel.showHiddenFiles.collectAsStateWithLifecycle()
    val originalSubtitles by viewModel.standaloneOriginalSubtitles.collectAsStateWithLifecycle()
    val translatedSubtitles by viewModel.standaloneTranslatedSubtitles.collectAsStateWithLifecycle()
    val isTranslating by viewModel.standaloneIsTranslating.collectAsStateWithLifecycle()
    val progress by viewModel.standaloneTranslateProgress.collectAsStateWithLifecycle()
    val targetLang by viewModel.standaloneTargetLang.collectAsStateWithLifecycle()

    var showTargetLangDialog by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var showInAppSrtPicker by remember { mutableStateOf(false) }

    val srtPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.loadStandaloneSrt(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Control Card (File Selection & Language Pickers)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Online Subtitle Translator",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (originalSubtitles.isNotEmpty()) {
                        Text(
                            "${originalSubtitles.size} Subtitle Cards",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // File Picker / Paste Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showInAppSrtPicker = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (srtFileName.isNotBlank()) srtFileName else "Choose Subtitle (.srt)",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 11.sp
                        )
                    }

                    FilledTonalButton(
                        onClick = { showPasteDialog = true },
                        modifier = Modifier.height(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Paste Text", fontSize = 11.sp)
                    }
                }

                // Language Selection Controls Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Source Lang
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("From: ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Auto-Detect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "to",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )

                    // Target Lang Picker Button
                    Surface(
                        modifier = Modifier
                            .weight(1.3f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showTargetLangDialog = true },
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("To Target:", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                                Text("${targetLang.name} (${targetLang.nativeName})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // Translation Trigger Button
                Button(
                    onClick = { viewModel.translateStandaloneSubtitles() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    enabled = originalSubtitles.isNotEmpty() && !isTranslating,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (isTranslating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Translating online (${progress.first}/${progress.second})...",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    } else {
                        Icon(Icons.Outlined.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Translate Subtitles to ${targetLang.name}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Subtitle View & Translation Comparison List
        if (originalSubtitles.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Subtitles,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "No Subtitles Loaded",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Upload any .srt or .vtt file to translate instantly between 18+ languages online.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Action Bar for Export / Copy / Send to Dubber
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Save to Downloads
                OutlinedButton(
                    onClick = { viewModel.saveStandaloneTranslatedSrtToDownloads() },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.weight(1f).height(34.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save SRT", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                // Copy to Clipboard
                OutlinedButton(
                    onClick = {
                        val text = viewModel.exportStandaloneTranslatedSrt()
                        clipboardManager.setText(AnnotatedString(text))
                        Toast.makeText(context, "Copied SRT to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.weight(1f).height(34.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                // Send to Video Dubbing Queue
                Button(
                    onClick = {
                        viewModel.sendTranslatedToDubbingQueue()
                        onNavigateToDubbing()
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.weight(1.3f).height(34.dp)
                ) {
                    Icon(Icons.Rounded.Movie, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Dub in Studio", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Subtitle entries list
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(originalSubtitles) { idx, originalEntry ->
                    val translatedEntry = translatedSubtitles.getOrNull(idx)
                    SubtitleCardRow(
                        index = idx + 1,
                        original = originalEntry,
                        translated = translatedEntry,
                        targetLangName = targetLang.name
                    )
                }
            }
        }
    }

    // In-App File Explorer Dialog for Subtitle
    if (showInAppSrtPicker) {
        InAppFileExplorerDialog(
            mode = FilePickerMode.SUBTITLE,
            showHiddenFiles = showHiddenFiles,
            onFileSelected = { file ->
                viewModel.loadStandaloneSrtFileDirect(file)
                showInAppSrtPicker = false
            },
            onDismiss = { showInAppSrtPicker = false },
            onOpenSystemPicker = {
                showInAppSrtPicker = false
                srtPickerLauncher.launch("*/*")
            }
        )
    }

    // Modal Target Language Picker Dialog with Voice Audition Preview
    if (showTargetLangDialog) {
        LanguagePickerDialog(
            viewModel = viewModel,
            currentLang = targetLang,
            onSelect = {
                viewModel.setStandaloneTargetLang(it)
                showTargetLangDialog = false
            },
            onDismiss = {
                viewModel.stopVoicePreview()
                showTargetLangDialog = false
            }
        )
    }

    // Paste Text Modal Dialog
    if (showPasteDialog) {
        PasteSubtitleDialog(
            onConfirm = { text ->
                viewModel.loadStandaloneSrtFromText(text)
                showPasteDialog = false
            },
            onDismiss = { showPasteDialog = false }
        )
    }
}

@Composable
fun SubtitleCardRow(
    index: Int,
    original: SubtitleEntry,
    translated: SubtitleEntry?,
    targetLangName: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "#$index",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "${formatTimestamp(original.startTimeMs)} → ${formatTimestamp(original.endTimeMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Original Text
            Text(
                original.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (translated != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )

            // Translated Text
            if (translated != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "[$targetLangName]: ",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        translated.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String {
    val mins = (ms % 3600000) / 60000
    val secs = (ms % 60000) / 1000
    val millis = ms % 1000
    return String.format(Locale.US, "%02d:%02d.%03d", mins, secs, millis)
}

@Composable
fun LanguagePickerDialog(
    viewModel: DocumentViewModel? = null,
    currentLang: LanguageOption,
    onSelect: (LanguageOption) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Choose Target Language",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Tap speaker icon to audition voice",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search language...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                val filtered = SubtitleTranslator.supportedLanguages.filter {
                    searchQuery.isBlank() ||
                            it.name.contains(searchQuery, ignoreCase = true) ||
                            it.nativeName.contains(searchQuery, ignoreCase = true)
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered) { lang ->
                        val isSelected = currentLang.code == lang.code
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(lang) },
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        lang.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "(${lang.nativeName})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (viewModel != null) {
                                        IconButton(
                                            onClick = {
                                                viewModel.previewLanguageVoice(lang.code)
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.VolumeUp,
                                                contentDescription = "Audition voice",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PasteSubtitleDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var rawText by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.70f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Paste Subtitle Text",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    placeholder = { Text("1\n00:00:01,000 --> 00:00:04,000\nHello and welcome!\n\n2\n00:00:04,500 --> 00:00:08,000\nThis is auto-dubbed speech.") },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { onConfirm(rawText) },
                    enabled = rawText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Load Subtitles", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun VoiceSelectionDialog(
    viewModel: DocumentViewModel? = null,
    currentTarget: BatchDubItem,
    friendlyVoicesList: List<com.example.tts.FriendlyVoice>,
    onSelectVoice: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedLanguageFilter by remember { mutableStateOf("All") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.98f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Select TTS Voice",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Tap speaker icon to audition voice",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search voice by name or language...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Quick language filter chips
                val languages = listOf("All", "Bengali", "English", "Hindi", "Urdu", "Arabic", "Spanish", "French")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    languages.forEach { lang ->
                        FilterChip(
                            selected = selectedLanguageFilter == lang,
                            onClick = { selectedLanguageFilter = lang },
                            label = { Text(lang, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val filteredList = friendlyVoicesList.filter { voice ->
                    val matchesSearch = searchQuery.isBlank() ||
                            voice.displayName.contains(searchQuery, ignoreCase = true) ||
                            voice.languageGroup.contains(searchQuery, ignoreCase = true) ||
                            voice.locale.displayName.contains(searchQuery, ignoreCase = true)

                    val matchesFilter = when (selectedLanguageFilter) {
                        "All" -> true
                        "Bengali" -> voice.locale.language.equals("bn", ignoreCase = true)
                        "English" -> voice.locale.language.equals("en", ignoreCase = true)
                        "Hindi" -> voice.locale.language.equals("hi", ignoreCase = true)
                        "Urdu" -> voice.locale.language.equals("ur", ignoreCase = true)
                        "Arabic" -> voice.locale.language.equals("ar", ignoreCase = true)
                        "Spanish" -> voice.locale.language.equals("es", ignoreCase = true)
                        "French" -> voice.locale.language.equals("fr", ignoreCase = true)
                        else -> true
                    }
                    matchesSearch && matchesFilter
                }

                if (filteredList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No matching voices found.\nPlease install desired voice from System TTS settings.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredList) { voice ->
                            val isSelected = currentTarget.itemVoiceName == voice.id
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSelectVoice(voice.id) },
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            voice.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            voice.languageGroup,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (viewModel != null) {
                                            IconButton(
                                                onClick = {
                                                    viewModel.previewVoice(voice.id, voice.locale)
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.VolumeUp,
                                                    contentDescription = "Audition voice",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SubtitleTranslationDialog(
    viewModel: DocumentViewModel? = null,
    item: BatchDubItem,
    onTranslate: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedLang by remember { mutableStateOf(SubtitleTranslator.supportedLanguages.first()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Translate Subtitles Online",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Translate ${item.srtSubtitles.size} subtitle cards with exact timings",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    "Choose Target Language:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(SubtitleTranslator.supportedLanguages) { lang ->
                        val isSelected = selectedLang.code == lang.code
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedLang = lang },
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        lang.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "(${lang.nativeName})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (viewModel != null) {
                                        IconButton(
                                            onClick = {
                                                viewModel.previewLanguageVoice(lang.code)
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.VolumeUp,
                                                contentDescription = "Audition voice",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { onTranslate(selectedLang.code, selectedLang.name) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Outlined.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Translate to ${selectedLang.name}", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun SettingsModalDialog(
    viewModel: DocumentViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val showHiddenFiles by viewModel.showHiddenFiles.collectAsStateWithLifecycle()
    val backgroundProcessing by viewModel.backgroundProcessing.collectAsStateWithLifecycle()
    val customOutputPath by viewModel.customOutputPath.collectAsStateWithLifecycle()

    val globalPitch by viewModel.globalPitch.collectAsStateWithLifecycle()
    
    val performanceProfile by viewModel.performanceProfile.collectAsStateWithLifecycle()
    val workerThreads by viewModel.workerThreads.collectAsStateWithLifecycle()
    val ramBufferSizeKb by viewModel.ramBufferSizeKb.collectAsStateWithLifecycle()
    val translationConcurrency by viewModel.translationConcurrency.collectAsStateWithLifecycle()
    val hardwareAcceleration by viewModel.hardwareAcceleration.collectAsStateWithLifecycle()

    var showFolderPicker by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.98f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.Settings,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Settings & Engine Controls",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Dark Mode, Performance & Storage",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. App Theme / Dark Mode Setting
                    Column {
                        Text(
                            "App Appearance (Theme)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                Triple("system", "System", Icons.Rounded.BrightnessAuto),
                                Triple("dark", "Dark Mode", Icons.Rounded.DarkMode),
                                Triple("light", "Light Mode", Icons.Rounded.LightMode)
                            ).forEach { (mode, label, icon) ->
                                val isSelected = themeMode == mode
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { viewModel.setThemeMode(mode) },
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 1.5 Show Hidden Files Switch
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Show Hidden Files",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Display system & hidden files in the explorer.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = showHiddenFiles,
                                onCheckedChange = { viewModel.setShowHiddenFiles(it) }
                            )
                        }
                    }

                    // 2. Background Processing Service Switch
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Background Processing Engine",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Keep dubbing & translation running in background service even when app is closed or minimized.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = backgroundProcessing,
                                onCheckedChange = { viewModel.setBackgroundProcessing(it) }
                            )
                        }
                    }

                    // 3. Custom Output Directory
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Output Video Save Folder",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                if (customOutputPath != null) customOutputPath!! else "Default: Downloads/BalaSpeak",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { showFolderPicker = true },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Change Folder", fontSize = 11.sp)
                                }

                                if (customOutputPath != null) {
                                    OutlinedButton(
                                        onClick = { viewModel.setCustomOutputPath(null) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text("Reset to Downloads", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }

                    // 4. Gemini AI API Key Setting
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Cloud AI Server",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "Active (Unlimited)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Using high-performance Cloud AI server. Completely free and unlimited. No API keys required.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Hardware Live Telemetry Card
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "⚡ System Hardware Telemetry",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        "TURBO ACTIVE",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 9.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text("CPU Processors", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("${viewModel.detectedCpuCores} Multi-Cores", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text("Max RAM Heap", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("${viewModel.detectedMaxMemoryMb} MB", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // Section: Power & CPU Performance Profile
                    Text(
                        "Processor Power & Performance Profile",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )

                    com.example.ui.TurboPerformanceProfile.values().forEach { profile ->
                        val isSelected = performanceProfile == profile
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { viewModel.setPerformanceProfile(profile) },
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            border = BorderStroke(
                                1.5.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            profile.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                profile.cpuTarget,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        profile.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Active",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Multi-Core CPU Worker Threads Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Dedicated Multi-Core Worker Threads",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    "$workerThreads Threads",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Slider(
                            value = workerThreads.toFloat(),
                            onValueChange = { viewModel.setWorkerThreads(it.toInt()) },
                            valueRange = 1f..16f,
                            steps = 14
                        )
                    }

                    // Online Subtitle Translation Parallel Network Streams Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Online Translation Concurrency Streams",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    "$translationConcurrency Parallel Streams",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Slider(
                            value = translationConcurrency.toFloat(),
                            onValueChange = { viewModel.setTranslationConcurrency(it.toInt()) },
                            valueRange = 1f..16f,
                            steps = 14
                        )
                    }

                    // High-Speed RAM Buffer Cache Allocation
                    Column {
                        Text(
                            "High-Speed RAM Cache Buffer Size",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                16384 to "16 MB",
                                65536 to "64 MB",
                                131072 to "128 MB",
                                262144 to "256 MB",
                                524288 to "512 MB"
                            ).forEach { (sizeKb, label) ->
                                val isSelected = ramBufferSizeKb == sizeKb
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { viewModel.setRamBufferSizeKb(sizeKb) },
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Hardware MediaCodec Acceleration Switch
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "GPU & Hardware MediaCodec Acceleration",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Uses hardware video & audio encoding pipelines for fastest rendering speed.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = hardwareAcceleration,
                                onCheckedChange = { viewModel.setHardwareAcceleration(it) }
                            )
                        }
                    }

                    // Subtitle Synchrony & Timing Info
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    "Automatic Subtitle Timing Sync",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Speech speed is dynamically matched to each subtitle segment's exact start and end duration (1.0x default).",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Global Voice Pitch Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Default Voice Pitch", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text(String.format(Locale.US, "%.2fx", globalPitch), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = globalPitch,
                            onValueChange = { viewModel.setGlobalPitch(it) },
                            valueRange = 0.5f..2.0f
                        )
                    }

                    // System TTS settings link
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent("com.android.settings.TTS_SETTINGS")
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val intent = Intent(Settings.ACTION_SETTINGS)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                } catch (e2: Exception) {}
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.SettingsVoice, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open System TTS Voice Settings", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Apply & Close", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showFolderPicker) {
        InAppFileExplorerDialog(
            mode = FilePickerMode.DIRECTORY,
            showHiddenFiles = showHiddenFiles,
            onFileSelected = { folder ->
                viewModel.setCustomOutputPath(folder.absolutePath)
                showFolderPicker = false
            },
            onDismiss = { showFolderPicker = false }
        )
    }
}
