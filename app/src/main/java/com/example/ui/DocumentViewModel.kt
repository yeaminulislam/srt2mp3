package com.example.ui

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.service.DubbingForegroundService
import com.example.tts.SpeechState
import com.example.tts.TextToSpeechManager
import com.example.tts.FriendlyVoice
import com.example.BuildConfig
import com.example.util.AudioVideoMerger
import com.example.util.DetectedLanguage
import com.example.util.LanguageDetector
import com.example.util.LanguageOption
import com.example.util.SubtitleEntry
import com.example.util.SubtitleParser
import com.example.util.SubtitleTranslator
import com.example.util.VideoTranscriber
import com.example.data.AppDatabase
import com.example.data.MediaTaskHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

data class BatchDubItem(
    val id: String = UUID.randomUUID().toString(),
    val videoUri: Uri? = null,
    val videoFile: File? = null,
    val videoName: String = "",
    val sourceDirectory: File? = null,
    val srtUri: Uri? = null,
    val srtName: String = "",
    val srtContent: String = "",
    val srtSubtitles: List<SubtitleEntry> = emptyList(),
    val detectedLanguage: DetectedLanguage? = null,
    val videoVolume: Float = 0.20f,
    val ttsVolume: Float = 1.0f,
    val itemSpeed: Float = 1.00f,
    val itemPitch: Float = 1.00f,
    val itemVoiceName: String? = null,
    val status: String = "Select video and subtitle files",
    val isProcessing: Boolean = false,
    val isTranslating: Boolean = false,
    val progressText: String = "",
    val dubbedVideoFile: File? = null
)

enum class TurboPerformanceProfile(
    val title: String,
    val description: String,
    val cpuTarget: String,
    val defaultThreads: Int,
    val bufferMb: Int
) {
    BEAST_MODE("Turbo Beast Mode", "90%+ CPU Multi-Threading, Max RAM & GPU Speed", "90% - 100%", 12, 512),
    ULTRA_PERFORMANCE("Ultra Performance", "75% Multi-Core parallel processing", "75%", 8, 256),
    BALANCED("Balanced Mode", "50% CPU allocation with steady battery", "50%", 4, 128),
    BATTERY_SAVER("Battery Saver", "25% Low-power background execution", "25%", 2, 64)
}

class DocumentViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences = application.getSharedPreferences("balaspeak_studio_prefs", Context.MODE_PRIVATE)

    val ttsManager = TextToSpeechManager(application)

    // Hardware Telemetry & Turbo Optimization State
    val detectedCpuCores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
    val detectedMaxMemoryMb: Long = Runtime.getRuntime().maxMemory() / (1024 * 1024)

    // Theme Mode Setting ("system", "dark", "light")
    private val _themeMode = MutableStateFlow(prefs.getString("pref_theme_mode", "system") ?: "system")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _performanceProfile = MutableStateFlow(
        try {
            TurboPerformanceProfile.valueOf(prefs.getString("pref_perf_profile", TurboPerformanceProfile.BEAST_MODE.name) ?: TurboPerformanceProfile.BEAST_MODE.name)
        } catch (e: Exception) {
            TurboPerformanceProfile.BEAST_MODE
        }
    )
    val performanceProfile: StateFlow<TurboPerformanceProfile> = _performanceProfile.asStateFlow()

    private val _workerThreads = MutableStateFlow(
        prefs.getInt("pref_worker_threads", minOf(16, maxOf(4, detectedCpuCores * 2)))
    )
    val workerThreads: StateFlow<Int> = _workerThreads.asStateFlow()

    private val _ramBufferSizeMb = MutableStateFlow(
        prefs.getInt("pref_ram_buffer_mb", 256)
    )
    val ramBufferSizeMb: StateFlow<Int> = _ramBufferSizeMb.asStateFlow()

    private val _translationConcurrency = MutableStateFlow(
        prefs.getInt("pref_translation_concurrency", 8)
    )
    val translationConcurrency: StateFlow<Int> = _translationConcurrency.asStateFlow()

    private val _hardwareAcceleration = MutableStateFlow(
        prefs.getBoolean("pref_hardware_accel", true)
    )
    val hardwareAcceleration: StateFlow<Boolean> = _hardwareAcceleration.asStateFlow()

    private val _showHiddenFiles = MutableStateFlow(
        prefs.getBoolean("pref_show_hidden_files", false)
    )
    val showHiddenFiles: StateFlow<Boolean> = _showHiddenFiles.asStateFlow()

    private val _backgroundProcessingActive = MutableStateFlow(
        prefs.getBoolean("pref_background_active", false)
    )
    val backgroundProcessingActive: StateFlow<Boolean> = _backgroundProcessingActive.asStateFlow()
    val backgroundProcessing: StateFlow<Boolean> = _backgroundProcessingActive.asStateFlow()

    private val _customOutputDirPath = MutableStateFlow(
        prefs.getString("pref_custom_output_dir", "") ?: ""
    )
    val customOutputDirPath: StateFlow<String> = _customOutputDirPath.asStateFlow()
    val customOutputPath: StateFlow<String?> = _customOutputDirPath.map { if (it.isBlank()) null else it }.stateIn(viewModelScope, SharingStarted.Eagerly, if (_customOutputDirPath.value.isBlank()) null else _customOutputDirPath.value)

    val ramBufferSizeKb: StateFlow<Int> = _ramBufferSizeMb.map { it * 1024 }.stateIn(viewModelScope, SharingStarted.Eagerly, _ramBufferSizeMb.value * 1024)

    // Batch Dubbing Queue
    private val _batchItems = MutableStateFlow<List<BatchDubItem>>(listOf(BatchDubItem()))
    val batchItems: StateFlow<List<BatchDubItem>> = _batchItems.asStateFlow()

    private val _isGlobalProcessing = MutableStateFlow(false)
    val isGlobalProcessing: StateFlow<Boolean> = _isGlobalProcessing.asStateFlow()

    private var globalProcessingJob: kotlinx.coroutines.Job? = null

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage = _toastMessage.asSharedFlow()

    // Standalone Subtitle Translator State
    private val _standaloneSrtFileName = MutableStateFlow("")
    val standaloneSrtFileName: StateFlow<String> = _standaloneSrtFileName.asStateFlow()

    private val _standaloneOriginalSubtitles = MutableStateFlow<List<SubtitleEntry>>(emptyList())
    val standaloneOriginalSubtitles: StateFlow<List<SubtitleEntry>> = _standaloneOriginalSubtitles.asStateFlow()

    private val _standaloneTranslatedSubtitles = MutableStateFlow<List<SubtitleEntry>>(emptyList())
    val standaloneTranslatedSubtitles: StateFlow<List<SubtitleEntry>> = _standaloneTranslatedSubtitles.asStateFlow()

    private val _standaloneIsTranslating = MutableStateFlow(false)
    val standaloneIsTranslating: StateFlow<Boolean> = _standaloneIsTranslating.asStateFlow()

    private val _standaloneTranslateProgress = MutableStateFlow(Pair(0, 0))
    val standaloneTranslateProgress: StateFlow<Pair<Int, Int>> = _standaloneTranslateProgress.asStateFlow()

    private val _standaloneSourceLang = MutableStateFlow("auto")
    val standaloneSourceLang: StateFlow<String> = _standaloneSourceLang.asStateFlow()

    private val _standaloneTargetLang = MutableStateFlow(
        SubtitleTranslator.supportedLanguages.firstOrNull { it.code == "en" } ?: SubtitleTranslator.supportedLanguages.first()
    )
    val standaloneTargetLang: StateFlow<LanguageOption> = _standaloneTargetLang.asStateFlow()

    // --- AI Video Transcription State ---
    private val _geminiApiKey = MutableStateFlow(
        prefs.getString("pref_gemini_api_key", "") ?: ""
    )
    val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

    private val _transcribeVideoUri = MutableStateFlow<Uri?>(null)
    val transcribeVideoUri: StateFlow<Uri?> = _transcribeVideoUri.asStateFlow()

    private val _transcribeVideoFile = MutableStateFlow<File?>(null)
    val transcribeVideoFile: StateFlow<File?> = _transcribeVideoFile.asStateFlow()

    private val _transcribeVideoName = MutableStateFlow("")
    val transcribeVideoName: StateFlow<String> = _transcribeVideoName.asStateFlow()

    private val _transcribeVideoDurationMs = MutableStateFlow(0L)
    val transcribeVideoDurationMs: StateFlow<Long> = _transcribeVideoDurationMs.asStateFlow()

    private val _transcribeVideoSizeBytes = MutableStateFlow(0L)
    val transcribeVideoSizeBytes: StateFlow<Long> = _transcribeVideoSizeBytes.asStateFlow()

    private val _transcribeTargetLang = MutableStateFlow("Original")
    val transcribeTargetLang: StateFlow<String> = _transcribeTargetLang.asStateFlow()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _transcribeStatusText = MutableStateFlow("Select audio or video to start transcribing")
    val transcribeStatusText: StateFlow<String> = _transcribeStatusText.asStateFlow()

    private val _transcribeProgress = MutableStateFlow(0f)
    val transcribeProgress: StateFlow<Float> = _transcribeProgress.asStateFlow()

    private val _transcribeCurrentChunk = MutableStateFlow(0)
    val transcribeCurrentChunk: StateFlow<Int> = _transcribeCurrentChunk.asStateFlow()

    private val _transcribeTotalChunks = MutableStateFlow(0)
    val transcribeTotalChunks: StateFlow<Int> = _transcribeTotalChunks.asStateFlow()

    private val _transcribePercent = MutableStateFlow(0)
    val transcribePercent: StateFlow<Int> = _transcribePercent.asStateFlow()

    private val _transcribePhase = MutableStateFlow("IDLE")
    val transcribePhase: StateFlow<String> = _transcribePhase.asStateFlow()

    private var transcriptionJob: kotlinx.coroutines.Job? = null

    private val _transcribedSrtContent = MutableStateFlow("")
    val transcribedSrtContent: StateFlow<String> = _transcribedSrtContent.asStateFlow()

    private val _transcribedSubtitles = MutableStateFlow<List<SubtitleEntry>>(emptyList())
    val transcribedSubtitles: StateFlow<List<SubtitleEntry>> = _transcribedSubtitles.asStateFlow()

    private val _savedSubtitleFilePath = MutableStateFlow<String?>(null)
    val savedSubtitleFilePath: StateFlow<String?> = _savedSubtitleFilePath.asStateFlow()

    // Room Database & Persistent History for Tasks and Crash/Close Recovery
    private val database = AppDatabase.getDatabase(application)
    val mediaTaskHistoryDao = database.mediaTaskHistoryDao()

    val mediaHistoryTasks: StateFlow<List<MediaTaskHistory>> = mediaTaskHistoryDao.getAllHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentActiveHistoryTaskId = MutableStateFlow<Long?>(null)
    val currentActiveHistoryTaskId: StateFlow<Long?> = _currentActiveHistoryTaskId.asStateFlow()

    private val _restoredSessionBanner = MutableStateFlow<MediaTaskHistory?>(null)
    val restoredSessionBanner: StateFlow<MediaTaskHistory?> = _restoredSessionBanner.asStateFlow()

    val speechState: StateFlow<SpeechState> = ttsManager.speechState
    val availableVoices: StateFlow<List<Voice>> = ttsManager.availableVoices
    val friendlyVoicesList: StateFlow<List<FriendlyVoice>> = ttsManager.availableVoices
        .map { ttsManager.getFriendlyVoicesList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val previewingVoiceId: StateFlow<String?> = ttsManager.previewingVoiceId

    val globalSpeed: StateFlow<Float> = ttsManager.speed
    val globalPitch: StateFlow<Float> = ttsManager.pitch
    val globalSelectedVoice: StateFlow<String?> = ttsManager.selectedVoice
    val autoFitDuration: StateFlow<Boolean> = ttsManager.autoFitSubtitleDuration

    init {
        com.example.service.StopProcessingReceiver.onStopProcessing = {
            stopAllProcessing()
        }
        // Restore persistent speech settings (Default 1.0f speed + locked Auto-Fit for subtitle synchrony)
        val savedPitch = prefs.getFloat("pref_global_pitch", 1.0f)
        val savedVoice = prefs.getString("pref_global_voice", null)
        ttsManager.setSpeed(1.0f)
        ttsManager.setPitch(savedPitch)
        ttsManager.setAutoFitSubtitleDuration(true)
        if (savedVoice != null) {
            ttsManager.setVoice(savedVoice)
        }

        // Auto-recover last active session (if user exited app or it closed unexpectedly)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lastId = prefs.getLong("pref_last_active_task_id", -1L)
                val task = if (lastId != -1L) {
                    mediaTaskHistoryDao.getTaskById(lastId)
                } else {
                    mediaTaskHistoryDao.getLatestTask()
                }

                if (task != null && (task.srtContent.isNotBlank() || !task.filePath.isNullOrBlank() || !task.fileUri.isNullOrBlank())) {
                    restoreSessionFromHistory(task, auto = true)
                }
            } catch (e: Exception) {
                Log.w("DocumentViewModel", "Could not auto-recover task: ${e.message}")
            }
        }
    }

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        prefs.edit().putString("pref_theme_mode", mode).apply()
    }

    fun setPerformanceProfile(profile: TurboPerformanceProfile) {
        _performanceProfile.value = profile
        _workerThreads.value = profile.defaultThreads
        _ramBufferSizeMb.value = profile.bufferMb
        prefs.edit()
            .putString("pref_perf_profile", profile.name)
            .putInt("pref_worker_threads", profile.defaultThreads)
            .putInt("pref_ram_buffer_mb", profile.bufferMb)
            .apply()
    }

    fun setWorkerThreads(threads: Int) {
        val bounded = threads.coerceIn(1, 16)
        _workerThreads.value = bounded
        prefs.edit().putInt("pref_worker_threads", bounded).apply()
    }

    fun setRamBufferSizeMb(mb: Int) {
        _ramBufferSizeMb.value = mb
        prefs.edit().putInt("pref_ram_buffer_mb", mb).apply()
    }

    fun setTranslationConcurrency(concurrency: Int) {
        val bounded = concurrency.coerceIn(1, 16)
        _translationConcurrency.value = bounded
        prefs.edit().putInt("pref_translation_concurrency", bounded).apply()
    }

    fun setHardwareAcceleration(enabled: Boolean) {
        _hardwareAcceleration.value = enabled
        prefs.edit().putBoolean("pref_hardware_accel", enabled).apply()
    }

    fun setRamBufferSizeKb(kb: Int) {
        setRamBufferSizeMb(maxOf(16, kb / 1024))
    }

    fun setBackgroundProcessing(enabled: Boolean) = setBackgroundProcessingActive(enabled)

    fun setShowHiddenFiles(enabled: Boolean) {
        _showHiddenFiles.value = enabled
        prefs.edit().putBoolean("pref_show_hidden_files", enabled).apply()
    }

    fun setBackgroundProcessingActive(enabled: Boolean) {
        _backgroundProcessingActive.value = enabled
        prefs.edit().putBoolean("pref_background_active", enabled).apply()
        if (enabled) {
            DubbingForegroundService.startService(
                getApplication(),
                "BalaSpeak Studio Active",
                "Turbo background engine running..."
            )
        } else {
            DubbingForegroundService.stopService(getApplication())
        }
    }

    fun setCustomOutputPath(path: String?) {
        setCustomOutputDirPath(path ?: "")
    }

    fun setCustomOutputDirPath(path: String) {
        _customOutputDirPath.value = path
        prefs.edit().putString("pref_custom_output_dir", path).apply()
    }

    fun setGlobalSpeed(value: Float) {
        ttsManager.setSpeed(value)
        prefs.edit().putFloat("pref_global_speed", value).apply()
    }

    fun setGlobalPitch(value: Float) {
        ttsManager.setPitch(value)
        prefs.edit().putFloat("pref_global_pitch", value).apply()
    }

    fun setAutoFitDuration(enabled: Boolean) {
        ttsManager.setAutoFitSubtitleDuration(enabled)
        prefs.edit().putBoolean("pref_auto_fit", enabled).apply()
    }

    fun selectGlobalVoice(voiceName: String) {
        ttsManager.setVoice(voiceName)
        prefs.edit().putString("pref_global_voice", voiceName).apply()
    }

    // Voice Audition / Preview Methods
    fun previewVoice(voice: FriendlyVoice?) {
        ttsManager.previewVoice(voice, _batchItems.value.firstOrNull()?.itemSpeed ?: globalSpeed.value, _batchItems.value.firstOrNull()?.itemPitch ?: globalPitch.value)
    }

    fun previewVoice(voiceId: String, locale: java.util.Locale? = null) {
        val friendly = friendlyVoicesList.value.firstOrNull { it.id == voiceId }
        if (friendly != null) {
            previewVoice(friendly)
        } else {
            ttsManager.previewLanguageVoice(locale?.language ?: "en", globalSpeed.value, globalPitch.value)
        }
    }

    fun previewLanguageVoice(langCode: String) {
        ttsManager.previewLanguageVoice(langCode, globalSpeed.value, globalPitch.value)
    }

    fun stopVoicePreview() {
        ttsManager.stopVoicePreview()
    }

    fun addBatchItem() {
        val currentList = _batchItems.value.toMutableList()
        currentList.add(BatchDubItem())
        _batchItems.value = currentList
    }

    fun removeBatchItem(itemId: String) {
        val currentList = _batchItems.value.filterNot { it.id == itemId }
        _batchItems.value = if (currentList.isEmpty()) listOf(BatchDubItem()) else currentList
    }

    private fun updateBatchItem(itemId: String, transform: (BatchDubItem) -> BatchDubItem) {
        _batchItems.value = _batchItems.value.map { item ->
            if (item.id == itemId) transform(item) else item
        }
    }

    fun updateVideoVolume(itemId: String, volume: Float) {
        updateBatchItem(itemId) { it.copy(videoVolume = volume.coerceIn(0f, 1f)) }
    }

    fun updateTtsVolume(itemId: String, volume: Float) {
        updateBatchItem(itemId) { it.copy(ttsVolume = volume.coerceIn(0f, 2f)) }
    }

    fun updateItemSpeed(itemId: String, speedVal: Float) {
        updateBatchItem(itemId) { it.copy(itemSpeed = speedVal.coerceIn(0.5f, 2.5f)) }
    }

    fun updateItemPitch(itemId: String, pitchVal: Float) {
        updateBatchItem(itemId) { it.copy(itemPitch = pitchVal.coerceIn(0.5f, 2.0f)) }
    }

    fun updateItemVoice(itemId: String, voiceName: String?) {
        updateBatchItem(itemId) { it.copy(itemVoiceName = voiceName) }
    }

    fun getFileNameFromUri(uri: Uri): String {
        var name = ""
        try {
            val cursor = getApplication<Application>().contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        name = it.getString(nameIdx)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("BalaSpeakTTS", "Error getting file name: ${e.message}")
        }
        if (name.isEmpty()) {
            name = uri.lastPathSegment ?: "unknown_video.mp4"
        }
        return name
    }

    private fun resolveRealFileFromUri(uri: Uri): File? {
        try {
            if (uri.scheme == "file") {
                val path = uri.path
                if (!path.isNullOrBlank()) {
                    val f = File(path)
                    if (f.exists()) return f
                }
            }
            if (uri.scheme == "content") {
                val proj = arrayOf(MediaStore.MediaColumns.DATA)
                getApplication<Application>().contentResolver.query(uri, proj, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (idx != -1) {
                            val path = cursor.getString(idx)
                            if (!path.isNullOrBlank()) {
                                val f = File(path)
                                if (f.exists()) return f
                            }
                        }
                    }
                }

                val docId = try {
                    if (DocumentsContract.isDocumentUri(getApplication(), uri)) {
                        DocumentsContract.getDocumentId(uri)
                    } else null
                } catch (e: Exception) { null }

                if (docId != null) {
                    if (docId.startsWith("raw:")) {
                        val f = File(docId.removePrefix("raw:"))
                        if (f.exists()) return f
                    } else if (docId.startsWith("primary:")) {
                        val relPath = docId.removePrefix("primary:")
                        val f = File(Environment.getExternalStorageDirectory(), relPath)
                        if (f.exists()) return f
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("BalaSpeakTTS", "Could not resolve real file from uri: ${e.message}")
        }
        return null
    }

    fun loadVideoForBatchItem(itemId: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                updateBatchItem(itemId) {
                    it.copy(
                        status = "Loading video file...",
                        progressText = "Reading video data..."
                    )
                }

                val name = getFileNameFromUri(uri)
                val cleanName = if (name.contains("/")) name.substringAfterLast("/") else name

                // Try to resolve real file path directly to preserve original source directory
                val realFile = resolveRealFileFromUri(uri)
                val sourceDir = realFile?.parentFile

                val videoCacheFile = if (realFile != null && realFile.exists() && realFile.canRead()) {
                    realFile
                } else {
                    val temp = File(getApplication<Application>().cacheDir, "source_${itemId}_${System.currentTimeMillis()}.mp4")
                    val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        val outputStream = FileOutputStream(temp)
                        inputStream.copyTo(outputStream)
                        inputStream.close()
                        outputStream.close()
                        temp
                    } else null
                }

                if (videoCacheFile != null && videoCacheFile.exists() && videoCacheFile.length() > 0) {
                    updateBatchItem(itemId) { item ->
                        val isReady = item.srtSubtitles.isNotEmpty()
                        item.copy(
                            videoUri = uri,
                            videoFile = videoCacheFile,
                            videoName = cleanName,
                            sourceDirectory = sourceDir ?: if (videoCacheFile != realFile) null else videoCacheFile.parentFile,
                            status = if (isReady) "Ready for Dubbing" else "Add Subtitle (SRT) File",
                            progressText = ""
                        )
                    }
                } else {
                    updateBatchItem(itemId) { it.copy(status = "Failed to load video", progressText = "") }
                    _toastMessage.emit("Cannot open video stream!")
                }
            } catch (e: Exception) {
                updateBatchItem(itemId) { it.copy(status = "Video error: ${e.message}", progressText = "") }
                _toastMessage.emit("Video load error: ${e.message}")
            }
        }
    }

    fun loadVideoFileDirect(itemId: String, file: File) {
        if (!file.exists() || !file.canRead()) {
            viewModelScope.launch { _toastMessage.emit("Selected video file cannot be read") }
            return
        }
        updateBatchItem(itemId) { item ->
            val isReady = item.srtSubtitles.isNotEmpty()
            item.copy(
                videoFile = file,
                videoName = file.name,
                sourceDirectory = file.parentFile,
                videoUri = Uri.fromFile(file),
                status = if (isReady) "Ready for Dubbing" else "Add Subtitle (SRT) File",
                progressText = ""
            )
        }
    }

    fun loadMultipleVideosForDubbing(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val newItems = mutableListOf<BatchDubItem>()
            for (uri in uris) {
                try {
                    val name = getFileNameFromUri(uri)
                    val cleanName = if (name.contains("/")) name.substringAfterLast("/") else name
                    val realFile = resolveRealFileFromUri(uri)
                    val sourceDir = realFile?.parentFile

                    val videoCacheFile = if (realFile != null && realFile.exists() && realFile.canRead()) {
                        realFile
                    } else {
                        val tempId = UUID.randomUUID().toString().take(8)
                        val temp = File(getApplication<Application>().cacheDir, "source_batch_${tempId}_${System.currentTimeMillis()}.mp4")
                        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(temp).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (temp.exists() && temp.length() > 0) temp else null
                    }

                    val item = BatchDubItem(
                        videoUri = uri,
                        videoFile = videoCacheFile,
                        videoName = cleanName,
                        sourceDirectory = sourceDir ?: videoCacheFile?.parentFile,
                        status = "Add Subtitle (SRT) File"
                    )
                    newItems.add(item)
                } catch (e: Exception) {
                    Log.e("DocumentViewModel", "Failed to load video uri: ${e.message}")
                }
            }

            if (newItems.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    val current = _batchItems.value.toMutableList()
                    if (current.size == 1 && current[0].videoFile == null && current[0].srtSubtitles.isEmpty()) {
                        _batchItems.value = newItems
                    } else {
                        current.addAll(newItems)
                        _batchItems.value = current
                    }
                    _toastMessage.emit("${newItems.size} video(s) added to dubbing list!")
                }
            }
        }
    }

    fun loadMultipleVideoFilesDirect(files: List<File>) {
        if (files.isEmpty()) return
        val validFiles = files.filter { it.exists() && it.canRead() }
        if (validFiles.isEmpty()) return

        val newItems = validFiles.map { file ->
            BatchDubItem(
                videoFile = file,
                videoName = file.name,
                sourceDirectory = file.parentFile,
                videoUri = Uri.fromFile(file),
                status = "Add Subtitle (SRT) File"
            )
        }
        val current = _batchItems.value.toMutableList()
        if (current.size == 1 && current[0].videoFile == null && current[0].srtSubtitles.isEmpty()) {
            _batchItems.value = newItems
        } else {
            current.addAll(newItems)
            _batchItems.value = current
        }
        viewModelScope.launch {
            _toastMessage.emit("${newItems.size} video(s) added to dubbing list!")
        }
    }

    fun loadSrtForBatchItem(itemId: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                updateBatchItem(itemId) { it.copy(status = "Parsing subtitle file...") }

                val name = getFileNameFromUri(uri)
                val cleanName = if (name.contains("/")) name.substringAfterLast("/") else name

                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                val content = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                val entries = SubtitleParser.parseSrtOrVtt(content)

                if (entries.isEmpty()) {
                    updateBatchItem(itemId) { it.copy(status = "Empty or invalid subtitle format") }
                    _toastMessage.emit("No valid subtitle timestamps found!")
                    return@launch
                }

                // Automatic language detection from parsed subtitle text
                val sampleText = entries.take(15).joinToString(" ") { it.text }
                val detected = LanguageDetector.detectLanguage(sampleText)
                val bestVoice = ttsManager.findBestVoiceForLanguage(detected.code)
                val autoVoiceName = bestVoice?.name

                updateBatchItem(itemId) { item ->
                    val isReady = item.videoFile != null && item.videoFile.exists()
                    item.copy(
                        srtUri = uri,
                        srtName = cleanName,
                        srtContent = content,
                        srtSubtitles = entries,
                        detectedLanguage = detected,
                        itemVoiceName = autoVoiceName ?: item.itemVoiceName,
                        status = if (isReady) "Ready for Dubbing • ${detected.name} (${detected.nativeName})" else "Add Video (MP4) File • ${detected.name} (${detected.nativeName})",
                        progressText = ""
                    )
                }
                _toastMessage.emit("Detected: ${detected.name} (${detected.nativeName}) - Voice updated!")
            } catch (e: Exception) {
                updateBatchItem(itemId) { it.copy(status = "Subtitle error: ${e.message}") }
                _toastMessage.emit("Subtitle read error: ${e.message}")
            }
        }
    }

    fun loadSubtitleFileDirect(itemId: String, file: File) {
        if (!file.exists() || !file.canRead()) {
            viewModelScope.launch { _toastMessage.emit("Selected subtitle file cannot be read") }
            return
        }
        try {
            val content = file.readText(Charsets.UTF_8)
            val entries = SubtitleParser.parseSrtOrVtt(content)
            if (entries.isEmpty()) {
                viewModelScope.launch { _toastMessage.emit("No valid subtitle entries found") }
                return
            }

            // Automatic language detection from parsed subtitle text
            val sampleText = entries.take(15).joinToString(" ") { it.text }
            val detected = LanguageDetector.detectLanguage(sampleText)
            val bestVoice = ttsManager.findBestVoiceForLanguage(detected.code)
            val autoVoiceName = bestVoice?.name

            updateBatchItem(itemId) { item ->
                val isReady = item.videoFile != null && item.videoFile.exists()
                item.copy(
                    srtUri = Uri.fromFile(file),
                    srtName = file.name,
                    srtContent = content,
                    srtSubtitles = entries,
                    detectedLanguage = detected,
                    itemVoiceName = autoVoiceName ?: item.itemVoiceName,
                    status = if (isReady) "Ready for Dubbing • ${detected.name} (${detected.nativeName})" else "Add Video (MP4) File • ${detected.name} (${detected.nativeName})",
                    progressText = ""
                )
            }
            viewModelScope.launch {
                _toastMessage.emit("Detected: ${detected.name} (${detected.nativeName}) - Voice updated!")
            }
        } catch (e: Exception) {
            viewModelScope.launch { _toastMessage.emit("Failed to read subtitle: ${e.message}") }
        }
    }

    /**
     * Online Subtitle Translation: Translates all lines while maintaining exact time codes.
     */
    fun translateSubtitlesForBatchItem(itemId: String, targetLangCode: String, targetLangName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = _batchItems.value.firstOrNull { it.id == itemId } ?: return@launch
            if (item.srtSubtitles.isEmpty()) {
                _toastMessage.emit("No subtitles loaded to translate!")
                return@launch
            }

            updateBatchItem(itemId) {
                it.copy(
                    isTranslating = true,
                    status = "Translating to $targetLangName...",
                    progressText = "Translating subtitles online..."
                )
            }

            try {
                val translated = SubtitleTranslator.translateSubtitles(
                    subtitles = item.srtSubtitles,
                    targetLang = targetLangCode,
                    sourceLang = "auto",
                    concurrencyLimit = _translationConcurrency.value
                ) { count, total ->
                    updateBatchItem(itemId) {
                        it.copy(progressText = "Translating: $count / $total lines")
                    }
                }

                val newSrtContent = SubtitleTranslator.exportToSrt(translated)
                val cleanName = if (item.srtName.isNotEmpty()) {
                    "${item.srtName.substringBeforeLast(".")}_$targetLangCode.srt"
                } else "subtitles_$targetLangCode.srt"

                val targetLangOpt = SubtitleTranslator.supportedLanguages.firstOrNull { it.code.equals(targetLangCode, ignoreCase = true) }
                val targetDetected = DetectedLanguage(
                    code = targetLangCode,
                    name = targetLangName,
                    nativeName = targetLangOpt?.nativeName ?: targetLangName,
                    confidence = 1.0f,
                    scriptName = targetLangName
                )
                val matchedVoice = ttsManager.findBestVoiceForLanguage(targetLangCode)

                updateBatchItem(itemId) {
                    it.copy(
                        isTranslating = false,
                        srtSubtitles = translated,
                        srtContent = newSrtContent,
                        srtName = cleanName,
                        detectedLanguage = targetDetected,
                        itemVoiceName = matchedVoice?.name ?: it.itemVoiceName,
                        status = "Translated to $targetLangName! Ready to Dub",
                        progressText = ""
                    )
                }
                _toastMessage.emit("Translated to $targetLangName & voice matched!")
            } catch (e: Exception) {
                Log.e("BalaSpeakTTS", "Translation failed: ${e.message}", e)
                updateBatchItem(itemId) {
                    it.copy(
                        isTranslating = false,
                        status = "Translation failed: ${e.localizedMessage}",
                        progressText = ""
                    )
                }
                _toastMessage.emit("Translation error: ${e.message}")
            }
        }
    }

    fun resetBatchItem(itemId: String) {
        updateBatchItem(itemId) { old ->
            try { old.videoFile?.delete() } catch (e: Exception) {}
            try { old.dubbedVideoFile?.delete() } catch (e: Exception) {}
            BatchDubItem(id = old.id)
        }
    }

    fun shareDubbedVideoForBatchItem(itemId: String) {
        val item = _batchItems.value.firstOrNull { it.id == itemId } ?: return
        val file = item.dubbedVideoFile ?: return

        try {
            val authority = "${getApplication<Application>().packageName}.provider"
            val uri: Uri = FileProvider.getUriForFile(getApplication(), authority, file)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(intent, "Share dubbed video...")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(chooser)
        } catch (e: Exception) {
            viewModelScope.launch {
                _toastMessage.emit("Share error: ${e.message}")
            }
        }
    }

    fun startVideoDubbingForBatchItem(itemId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            processItemDubbing(itemId)
        }
    }

    fun startAllBatchDubbing() {
        if (_isGlobalProcessing.value) return
        _isGlobalProcessing.value = true

        globalProcessingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (_backgroundProcessingActive.value) {
                    DubbingForegroundService.startService(
                        getApplication(),
                        "BalaSpeak Turbo Dubbing",
                        "Processing batch video dubbing in background..."
                    )
                }

                val list = _batchItems.value
                for ((index, item) in list.withIndex()) {
                    kotlinx.coroutines.yield()
                    if (item.videoFile != null && item.videoFile.exists() && item.srtSubtitles.isNotEmpty()) {
                        DubbingForegroundService.updateProgress(
                            getApplication(),
                            "Dubbing video ${index + 1} of ${list.size}",
                            index + 1,
                            list.size
                        )
                        processItemDubbing(item.id)
                    }
                }
                _toastMessage.emit("All batch dubbing tasks completed!")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _toastMessage.emit("Dubbing process stopped.")
            } finally {
                _isGlobalProcessing.value = false
                DubbingForegroundService.stopService(getApplication())
            }
        }
    }

    fun stopAllProcessing() {
        globalProcessingJob?.cancel()
        _isGlobalProcessing.value = false
        DubbingForegroundService.stopService(getApplication())
    }

    private suspend fun processItemDubbing(itemId: String) {
        val item = _batchItems.value.firstOrNull { it.id == itemId } ?: return
        val videoFile = item.videoFile
        val subtitles = item.srtSubtitles

        if (videoFile == null || !videoFile.exists() || subtitles.isEmpty()) {
            updateBatchItem(itemId) { it.copy(status = "Files not loaded") }
            return
        }

        updateBatchItem(itemId) {
            it.copy(
                isProcessing = true,
                status = "Dubbing in progress...",
                progressText = "Rendering high-speed voice tracks..."
            )
        }

        try {
            if (item.itemVoiceName != null) {
                ttsManager.setVoice(item.itemVoiceName)
            }

            val tempTtsWav = File(getApplication<Application>().cacheDir, "tts_${itemId}_${System.currentTimeMillis()}.wav")

            // High-speed parallelized batch synthesis with auto duration fit strictly synchronized to subtitles
            val ttsSuccess = ttsManager.compileSubtitlesToWav(
                subtitles = subtitles,
                destWavFile = tempTtsWav,
                speed = 1.0f,
                pitch = 1.0f,
                voiceName = item.itemVoiceName,
                autoFitDuration = true
            ) { completed, total ->
                updateBatchItem(itemId) {
                    it.copy(progressText = "Synthesizing voice: $completed / $total cards")
                }
            }

            if (!ttsSuccess || !tempTtsWav.exists() || tempTtsWav.length() < 44) {
                updateBatchItem(itemId) {
                    it.copy(
                        isProcessing = false,
                        status = "Voice generation failed",
                        progressText = ""
                    )
                }
                _toastMessage.emit("Voice synthesis failed. Please check voice language.")
                return
            }

            updateBatchItem(itemId) {
                it.copy(progressText = "Mixing audio with original video...")
            }

            // Name output file e.g., 1.mp4 -> 1u.mp4, or 1.mp3 -> 1u.m4a
            val rawName = if (item.videoName.isNotEmpty()) item.videoName else videoFile.name
            val cleanBaseName = rawName.substringBeforeLast(".")
            val rawExt = rawName.substringAfterLast(".", "mp4").lowercase()
            val cleanExtension = when (rawExt) {
                "mp3", "wav" -> "m4a"
                else -> rawExt
            }
            val fileNameu = "${cleanBaseName}u.$cleanExtension"

            // Target directory: Automatically use the exact directory where the input video came from!
            val sourceDir = item.sourceDirectory ?: videoFile.parentFile
            val isSourceDirWritable = sourceDir != null && sourceDir.exists() && sourceDir.canWrite() && sourceDir != getApplication<Application>().cacheDir

            val targetDir = if (isSourceDirWritable) {
                sourceDir!!
            } else {
                getEffectiveOutputDir()
            }

            // Encode to a clean temporary file in cache to ensure fast, unbuffered disk I/O without any partial-lock truncation
            val tempMuxFile = File(getApplication<Application>().cacheDir, "temp_mux_${itemId}_${System.currentTimeMillis()}.$cleanExtension")

            val mergerSuccess = AudioVideoMerger.mixAndEncodeDubbedMedia(
                videoFile = videoFile,
                ttsWavFile = tempTtsWav,
                targetOutputFile = tempMuxFile,
                videoAudioVolume = item.videoVolume,
                ttsAudioVolume = item.ttsVolume
            ) { mergerMsg ->
                updateBatchItem(itemId) { it.copy(progressText = mergerMsg) }
            }

            // Cleanup temp wave file
            try { tempTtsWav.delete() } catch (e: Exception) {}

            if (mergerSuccess && tempMuxFile.exists() && tempMuxFile.length() > 0) {
                var finalOutputFile: File? = null
                var displayLocation = fileNameu

                // 1. Direct copy to the source folder (or configured targetDir)
                if (targetDir.exists() && targetDir.canWrite()) {
                    val candidateFile = File(targetDir, fileNameu)
                    try {
                        tempMuxFile.copyTo(candidateFile, overwrite = true)
                        if (candidateFile.exists() && candidateFile.length() > 0) {
                            finalOutputFile = candidateFile
                            displayLocation = "${targetDir.name}/$fileNameu"
                            MediaScannerConnection.scanFile(getApplication(), arrayOf(candidateFile.absolutePath), null, null)
                        }
                    } catch (e: Exception) {
                        Log.w("BalaSpeakTTS", "Direct save to source directory failed: ${e.message}")
                    }
                }

                // 2. Fallback to public Downloads/MediaStore if source folder was read-only
                if (finalOutputFile == null) {
                    val publicFile = saveToPublicDownloadsIfPossible(tempMuxFile, fileNameu)
                    if (publicFile != null && publicFile.exists() && publicFile.length() > 0) {
                        finalOutputFile = publicFile
                        displayLocation = if (publicFile.parentFile != null) "${publicFile.parentFile?.name}/$fileNameu" else "Downloads/$fileNameu"
                    } else {
                        // 3. Fallback: app external downloads directory
                        val fallbackDir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                            ?: getApplication<Application>().filesDir
                        val fallbackFile = File(fallbackDir, fileNameu)
                        tempMuxFile.copyTo(fallbackFile, overwrite = true)
                        finalOutputFile = fallbackFile
                        displayLocation = fallbackFile.name
                    }
                }

                // Cleanup temp mux file
                try { tempMuxFile.delete() } catch (e: Exception) {}

                updateBatchItem(itemId) {
                    it.copy(
                        isProcessing = false,
                        status = "Dubbing Complete! ✨",
                        progressText = "Saved: $displayLocation",
                        dubbedVideoFile = finalOutputFile
                    )
                }
                _toastMessage.emit("Dubbed successfully: $displayLocation")
            } else {
                try { tempMuxFile.delete() } catch (e: Exception) {}

                updateBatchItem(itemId) {
                    it.copy(
                        isProcessing = false,
                        status = "Merging failed",
                        progressText = ""
                    )
                }
                _toastMessage.emit("Video merging failed. Check input video format.")
            }
        } catch (e: Exception) {
            Log.e("BalaSpeakTTS", "Error dubbing item $itemId: ${e.message}", e)
            updateBatchItem(itemId) {
                it.copy(
                    isProcessing = false,
                    status = "Error: ${e.localizedMessage}",
                    progressText = ""
                )
            }
            _toastMessage.emit("Dubbing error: ${e.message}")
        }
    }

    fun getEffectiveOutputDir(): File {
        val customPath = _customOutputDirPath.value
        if (customPath.isNotBlank()) {
            val customFolder = File(customPath)
            if (customFolder.exists() && customFolder.canWrite()) {
                return customFolder
            }
        }
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (publicDownloads != null && publicDownloads.exists() && publicDownloads.canWrite()) {
            return publicDownloads
        }
        return getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: getApplication<Application>().cacheDir
    }

    private fun saveToPublicDownloadsIfPossible(sourceFile: File, displayName: String): File? {
        try {
            val customPath = _customOutputDirPath.value
            if (customPath.isNotBlank()) {
                val customFolder = File(customPath)
                if (customFolder.exists() && customFolder.canWrite()) {
                    val dest = File(customFolder, displayName)
                    if (dest.canonicalPath == sourceFile.canonicalPath) {
                        return dest
                    }
                    sourceFile.copyTo(dest, overwrite = true)
                    MediaScannerConnection.scanFile(getApplication(), arrayOf(dest.absolutePath), null, null)
                    return dest
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (displayName.endsWith(".m4a", ignoreCase = true)) "audio/mp4" else "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BalaSpeak")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = getApplication<Application>().contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { os ->
                        FileInputStream(sourceFile).use { `is` -> `is`.copyTo(os) }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BalaSpeak/$displayName")
                }
            } else {
                val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (publicDir.exists() || publicDir.mkdirs()) {
                    val destFile = File(publicDir, displayName)
                    if (destFile.canonicalPath != sourceFile.canonicalPath) {
                        sourceFile.copyTo(destFile, overwrite = true)
                    }
                    MediaScannerConnection.scanFile(getApplication(), arrayOf(destFile.absolutePath), null, null)
                    return destFile
                }
            }
        } catch (e: Exception) {
            Log.e("BalaSpeakTTS", "Public save fallback error: ${e.message}")
        }
        return null
    }

    // --- Standalone Subtitle Translator Actions ---

    private val _standaloneSrtSourceDir = MutableStateFlow<File?>(null)
    val standaloneSrtSourceDir: StateFlow<File?> = _standaloneSrtSourceDir.asStateFlow()

    fun setStandaloneSourceLang(lang: String) {
        _standaloneSourceLang.value = lang
    }

    fun setStandaloneTargetLang(lang: LanguageOption) {
        _standaloneTargetLang.value = lang
    }

    fun loadStandaloneSrt(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = getFileNameFromUri(uri)
                val cleanName = if (name.contains("/")) name.substringAfterLast("/") else name

                val realFile = resolveRealFileFromUri(uri)
                _standaloneSrtSourceDir.value = realFile?.parentFile

                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                val content = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                val entries = SubtitleParser.parseSrtOrVtt(content)

                if (entries.isEmpty()) {
                    _toastMessage.emit("No valid subtitle timestamps found!")
                    return@launch
                }

                _standaloneSrtFileName.value = cleanName
                _standaloneOriginalSubtitles.value = entries
                _standaloneTranslatedSubtitles.value = emptyList()
                _toastMessage.emit("Loaded ${entries.size} subtitle entries")
            } catch (e: Exception) {
                _toastMessage.emit("Error loading subtitles: ${e.message}")
            }
        }
    }

    fun loadStandaloneSrtFileDirect(file: File) {
        if (!file.exists() || !file.canRead()) {
            viewModelScope.launch { _toastMessage.emit("Subtitle file cannot be read") }
            return
        }
        try {
            val content = file.readText(Charsets.UTF_8)
            val entries = SubtitleParser.parseSrtOrVtt(content)
            if (entries.isEmpty()) {
                viewModelScope.launch { _toastMessage.emit("No valid subtitle entries in file") }
                return
            }
            _standaloneSrtSourceDir.value = file.parentFile
            _standaloneSrtFileName.value = file.name
            _standaloneOriginalSubtitles.value = entries
            _standaloneTranslatedSubtitles.value = emptyList()
            viewModelScope.launch { _toastMessage.emit("Loaded ${entries.size} subtitle entries from ${file.name}") }
        } catch (e: Exception) {
            viewModelScope.launch { _toastMessage.emit("Error loading subtitles: ${e.message}") }
        }
    }

    fun loadStandaloneSrtFromText(text: String, fileName: String = "custom_subtitles.srt") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (text.isNotBlank()) {
                    val parsed = SubtitleParser.parseSrtOrVtt(text)
                    _standaloneSrtFileName.value = fileName
                    _standaloneOriginalSubtitles.value = parsed
                    _standaloneTranslatedSubtitles.value = emptyList()
                    _toastMessage.emit("Loaded ${parsed.size} subtitle entries")
                }
            } catch (e: Exception) {
                _toastMessage.emit("Failed to parse subtitle text: ${e.message}")
            }
        }
    }

    fun translateStandaloneSubtitles() {
        val originalList = _standaloneOriginalSubtitles.value
        if (originalList.isEmpty()) {
            viewModelScope.launch { _toastMessage.emit("Please select or paste a subtitle file first!") }
            return
        }

        val target = _standaloneTargetLang.value
        val source = _standaloneSourceLang.value

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _standaloneIsTranslating.value = true
                _standaloneTranslateProgress.value = Pair(0, originalList.size)

                val translated = SubtitleTranslator.translateSubtitles(
                    subtitles = originalList,
                    targetLang = target.code,
                    sourceLang = source,
                    concurrencyLimit = _translationConcurrency.value,
                    onProgress = { cur, total ->
                        _standaloneTranslateProgress.value = Pair(cur, total)
                    }
                )

                _standaloneTranslatedSubtitles.value = translated
                _standaloneIsTranslating.value = false
                _toastMessage.emit("Successfully translated ${translated.size} subtitles to ${target.name}!")
            } catch (e: Exception) {
                Log.e("BalaSpeakTTS", "Standalone translation error: ${e.message}")
                _standaloneIsTranslating.value = false
                _toastMessage.emit("Translation error: ${e.message}")
            }
        }
    }

    fun exportStandaloneTranslatedSrt(): String {
        val list = if (_standaloneTranslatedSubtitles.value.isNotEmpty()) _standaloneTranslatedSubtitles.value else _standaloneOriginalSubtitles.value
        return SubtitleTranslator.exportToSrt(list)
    }

    fun saveStandaloneTranslatedSrtToDownloads(): File? {
        val srtText = exportStandaloneTranslatedSrt()
        if (srtText.isBlank()) return null

        try {
            val baseName = _standaloneSrtFileName.value.ifBlank { "subtitles" }.substringBeforeLast(".")
            // Output name strictly ends with u.srt (e.g. 1.srt -> 1u.srt, NO "-en" or ".txt")
            val fileName = "${baseName}u.srt"

            // Target folder: directly in the exact directory where the input file came from!
            val sourceDir = _standaloneSrtSourceDir.value
            val isSourceDirWritable = sourceDir != null && sourceDir.exists() && sourceDir.canWrite() && sourceDir != getApplication<Application>().cacheDir

            val targetDir = if (isSourceDirWritable) {
                sourceDir!!
            } else {
                getEffectiveOutputDir()
            }

            val srtFile = File(targetDir, fileName)
            srtFile.writeText(srtText, Charsets.UTF_8)
            MediaScannerConnection.scanFile(getApplication(), arrayOf(srtFile.absolutePath), null, null)

            val displayLocation = if (isSourceDirWritable) "${targetDir.name}/$fileName" else fileName
            viewModelScope.launch { _toastMessage.emit("Saved: $displayLocation") }
            return srtFile
        } catch (e: Exception) {
            Log.e("BalaSpeakTTS", "Error saving srt: ${e.message}")
            viewModelScope.launch { _toastMessage.emit("Failed to save SRT: ${e.message}") }
            return null
        }
    }

    fun sendTranslatedToDubbingQueue() {
        val translated = _standaloneTranslatedSubtitles.value
        if (translated.isEmpty()) {
            viewModelScope.launch { _toastMessage.emit("No translated subtitles to send!") }
            return
        }

        val baseName = _standaloneSrtFileName.value.ifBlank { "subtitles" }.substringBeforeLast(".")
        val srtName = "${baseName}u.srt"
        val srtContent = SubtitleTranslator.exportToSrt(translated)

        val newItem = BatchDubItem(
            srtContent = srtContent,
            srtName = srtName,
            srtSubtitles = translated,
            status = "Translated subtitles loaded. Please select a video."
        )

        val currentList = _batchItems.value.toMutableList()
        if (currentList.size == 1 && currentList[0].videoFile == null && currentList[0].srtSubtitles.isEmpty()) {
            currentList[0] = newItem
        } else {
            currentList.add(newItem)
        }
        _batchItems.value = currentList
        viewModelScope.launch { _toastMessage.emit("Added translated subtitle to Dubbing Queue!") }
    }

    private fun saveSrtToPublicDownloads(sourceFile: File, displayName: String): File? {
        try {
            val customPath = _customOutputDirPath.value
            if (customPath.isNotBlank()) {
                val customFolder = File(customPath)
                if (customFolder.exists() && customFolder.canWrite()) {
                    val dest = File(customFolder, displayName)
                    FileInputStream(sourceFile).use { `is` ->
                        FileOutputStream(dest).use { os -> `is`.copyTo(os) }
                    }
                    return dest
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BalaSpeak")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = getApplication<Application>().contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { os ->
                        FileInputStream(sourceFile).use { `is` -> `is`.copyTo(os) }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    return File(displayName)
                }
            }
        } catch (e: Exception) {
            Log.e("BalaSpeakTTS", "Public srt save fallback error: ${e.message}")
        }
        return null
    }

    fun shareSrt(content: String, fileName: String = "subtitles.srt") {
        try {
            val cacheFile = File(getApplication<Application>().cacheDir, fileName)
            cacheFile.writeText(content, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(
                getApplication<Application>(),
                "${getApplication<Application>().packageName}.fileprovider",
                cacheFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "Share Subtitle File").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(chooser)
        } catch (e: Exception) {
            Log.e("BalaSpeakTTS", "Error sharing srt: ${e.message}")
            viewModelScope.launch { _toastMessage.emit("Share error: ${e.message}") }
        }
    }

    // --- AI Audio & Video Transcription Actions ---

    fun getEffectiveGeminiApiKey(): String {
        val userKey = _geminiApiKey.value.trim()
        if (userKey.isNotBlank() && userKey != "MY_GEMINI_API_KEY") return userKey
        val buildKey = BuildConfig.GEMINI_API_KEY.trim()
        if (buildKey.isNotBlank() && buildKey != "MY_GEMINI_API_KEY") return buildKey
        val envKey = (try { System.getenv("GEMINI_API_KEY") } catch (e: Exception) { null })?.trim() ?: ""
        if (envKey.isNotBlank() && envKey != "MY_GEMINI_API_KEY") return envKey
        return ""
    }

    fun isUsingFreeUnlimitedMode(): Boolean {
        return _geminiApiKey.value.trim().isBlank()
    }

    fun setGeminiApiKey(key: String) {
        _geminiApiKey.value = key.trim()
        prefs.edit().putString("pref_gemini_api_key", key.trim()).apply()
    }

    fun setTranscribeVideo(uri: Uri?, file: File?, name: String) {
        _transcribeVideoUri.value = uri
        _transcribeVideoFile.value = file
        _transcribeVideoName.value = name
        _savedSubtitleFilePath.value = null
        _transcribeProgress.value = 0f
        _transcribeCurrentChunk.value = 0
        _transcribeTotalChunks.value = 0
        _transcribedSrtContent.value = ""
        _transcribedSubtitles.value = emptyList()

        viewModelScope.launch(Dispatchers.IO) {
            val realFile = file ?: uri?.let { resolveRealFileFromUri(it) }
            if (realFile != null && realFile.exists()) {
                _transcribeVideoFile.value = realFile
                _transcribeVideoSizeBytes.value = realFile.length()
                _transcribeVideoDurationMs.value = VideoTranscriber.getVideoDurationMs(realFile)
            } else if (uri != null) {
                try {
                    getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        _transcribeVideoSizeBytes.value = pfd.statSize
                    }
                } catch (e: Exception) {
                    _transcribeVideoSizeBytes.value = 0L
                }
            }

            // Auto-persist active session draft into Room Database
            try {
                val initialTask = MediaTaskHistory(
                    taskType = "TRANSCRIBE",
                    title = name,
                    filePath = realFile?.absolutePath,
                    fileUri = uri?.toString(),
                    targetLanguage = _transcribeTargetLang.value,
                    status = "SAVED",
                    durationMs = _transcribeVideoDurationMs.value,
                    fileSizeBytes = _transcribeVideoSizeBytes.value,
                    updatedAt = System.currentTimeMillis()
                )
                val newId = mediaTaskHistoryDao.insertTask(initialTask)
                _currentActiveHistoryTaskId.value = newId
                prefs.edit().putLong("pref_last_active_task_id", newId).apply()
            } catch (e: Exception) {
                Log.w("DocumentViewModel", "Error saving draft task: ${e.message}")
            }
        }
    }

    fun setTranscribeTargetLang(lang: String) {
        _transcribeTargetLang.value = lang
    }

    fun cancelTranscription() {
        transcriptionJob?.cancel()
        _isTranscribing.value = false
        _transcribeStatusText.value = "Transcription cancelled (${_transcribedSubtitles.value.size} lines saved)"
    }

    fun startVideoTranscription() {
        val uri = _transcribeVideoUri.value
        val file = _transcribeVideoFile.value
        if (uri == null && file == null) {
            viewModelScope.launch { _toastMessage.emit("Please select an audio or video file first") }
            return
        }

        var effectiveKey = getEffectiveGeminiApiKey()
        if (effectiveKey.isBlank() || effectiveKey == "MY_GEMINI_API_KEY") {
            effectiveKey = (try { System.getenv("GEMINI_API_KEY") } catch (e: Exception) { null })?.trim() ?: ""
        }

        _isTranscribing.value = true
        _transcribeProgress.value = 0f
        _transcribePercent.value = 0
        _transcribePhase.value = "INITIALIZING"
        _transcribeCurrentChunk.value = 0
        _transcribeTotalChunks.value = 0
        _transcribedSrtContent.value = ""
        _transcribedSubtitles.value = emptyList()
        _transcribeStatusText.value = "Preparing media file..."

        transcriptionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                var resolvedMediaFile = file ?: uri?.let { resolveRealFileFromUri(it) }

                // If uri cannot be resolved directly to real file, copy stream to cache
                if (resolvedMediaFile == null || !resolvedMediaFile.exists()) {
                    _transcribeStatusText.value = "Copying media file..."
                    val ext = _transcribeVideoName.value.substringAfterLast('.', "mp4")
                    val temp = File(context.cacheDir, "transcribe_src_${System.currentTimeMillis()}.$ext")
                    context.contentResolver.openInputStream(uri!!)?.use { input ->
                        FileOutputStream(temp).use { output ->
                            input.copyTo(output)
                        }
                    }
                    resolvedMediaFile = temp
                }

                if (!resolvedMediaFile.exists() || resolvedMediaFile.length() == 0L) {
                    throw IllegalStateException("Failed to open media file.")
                }

                val tempDir = File(context.cacheDir, "transcribe_work").apply { mkdirs() }

                val (srtText, entries) = VideoTranscriber.transcribeFullMedia(
                    context = context,
                    mediaFile = resolvedMediaFile,
                    tempDir = tempDir,
                    apiKey = effectiveKey,
                    targetLanguage = _transcribeTargetLang.value,
                    onProgress = { chunkProg ->
                        _transcribeCurrentChunk.value = chunkProg.currentChunk
                        _transcribeTotalChunks.value = chunkProg.totalChunks
                        _transcribeProgress.value = chunkProg.progressFraction
                        _transcribePercent.value = chunkProg.progressPercent
                        _transcribePhase.value = chunkProg.phase
                        _transcribeStatusText.value = chunkProg.statusMessage
                        if (chunkProg.accumulatedSubtitles.isNotEmpty()) {
                            _transcribedSubtitles.value = chunkProg.accumulatedSubtitles
                            val currentSrt = SubtitleTranslator.exportToSrt(chunkProg.accumulatedSubtitles)
                            _transcribedSrtContent.value = currentSrt

                            // Immediately persist intermediate subtitles in Room so user never loses progress!
                            val activeId = _currentActiveHistoryTaskId.value ?: 0L
                            val inProgressTask = MediaTaskHistory(
                                id = activeId,
                                taskType = "TRANSCRIBE",
                                title = _transcribeVideoName.value.ifBlank { "Media Task" },
                                filePath = resolvedMediaFile.absolutePath,
                                fileUri = uri?.toString(),
                                targetLanguage = _transcribeTargetLang.value,
                                srtContent = currentSrt,
                                subtitleCount = chunkProg.accumulatedSubtitles.size,
                                currentChunk = chunkProg.currentChunk,
                                totalChunks = chunkProg.totalChunks,
                                status = "IN_PROGRESS",
                                durationMs = _transcribeVideoDurationMs.value,
                                fileSizeBytes = _transcribeVideoSizeBytes.value,
                                updatedAt = System.currentTimeMillis()
                            )
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    val savedId = mediaTaskHistoryDao.insertTask(inProgressTask)
                                    _currentActiveHistoryTaskId.value = savedId
                                    prefs.edit().putLong("pref_last_active_task_id", savedId).apply()
                                } catch (e: Exception) {
                                    Log.w("DocumentViewModel", "Error updating history progress: ${e.message}")
                                }
                            }
                        }
                    }
                )

                _transcribedSrtContent.value = srtText
                _transcribedSubtitles.value = entries
                _transcribeProgress.value = 1.0f
                _transcribePercent.value = 100
                _transcribePhase.value = "COMPLETED"
                _transcribeStatusText.value = "Transcription completed (${entries.size} subtitles)"

                // Automatically save SRT alongside media or in output directory
                val targetDir = resolvedMediaFile.parentFile ?: getEffectiveOutputDir()
                val baseName = _transcribeVideoName.value.ifBlank { "audio_video" }.substringBeforeLast('.')
                val savedFile = saveTranscribedSrt(targetDir, "$baseName.srt")
                if (savedFile != null) {
                    _savedSubtitleFilePath.value = savedFile.absolutePath
                    _toastMessage.emit("Subtitles saved: ${savedFile.name}")
                } else {
                    _toastMessage.emit("Transcription completed successfully!")
                }

                // Persist completed task permanently in Room
                val activeId = _currentActiveHistoryTaskId.value ?: 0L
                val completedTask = MediaTaskHistory(
                    id = activeId,
                    taskType = "TRANSCRIBE",
                    title = _transcribeVideoName.value.ifBlank { "Media Task" },
                    filePath = resolvedMediaFile.absolutePath,
                    fileUri = uri?.toString(),
                    targetLanguage = _transcribeTargetLang.value,
                    srtContent = srtText,
                    subtitleCount = entries.size,
                    currentChunk = _transcribeTotalChunks.value,
                    totalChunks = _transcribeTotalChunks.value,
                    status = "COMPLETED",
                    savedOutputPath = savedFile?.absolutePath,
                    durationMs = _transcribeVideoDurationMs.value,
                    fileSizeBytes = _transcribeVideoSizeBytes.value,
                    updatedAt = System.currentTimeMillis()
                )
                try {
                    val finalId = mediaTaskHistoryDao.insertTask(completedTask)
                    _currentActiveHistoryTaskId.value = finalId
                    prefs.edit().putLong("pref_last_active_task_id", finalId).apply()
                } catch (e: Exception) {
                    Log.w("DocumentViewModel", "Error finalizing history task: ${e.message}")
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                _transcribeStatusText.value = "Transcription cancelled (${_transcribedSubtitles.value.size} lines saved)"
                _toastMessage.emit("Transcription cancelled")
            } catch (t: Throwable) {
                Log.e("VideoTranscribe", "Transcription error: ${t.message}", t)
                val errorDetails = when (t) {
                    is OutOfMemoryError -> "Low memory on device. Please close other apps and try again."
                    else -> t.localizedMessage ?: t.message ?: "Unexpected error occurred"
                }
                _transcribeStatusText.value = "Error: $errorDetails"
                _toastMessage.emit("Transcription failed: $errorDetails")
            } finally {
                _isTranscribing.value = false
            }
        }
    }

    fun saveTranscribedSrt(customDir: File? = null, customFileName: String? = null): File? {
        val content = _transcribedSrtContent.value
        if (content.isBlank()) return null
        return try {
            val baseName = _transcribeVideoName.value.ifBlank { "video" }.substringBeforeLast('.')
            val fileName = customFileName?.ifBlank { null } ?: "$baseName.srt"

            val targetDir = customDir ?: getEffectiveOutputDir()
            if (!targetDir.exists()) targetDir.mkdirs()

            val srtFile = File(targetDir, fileName)
            srtFile.writeText(content, Charsets.UTF_8)
            MediaScannerConnection.scanFile(getApplication(), arrayOf(srtFile.absolutePath), null, null)
            _savedSubtitleFilePath.value = srtFile.absolutePath
            viewModelScope.launch { _toastMessage.emit("Saved: ${srtFile.absolutePath}") }
            srtFile
        } catch (e: Exception) {
            Log.e("VideoTranscribe", "Save srt error: ${e.message}")
            viewModelScope.launch { _toastMessage.emit("Save failed: ${e.message}") }
            null
        }
    }

    fun updateTranscribedSubtitleText(index: Int, newText: String) {
        val current = _transcribedSubtitles.value.toMutableList()
        val idx = current.indexOfFirst { it.index == index }
        if (idx != -1) {
            current[idx] = current[idx].copy(text = newText)
            _transcribedSubtitles.value = current
            _transcribedSrtContent.value = SubtitleTranslator.exportToSrt(current)
        }
    }

    fun clearTranscribeSession() {
        _transcribeVideoUri.value = null
        _transcribeVideoFile.value = null
        _transcribeVideoName.value = ""
        _transcribeVideoDurationMs.value = 0L
        _transcribeVideoSizeBytes.value = 0L
        _transcribedSrtContent.value = ""
        _transcribedSubtitles.value = emptyList()
        _savedSubtitleFilePath.value = null
        _transcribeStatusText.value = "Select audio or video to start transcribing"
        _transcribeProgress.value = 0f
        _currentActiveHistoryTaskId.value = null
        _restoredSessionBanner.value = null
        prefs.edit().remove("pref_last_active_task_id").apply()
    }

    fun restoreSessionFromHistory(task: MediaTaskHistory, auto: Boolean = false) {
        viewModelScope.launch(Dispatchers.Main) {
            _currentActiveHistoryTaskId.value = task.id
            prefs.edit().putLong("pref_last_active_task_id", task.id).apply()

            if (!task.filePath.isNullOrBlank()) {
                val f = File(task.filePath)
                if (f.exists()) {
                    _transcribeVideoFile.value = f
                }
            }
            if (!task.fileUri.isNullOrBlank()) {
                try {
                    _transcribeVideoUri.value = Uri.parse(task.fileUri)
                } catch (e: Exception) {
                    Log.w("DocumentViewModel", "Error parsing uri: ${e.message}")
                }
            }
            _transcribeVideoName.value = task.title
            _transcribeVideoDurationMs.value = task.durationMs
            _transcribeVideoSizeBytes.value = task.fileSizeBytes
            _transcribeTargetLang.value = task.targetLanguage

            if (task.srtContent.isNotBlank()) {
                _transcribedSrtContent.value = task.srtContent
                val parsed = SubtitleParser.parseSrtOrVtt(task.srtContent)
                _transcribedSubtitles.value = parsed
                _transcribeStatusText.value = "Restored previous task (${parsed.size} subtitles)"
            }
            _savedSubtitleFilePath.value = task.savedOutputPath

            if (auto) {
                _restoredSessionBanner.value = task
            } else {
                _toastMessage.emit("Restored '${task.title}' from history!")
            }
        }
    }

    fun dismissRestoredBanner() {
        _restoredSessionBanner.value = null
    }

    fun deleteHistoryTask(taskId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                mediaTaskHistoryDao.deleteTaskById(taskId)
                if (_currentActiveHistoryTaskId.value == taskId) {
                    _currentActiveHistoryTaskId.value = null
                    prefs.edit().remove("pref_last_active_task_id").apply()
                }
                _toastMessage.emit("Removed from history")
            } catch (e: Exception) {
                Log.w("DocumentViewModel", "Error deleting history: ${e.message}")
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                mediaTaskHistoryDao.clearAll()
                _currentActiveHistoryTaskId.value = null
                prefs.edit().remove("pref_last_active_task_id").apply()
                _toastMessage.emit("All history cleared")
            } catch (e: Exception) {
                Log.w("DocumentViewModel", "Error clearing history: ${e.message}")
            }
        }
    }

    fun sendHistoryTaskToDubbing(task: MediaTaskHistory): Boolean {
        if (task.srtContent.isBlank()) return false
        val subs = SubtitleParser.parseSrtOrVtt(task.srtContent)
        val videoFile = task.filePath?.let { File(it) }?.takeIf { it.exists() }
        val videoUri = task.fileUri?.let { Uri.parse(it) }

        val item = BatchDubItem(
            videoUri = videoUri,
            videoFile = videoFile,
            videoName = task.title,
            sourceDirectory = videoFile?.parentFile,
            srtName = "${task.title.substringBeforeLast('.')}.srt",
            srtContent = task.srtContent,
            srtSubtitles = subs,
            status = "Ready for Dubbing (${subs.size} entries)"
        )
        val current = _batchItems.value.toMutableList()
        if (current.size == 1 && current[0].videoFile == null && current[0].srtSubtitles.isEmpty()) {
            _batchItems.value = listOf(item)
        } else {
            current.add(item)
            _batchItems.value = current
        }
        viewModelScope.launch { _toastMessage.emit("Loaded '${task.title}' into Dubbing Studio!") }
        return true
    }

    fun sendTranscribedToDubbing(): Boolean {
        val videoFile = _transcribeVideoFile.value
        val videoUri = _transcribeVideoUri.value
        val srtContent = _transcribedSrtContent.value
        val subs = _transcribedSubtitles.value
        if ((videoFile == null && videoUri == null) || srtContent.isBlank()) return false

        val baseName = _transcribeVideoName.value.ifBlank { "video" }.substringBeforeLast('.')
        val item = BatchDubItem(
            videoUri = videoUri,
            videoFile = videoFile,
            videoName = _transcribeVideoName.value,
            sourceDirectory = videoFile?.parentFile,
            srtName = "$baseName.srt",
            srtContent = srtContent,
            srtSubtitles = subs,
            status = "Ready for Dubbing (${subs.size} entries)"
        )
        _batchItems.value = listOf(item)
        viewModelScope.launch { _toastMessage.emit("Loaded into Dubbing Studio!") }
        return true
    }

    fun sendTranscribedToTranslator(): Boolean {
        val srtContent = _transcribedSrtContent.value
        val subs = _transcribedSubtitles.value
        if (srtContent.isBlank()) return false

        val baseName = _transcribeVideoName.value.ifBlank { "video" }.substringBeforeLast('.')
        _standaloneSrtFileName.value = "$baseName.srt"
        _standaloneOriginalSubtitles.value = subs
        _standaloneTranslatedSubtitles.value = emptyList()
        viewModelScope.launch { _toastMessage.emit("Loaded into Subtitle Translator!") }
        return true
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.shutdown()
    }
}
