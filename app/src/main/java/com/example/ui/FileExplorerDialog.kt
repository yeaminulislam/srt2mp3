package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class FilePickerMode {
    VIDEO,
    SUBTITLE,
    DIRECTORY
}

private fun checkHasStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun InAppFileExplorerDialog(
    mode: FilePickerMode,
    initialDirectory: File? = null,
    showHiddenFiles: Boolean = false,
    onFileSelected: (File) -> Unit = {},
    onDirectorySelected: (File) -> Unit = onFileSelected,
    onOpenSystemPicker: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val defaultRoot = remember {
        val root = Environment.getExternalStorageDirectory()
        if (root != null && root.exists()) root else context.filesDir
    }

    var currentDir by remember {
        mutableStateOf(
            initialDirectory?.takeIf { it.exists() && it.isDirectory }
                ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path).takeIf { it.exists() }
                ?: defaultRoot
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    var showAllFilesMode by remember { mutableStateOf(false) }
    var allowHiddenFiles by remember { mutableStateOf(showHiddenFiles) }
    var refreshTrigger by remember { mutableStateOf(0) }
    
    var hasStoragePermission by remember { 
        mutableStateOf(checkHasStoragePermission(context))
    }

    val manageAllFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasStoragePermission = checkHasStoragePermission(context)
        refreshTrigger++
    }

    val standardPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasStoragePermission = checkHasStoragePermission(context)
        refreshTrigger++
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStoragePermission = checkHasStoragePermission(context)
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val videoExtensions = remember { listOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "m4v", "ts", "wmv") }
    val subtitleExtensions = remember { listOf("srt", "vtt", "txt", "sub", "ass", "ssa", "lrc") }
    val audioExtensions = remember { listOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "opus", "wma") }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var itemToDelete by remember { mutableStateOf<File?>(null) }
    var pendingSelectionFile by remember { mutableStateOf<File?>(null) }

    // Quick shortcuts
    val shortcuts = remember {
        listOf(
            "Downloads" to File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path),
            "Movies" to File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).path),
            "DCIM" to File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).path),
            "Documents" to File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).path),
            "Storage Root" to Environment.getExternalStorageDirectory(),
            "App Storage" to context.getExternalFilesDir(null)
        ).filter { it.second != null && it.second!!.exists() }
    }

    // Listed files and directories in current folder
    val itemsInDir = remember(currentDir, searchQuery, mode, hasStoragePermission, showAllFilesMode, allowHiddenFiles, refreshTrigger) {
        try {
            val list = currentDir.listFiles()?.toList() ?: emptyList()
            list.filter { f ->
                if (!allowHiddenFiles && f.name.startsWith(".")) return@filter false
                if (searchQuery.isNotBlank() && !f.name.contains(searchQuery, ignoreCase = true)) return@filter false

                if (f.isDirectory) {
                    true
                } else if (showAllFilesMode || mode == FilePickerMode.DIRECTORY) {
                    true // Show all files
                } else {
                    val ext = f.extension.lowercase(Locale.ROOT)
                    when (mode) {
                        FilePickerMode.VIDEO -> videoExtensions.contains(ext) || audioExtensions.contains(ext)
                        FilePickerMode.SUBTITLE -> subtitleExtensions.contains(ext)
                        FilePickerMode.DIRECTORY -> true
                    }
                }
            }.sortedWith(
                compareBy<File> { !it.isDirectory }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.90f),
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
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = when (mode) {
                                        FilePickerMode.VIDEO -> Icons.Rounded.Movie
                                        FilePickerMode.SUBTITLE -> Icons.Rounded.Subtitles
                                        FilePickerMode.DIRECTORY -> Icons.Rounded.FolderOpen
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = when (mode) {
                                    FilePickerMode.VIDEO -> "Select Video / Audio File"
                                    FilePickerMode.SUBTITLE -> "Select Subtitle (SRT/TXT)"
                                    FilePickerMode.DIRECTORY -> "Select Output Folder"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (showAllFilesMode) "Showing All Files (*.*)" else when (mode) {
                                    FilePickerMode.VIDEO -> "Supports MP4, MKV, AVI, MOV, WEBM, MP3"
                                    FilePickerMode.SUBTITLE -> "Supports SRT, VTT, TXT, ASS"
                                    FilePickerMode.DIRECTORY -> "Choose folder to save dubbed outputs"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { refreshTrigger++ }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Permission Warning & Request Banner
                if (!hasStoragePermission) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clickable {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    try {
                                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        manageAllFilesLauncher.launch(intent)
                                    } catch (e: Exception) {
                                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                        manageAllFilesLauncher.launch(intent)
                                    }
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    standardPermissionLauncher.launch(
                                        arrayOf(
                                            android.Manifest.permission.READ_MEDIA_VIDEO,
                                            android.Manifest.permission.READ_MEDIA_AUDIO
                                        )
                                    )
                                } else {
                                    standardPermissionLauncher.launch(
                                        arrayOf(
                                            android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                                        )
                                    )
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.LockOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Storage Permission Required",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    "Tap here to grant All Files Access so all your files can be displayed.",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        try {
                                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            manageAllFilesLauncher.launch(intent)
                                        } catch (e: Exception) {
                                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                            manageAllFilesLauncher.launch(intent)
                                        }
                                    } else {
                                        standardPermissionLauncher.launch(
                                            arrayOf(
                                                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                                            )
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Quick Directory Navigation Shortcuts
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(shortcuts) { (label, file) ->
                        val isCurrent = currentDir.absolutePath == file?.absolutePath
                        FilterChip(
                            selected = isCurrent,
                            onClick = { file?.let { currentDir = it } },
                            label = { Text(label, fontSize = 11.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Current Path & Up Navigation
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val parent = currentDir.parentFile
                                if (parent != null && parent.exists() && parent.canRead()) {
                                    currentDir = parent
                                }
                            },
                            enabled = currentDir.parentFile != null && currentDir.parentFile?.canRead() == true,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = "Go Up", modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = currentDir.absolutePath,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Mode Filters & Search Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search in folder...", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(14.dp))
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )

                    FilterChip(
                        selected = showAllFilesMode,
                        onClick = { showAllFilesMode = !showAllFilesMode },
                        label = { Text(if (showAllFilesMode) "All Files" else "Filtered", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                        leadingIcon = {
                            Icon(
                                if (showAllFilesMode) Icons.Rounded.FilterAltOff else Icons.Rounded.FilterAlt,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Files List
                if (itemsInDir.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                            Icon(
                                Icons.Outlined.FolderOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                if (!showAllFilesMode) "No compatible files in this folder." else "This folder is empty.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!showAllFilesMode) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { showAllFilesMode = true },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Show All Files", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(itemsInDir, key = { it.absolutePath }) { item ->
                            val isDir = item.isDirectory
                            val ext = item.extension.lowercase(Locale.ROOT)
                            val isVideo = videoExtensions.contains(ext)
                            val isSubtitle = subtitleExtensions.contains(ext)
                            val isAudio = audioExtensions.contains(ext)

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (isDir) {
                                            currentDir = item
                                        } else {
                                            if (mode == FilePickerMode.DIRECTORY) {
                                                // Directory mode, files not directly selected
                                            } else if (mode == FilePickerMode.VIDEO && (isVideo || isAudio)) {
                                                onFileSelected(item)
                                                onDismiss()
                                            } else if (mode == FilePickerMode.SUBTITLE && isSubtitle) {
                                                onFileSelected(item)
                                                onDismiss()
                                            } else {
                                                // Prompt or directly select in showAllFilesMode
                                                pendingSelectionFile = item
                                            }
                                        }
                                    },
                                color = if (isDir) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                else if ((mode == FilePickerMode.VIDEO && isVideo) || (mode == FilePickerMode.SUBTITLE && isSubtitle))
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    0.8.dp,
                                    if (isDir) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    else if ((mode == FilePickerMode.VIDEO && isVideo) || (mode == FilePickerMode.SUBTITLE && isSubtitle))
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when {
                                            isDir -> Icons.Rounded.Folder
                                            isVideo -> Icons.Rounded.Movie
                                            isAudio -> Icons.Rounded.Audiotrack
                                            isSubtitle -> Icons.Rounded.Subtitles
                                            else -> Icons.Rounded.InsertDriveFile
                                        },
                                        contentDescription = null,
                                        tint = when {
                                            isDir -> MaterialTheme.colorScheme.primary
                                            isVideo -> MaterialTheme.colorScheme.secondary
                                            isAudio -> Color(0xFFE91E63)
                                            isSubtitle -> Color(0xFF4CAF50)
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = item.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (isDir) FontWeight.Bold else FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (!isDir && ext.isNotEmpty()) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    color = when {
                                                        isVideo -> MaterialTheme.colorScheme.primaryContainer
                                                        isSubtitle -> Color(0xFFE8F5E9)
                                                        isAudio -> Color(0xFFFCE4EC)
                                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                                    },
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = ext.uppercase(Locale.ROOT),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = when {
                                                            isVideo -> MaterialTheme.colorScheme.onPrimaryContainer
                                                            isSubtitle -> Color(0xFF2E7D32)
                                                            isAudio -> Color(0xFFC2185B)
                                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                        },
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        if (!isDir) {
                                            val length = item.length()
                                            val sizeStr = when {
                                                length >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", length / (1024.0 * 1024.0))
                                                length >= 1024 -> "${length / 1024} KB"
                                                length > 0 -> "$length B"
                                                else -> "0 KB"
                                            }
                                            val dateStr = try { dateFormat.format(Date(item.lastModified())) } catch (e: Exception) { "" }
                                            Text(
                                                text = "$sizeStr • $dateStr",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (isDir) {
                                        Icon(
                                            Icons.Rounded.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { itemToDelete = item },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenSystemPicker,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("System Picker", fontSize = 12.sp)
                    }

                    if (mode == FilePickerMode.DIRECTORY) {
                        Button(
                            onClick = {
                                onDirectorySelected(currentDir)
                                onDismiss()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Select This Folder", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (pendingSelectionFile != null) {
        val file = pendingSelectionFile!!
        AlertDialog(
            onDismissRequest = { pendingSelectionFile = null },
            title = { Text("Select File") },
            text = { 
                Text("Do you want to use '${file.name}' as the selected ${if (mode == FilePickerMode.VIDEO) "video/audio" else "subtitle"} file?") 
            },
            confirmButton = {
                Button(
                    onClick = {
                        onFileSelected(file)
                        pendingSelectionFile = null
                        onDismiss()
                    }
                ) {
                    Text("Select")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSelectionFile = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Confirm Delete") },
            text = { Text("Are you sure you want to delete '${itemToDelete?.name}'?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            if (itemToDelete?.isDirectory == true) {
                                itemToDelete?.deleteRecursively()
                            } else {
                                itemToDelete?.delete()
                            }
                            refreshTrigger++
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        itemToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
