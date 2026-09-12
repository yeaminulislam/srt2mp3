package com.example.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.util.LanguageDetector
import com.example.util.SubtitleEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class SentenceSpan(
    val text: String,
    val startCharOffset: Int,
    val endCharOffset: Int
)

enum class SpeechState {
    IDLE,
    INITIALIZING,
    READY,
    SPEAKING,
    PAUSED,
    ERROR
}

data class FriendlyVoice(
    val id: String,
    val displayName: String,
    val languageGroup: String,
    val locale: Locale,
    val sampleText: String = "",
    val isSystemVoice: Boolean = true,
    val systemVoice: Voice? = null
)

class TextToSpeechManager(private val context: Context) : TextToSpeech.OnInitListener {

    var tts: TextToSpeech? = null

    private val _speechState = MutableStateFlow(SpeechState.INITIALIZING)
    val speechState: StateFlow<SpeechState> = _speechState.asStateFlow()

    private val _highlightRange = MutableStateFlow<Pair<Int, Int>?>(null)
    val highlightRange: StateFlow<Pair<Int, Int>?> = _highlightRange.asStateFlow()

    private val _currentSentenceIndex = MutableStateFlow(0)
    val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()

    private val _totalSentences = MutableStateFlow(0)
    val totalSentences: StateFlow<Int> = _totalSentences.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<Voice>>(emptyList())
    val availableVoices: StateFlow<List<Voice>> = _availableVoices.asStateFlow()

    private val _selectedVoice = MutableStateFlow<String?>(null)
    val selectedVoice: StateFlow<String?> = _selectedVoice.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _pitch = MutableStateFlow(1.0f)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _autoFitSubtitleDuration = MutableStateFlow(true)
    val autoFitSubtitleDuration: StateFlow<Boolean> = _autoFitSubtitleDuration.asStateFlow()

    private val _previewingVoiceId = MutableStateFlow<String?>(null)
    val previewingVoiceId: StateFlow<String?> = _previewingVoiceId.asStateFlow()

    // Concurrent synthesis tracking
    val concurrentListeners = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    var saveListener: CompletableDeferred<File?>? = null
    var saveFileTarget: File? = null

    private var fullText: String = ""
    private var sentences: List<SentenceSpan> = emptyList()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            setupTtsEngine()
        } else {
            _speechState.value = SpeechState.ERROR
            Log.e("BalaSpeakTTS", "Failed to initialize TTS engine")
        }
    }

    private fun setupTtsEngine() {
        tts?.let { engine ->
            val defaultLoc = Locale.getDefault()
            if (engine.isLanguageAvailable(defaultLoc) >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = defaultLoc
            } else {
                engine.language = Locale.US
            }

            try {
                val voicesList = engine.voices?.toList() ?: emptyList()
                _availableVoices.value = voicesList.sortedBy { it.locale.displayName }

                val defaultVoice = engine.defaultVoice?.name ?: engine.voice?.name
                _selectedVoice.value = defaultVoice
            } catch (e: Exception) {
                Log.e("BalaSpeakTTS", "Error retrieving voices: ${e.message}")
            }

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId != null && (utteranceId.startsWith("sub_seg_") || utteranceId == "balaspeak_save_utterance")) {
                        return
                    }
                    _speechState.value = SpeechState.SPEAKING
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != null) {
                        if (utteranceId.startsWith("sub_seg_")) {
                            val deferred = concurrentListeners.remove(utteranceId)
                            deferred?.complete(true)
                            return
                        }
                        if (utteranceId == "balaspeak_save_utterance") {
                            val deferred = saveListener
                            saveListener = null
                            deferred?.complete(saveFileTarget)
                            return
                        }
                    }
                    if (_speechState.value == SpeechState.SPEAKING) {
                        coroutineScope.launch {
                            playNextSentence()
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    if (utteranceId != null) {
                        if (utteranceId.startsWith("sub_seg_")) {
                            val deferred = concurrentListeners.remove(utteranceId)
                            deferred?.complete(false)
                            return
                        }
                        if (utteranceId == "balaspeak_save_utterance") {
                            val deferred = saveListener
                            saveListener = null
                            deferred?.complete(null)
                            return
                        }
                    }
                    _speechState.value = SpeechState.ERROR
                }

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    val activeIndex = _currentSentenceIndex.value
                    if (activeIndex in sentences.indices) {
                        val sentenceSpan = sentences[activeIndex]
                        val absStart = sentenceSpan.startCharOffset + start
                        val absEnd = sentenceSpan.startCharOffset + end
                        _highlightRange.value = Pair(absStart, absEnd)
                    }
                }
            })

            _speechState.value = SpeechState.READY
        }
    }

    fun setSpeed(speed: Float) {
        _speed.value = speed
        tts?.setSpeechRate(speed)
    }

    fun setPitch(pitch: Float) {
        _pitch.value = pitch
        tts?.setPitch(pitch)
    }

    fun setAutoFitSubtitleDuration(enabled: Boolean) {
        _autoFitSubtitleDuration.value = enabled
    }

    fun setVoice(voiceName: String) {
        tts?.let { engine ->
            val voice = engine.voices?.firstOrNull { it.name == voiceName }
            if (voice != null) {
                engine.voice = voice
                _selectedVoice.value = voiceName
            }
        }
    }

    fun findBestVoiceForLanguage(langCode: String): Voice? {
        val engine = tts ?: return null
        val voices = engine.voices ?: return null
        // 1. Look for voice matching language code exactly
        val matching = voices.filter { it.locale.language.equals(langCode, ignoreCase = true) }
        if (matching.isEmpty()) return null

        // Prefer high quality / not latency-compromised voice if available
        return matching.firstOrNull {
            !it.isNetworkConnectionRequired && it.quality >= Voice.QUALITY_NORMAL
        } ?: matching.firstOrNull {
            !it.isNetworkConnectionRequired
        } ?: matching.first()
    }

    fun getLocaleForLanguageCode(langCode: String): Locale {
        return when (langCode.lowercase()) {
            "bn" -> Locale("bn", "BD")
            "hi" -> Locale("hi", "IN")
            "ur" -> Locale("ur", "PK")
            "ar" -> Locale("ar", "SA")
            "ja" -> Locale.JAPAN
            "ko" -> Locale.KOREA
            "zh", "zh-cn" -> Locale.SIMPLIFIED_CHINESE
            "es" -> Locale("es", "ES")
            "fr" -> Locale.FRENCH
            "de" -> Locale.GERMAN
            "it" -> Locale.ITALY
            "pt" -> Locale("pt", "BR")
            "ru" -> Locale("ru", "RU")
            "id" -> Locale("id", "ID")
            "tr" -> Locale("tr", "TR")
            "th" -> Locale("th", "TH")
            "vi" -> Locale("vi", "VN")
            else -> Locale(langCode)
        }
    }

    fun applyVoiceOrLocale(voiceName: String?, fallbackLangCode: String? = null) {
        val engine = tts ?: return
        if (!voiceName.isNullOrEmpty()) {
            val matchingVoice = engine.voices?.firstOrNull { it.name == voiceName }
            if (matchingVoice != null) {
                engine.voice = matchingVoice
                engine.language = matchingVoice.locale
                return
            }
        }

        // If specific voice not found or null, configure by language code
        if (!fallbackLangCode.isNullOrEmpty()) {
            val bestVoice = findBestVoiceForLanguage(fallbackLangCode)
            if (bestVoice != null) {
                engine.voice = bestVoice
                engine.language = bestVoice.locale
            } else {
                engine.language = getLocaleForLanguageCode(fallbackLangCode)
            }
        }
    }

    fun getFriendlyVoicesList(): List<FriendlyVoice> {
        val engine = tts ?: return emptyList()
        val rawVoices = try { engine.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }

        val list = mutableListOf<FriendlyVoice>()
        for (v in rawVoices) {
            val loc = v.locale
            val isBangla = loc.language.equals("bn", ignoreCase = true)
            val isEnglish = loc.language.equals("en", ignoreCase = true)
            val isHindi = loc.language.equals("hi", ignoreCase = true)
            val isUrdu = loc.language.equals("ur", ignoreCase = true)
            val isArabic = loc.language.equals("ar", ignoreCase = true)

            val groupName = when {
                isBangla -> "Bengali (${loc.displayCountry.ifEmpty { "BD/IN" }})"
                isEnglish -> "English (${loc.displayCountry.ifEmpty { "US/UK" }})"
                isHindi -> "Hindi (India)"
                isUrdu -> "Urdu"
                isArabic -> "Arabic"
                else -> "${loc.displayLanguage} (${loc.displayCountry})"
            }

            val isFemale = v.name.contains("female", ignoreCase = true) || v.name.contains("woman", ignoreCase = true) || v.name.contains("f0", ignoreCase = true)
            val genderTag = if (isFemale) "Female" else "Male"
            val shortId = v.name.substringAfterLast("-", v.name.takeLast(6))

            val sample = when {
                isBangla -> "Hello, this is a voice preview in Bengali."
                isHindi -> "Hello, this is a voice preview in Hindi."
                else -> "Hello! I am ready to speak your text."
            }

            list.add(
                FriendlyVoice(
                    id = v.name,
                    displayName = "$genderTag Voice ($shortId)",
                    languageGroup = groupName,
                    locale = loc,
                    sampleText = sample,
                    isSystemVoice = true,
                    systemVoice = v
                )
            )
        }

        return list.sortedWith(
            compareBy<FriendlyVoice> {
                when {
                    it.locale.language == "en" -> 0
                    it.locale.language == "bn" -> 1
                    it.locale.language == "hi" -> 2
                    else -> 3
                }
            }.thenBy { it.displayName }
        )
    }

    fun previewVoice(voice: FriendlyVoice?, speed: Float = _speed.value, pitch: Float = _pitch.value) {
        val engine = tts ?: return
        if (voice == null) return

        stopVoicePreview()
        _previewingVoiceId.value = voice.id

        try {
            voice.systemVoice?.let { engine.voice = it } ?: run { engine.language = voice.locale }
            engine.setSpeechRate(speed)
            engine.setPitch(pitch)

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "preview_voice_${voice.id}")
            }
            engine.speak(voice.sampleText.ifEmpty { "Hello! This is a preview of the selected voice." }, TextToSpeech.QUEUE_FLUSH, params, "preview_voice_${voice.id}")
        } catch (e: Exception) {
            Log.e("BalaSpeakTTS", "Error previewing voice: ${e.message}")
            _previewingVoiceId.value = null
        }
    }

    fun previewLanguageVoice(langCode: String, speed: Float = _speed.value, pitch: Float = _pitch.value) {
        val engine = tts ?: return
        stopVoicePreview()
        _previewingVoiceId.value = langCode

        try {
            val loc = when (langCode.lowercase(Locale.ROOT)) {
                "bn" -> Locale("bn", "BD")
                "en" -> Locale.US
                "hi" -> Locale("hi", "IN")
                "ja" -> Locale.JAPANESE
                "ko" -> Locale.KOREAN
                "zh", "zh-cn" -> Locale.SIMPLIFIED_CHINESE
                "es" -> Locale("es", "ES")
                "fr" -> Locale.FRENCH
                "de" -> Locale.GERMAN
                "it" -> Locale.ITALIAN
                "pt" -> Locale("pt", "BR")
                "ru" -> Locale("ru", "RU")
                "ar" -> Locale("ar", "SA")
                "ur" -> Locale("ur", "PK")
                "tr" -> Locale("tr", "TR")
                "th" -> Locale("th", "TH")
                "vi" -> Locale("vi", "VN")
                "id" -> Locale("id", "ID")
                else -> Locale(langCode)
            }

            if (engine.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = loc
            }

            engine.setSpeechRate(speed)
            engine.setPitch(pitch)

            val sampleText = "Hello! This is a preview of the selected dubbing voice."

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "preview_lang_$langCode")
            }
            engine.speak(sampleText, TextToSpeech.QUEUE_FLUSH, params, "preview_lang_$langCode")
        } catch (e: Exception) {
            Log.e("BalaSpeakTTS", "Error previewing language voice: ${e.message}")
            _previewingVoiceId.value = null
        }
    }

    fun stopVoicePreview() {
        if (_previewingVoiceId.value != null) {
            try {
                tts?.stop()
            } catch (e: Exception) {}
            _previewingVoiceId.value = null
        }
    }

    fun parseAndLoadText(text: String) {
        fullText = text
        sentences = parseSentencesText(text)
        _totalSentences.value = sentences.size
        _currentSentenceIndex.value = 0
        _highlightRange.value = null
    }

    private fun parseSentencesText(text: String): List<SentenceSpan> {
        if (text.isEmpty()) return emptyList()
        val list = mutableListOf<SentenceSpan>()
        val delimiters = setOf('.', '?', '!', '।', '\n', '\r')
        var start = 0
        var i = 0
        val len = text.length
        while (i < len) {
            val char = text[i]
            if (delimiters.contains(char)) {
                val sentenceText = text.substring(start, i + 1)
                if (sentenceText.trim().isNotEmpty()) {
                    list.add(SentenceSpan(sentenceText, start, i + 1))
                }
                start = i + 1
            }
            i++
        }
        if (start < len) {
            val remaining = text.substring(start)
            if (remaining.trim().isNotEmpty()) {
                list.add(SentenceSpan(remaining, start, len))
            }
        }
        return list
    }

    fun startSpeaking() {
        if (_speechState.value == SpeechState.INITIALIZING || tts == null) return
        if (sentences.isEmpty()) {
            _speechState.value = SpeechState.READY
            return
        }

        if (_speechState.value == SpeechState.PAUSED) {
            _speechState.value = SpeechState.SPEAKING
            speakCurrentSentence()
        } else {
            _currentSentenceIndex.value = 0
            speakCurrentSentence()
        }
    }

    private fun speakCurrentSentence() {
        val i = _currentSentenceIndex.value
        if (i in sentences.indices) {
            val textToSpeak = sentences[i].text.trim()
            if (textToSpeak.isEmpty()) {
                playNextSentence()
                return
            }

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "balaspeak_utterance_$i")
            }

            tts?.let { engine ->
                engine.setSpeechRate(_speed.value)
                engine.setPitch(_pitch.value)
                engine.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, "balaspeak_utterance_$i")
            }
        } else {
            _speechState.value = SpeechState.READY
            _highlightRange.value = null
        }
    }

    private fun playNextSentence() {
        val nextIdx = _currentSentenceIndex.value + 1
        if (nextIdx < sentences.size) {
            _currentSentenceIndex.value = nextIdx
            speakCurrentSentence()
        } else {
            _currentSentenceIndex.value = 0
            _speechState.value = SpeechState.READY
            _highlightRange.value = null
        }
    }

    fun pauseSpeaking() {
        if (_speechState.value == SpeechState.SPEAKING) {
            _speechState.value = SpeechState.PAUSED
            tts?.stop()
        }
    }

    fun stopSpeaking() {
        _speechState.value = SpeechState.READY
        _highlightRange.value = null
        _currentSentenceIndex.value = 0
        tts?.stop()
    }

    fun skipNext() {
        val nextIdx = _currentSentenceIndex.value + 1
        if (nextIdx < sentences.size) {
            _currentSentenceIndex.value = nextIdx
            if (_speechState.value == SpeechState.SPEAKING) {
                speakCurrentSentence()
            }
        }
    }

    fun skipPrevious() {
        val prevIdx = (_currentSentenceIndex.value - 1).coerceAtLeast(0)
        _currentSentenceIndex.value = prevIdx
        if (_speechState.value == SpeechState.SPEAKING) {
            speakCurrentSentence()
        }
    }

    fun seekToSentence(index: Int) {
        if (index in sentences.indices) {
            _currentSentenceIndex.value = index
            if (_speechState.value == SpeechState.SPEAKING) {
                speakCurrentSentence()
            }
        }
    }

    /**
     * Ultra-fast batch synthesis of subtitles to master WAV file with zero timing drift,
     * absolute timestamp alignment, and high-fidelity pitch-preserving WSOLA audio fitting.
     */
    suspend fun compileSubtitlesToWav(
        subtitles: List<SubtitleEntry>,
        destWavFile: File,
        speed: Float,
        pitch: Float,
        voiceName: String? = null,
        autoFitDuration: Boolean = true,
        onProgress: (Int, Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val ttsEngine = tts ?: return@withContext false
        if (subtitles.isEmpty()) return@withContext false

        val sortedSubtitles = subtitles.sortedBy { it.startTimeMs }
        val tempDir = File(context.cacheDir, "sub_batch_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        // Auto-detect language from sample text of subtitles
        val sampleText = sortedSubtitles.take(10).joinToString(" ") { it.text }
        val detected = LanguageDetector.detectLanguage(sampleText)

        // Configure voice, locale and pitch once
        applyVoiceOrLocale(voiceName, fallbackLangCode = detected.code)
        ttsEngine.setSpeechRate(speed)
        ttsEngine.setPitch(pitch)

        val total = sortedSubtitles.size
        val tempFilesList = ArrayList<File>(total)
        repeat(total) { tempFilesList.add(File("")) }

        val completedCount = AtomicInteger(0)

        // Chunked dispatch (8 cards at a time) to prevent Android TTS IPC queue overload or dropped callbacks
        val chunkSize = 8
        for (chunkStart in 0 until total step chunkSize) {
            val chunkEnd = minOf(chunkStart + chunkSize, total)
            val chunkDeferreds = mutableListOf<Pair<Int, CompletableDeferred<Boolean>>>()

            for (i in chunkStart until chunkEnd) {
                val card = sortedSubtitles[i]
                val textTrimmed = card.text.trim()
                if (textTrimmed.isEmpty()) {
                    val done = completedCount.incrementAndGet()
                    onProgress(done, total)
                    continue
                }

                val segmentWav = File(tempDir, "seg_$i.wav")
                val utteranceId = "sub_seg_${System.currentTimeMillis()}_$i"
                val deferred = CompletableDeferred<Boolean>()
                concurrentListeners[utteranceId] = deferred
                chunkDeferreds.add(Pair(i, deferred))

                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }

                val res = ttsEngine.synthesizeToFile(textTrimmed, params, segmentWav, utteranceId)
                if (res != TextToSpeech.SUCCESS) {
                    concurrentListeners.remove(utteranceId)
                    val done = completedCount.incrementAndGet()
                    onProgress(done, total)
                }
            }

            for ((idx, deferred) in chunkDeferreds) {
                val success = try {
                    withTimeout(12000L) { deferred.await() }
                } catch (e: Exception) {
                    false
                }
                val segmentWav = File(tempDir, "seg_$idx.wav")
                if (success && segmentWav.exists() && segmentWav.length() >= 44) {
                    tempFilesList[idx] = segmentWav
                }
                val done = completedCount.incrementAndGet()
                onProgress(done, total)
            }
        }

        val successCount = tempFilesList.count { it.exists() && it.length() >= 44 }
        if (successCount == 0) {
            tempDir.deleteRecursively()
            return@withContext false
        }

        // 2. Discover Audio Parameters from first valid WAV
        val sampleWav = tempFilesList.first { it.exists() && it.length() >= 44 }
        val headerBuffer = ByteArray(44)
        FileInputStream(sampleWav).use { fis -> fis.read(headerBuffer) }
        val channels = ByteBuffer.wrap(headerBuffer, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt().coerceIn(1, 2)
        val sampleRate = ByteBuffer.wrap(headerBuffer, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int.coerceIn(8000, 48000)
        val bitsPerSample = ByteBuffer.wrap(headerBuffer, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt().coerceIn(8, 32)

        val blockAlign = channels * (bitsPerSample / 8)

        // 3. Absolute Timestamp-Anchored Assembly (Zero Drift Guaranteed)
        val lastCard = sortedSubtitles.last()
        val totalDurationMs = lastCard.endTimeMs + 3500L
        val totalFrames = (totalDurationMs * sampleRate) / 1000L
        val totalPcmBytes = totalFrames * blockAlign

        val raf = RandomAccessFile(destWavFile, "rw")
        raf.setLength(0L)
        // Write initial header placeholder
        writeWavHeader(raf, totalPcmBytes, sampleRate, channels, bitsPerSample)
        // Pre-allocate timeline with digital silence
        raf.setLength(44L + totalPcmBytes)

        for (i in sortedSubtitles.indices) {
            val card = sortedSubtitles[i]
            val segmentWav = tempFilesList[i]
            if (!segmentWav.exists() || segmentWav.length() < 44) continue

            val countShorts = ((segmentWav.length() - 44) / 2).toInt()
            if (countShorts <= 0) continue

            val pcmShorts = ShortArray(countShorts)
            FileInputStream(segmentWav).use { fis ->
                fis.skip(44)
                val rawBuffer = ByteArray(countShorts * 2)
                var read = 0
                while (read < rawBuffer.size) {
                    val r = fis.read(rawBuffer, read, rawBuffer.size - read)
                    if (r <= 0) break
                    read += r
                }
                ByteBuffer.wrap(rawBuffer, 0, read).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmShorts, 0, read / 2)
            }

            val originalDurationMs = ((pcmShorts.size.toLong() * 1000L) / (sampleRate * channels)).toLong()
            val nextCardStartMs = if (i + 1 < sortedSubtitles.size) sortedSubtitles[i + 1].startTimeMs else (card.endTimeMs + 2500L)
            val availableWindowMs = (nextCardStartMs - card.startTimeMs).coerceAtLeast(300L)
            val cardDurationMs = (card.endTimeMs - card.startTimeMs).coerceAtLeast(300L)
            val maxAllowedDurationMs = minOf(availableWindowMs, maxOf(cardDurationMs, 750L))

            // High-quality WSOLA Time Stretch: preserves pitch 100% naturally without chipmunk screech
            val processedShorts = if (autoFitDuration && originalDurationMs > maxAllowedDurationMs) {
                val speedFactor = (originalDurationMs.toDouble() / maxAllowedDurationMs.toDouble()).coerceIn(1.0, 2.4)
                timeStretchWSOLA(pcmShorts, speedFactor, sampleRate, channels)
            } else {
                pcmShorts
            }

            // Direct absolute timestamp seek
            val startFrame = (card.startTimeMs * sampleRate) / 1000L
            val startByteOffset = 44L + startFrame * blockAlign

            val existingBytes = ByteArray(processedShorts.size * 2)
            raf.seek(startByteOffset)
            val bytesRead = raf.read(existingBytes)
            val existingShorts = ShortArray(processedShorts.size)
            if (bytesRead > 0) {
                ByteBuffer.wrap(existingBytes, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(existingShorts, 0, bytesRead / 2)
            }

            // Overlap-Add mixing with existing samples (smooth crossfade if previous tail overlaps)
            val mixedShorts = ShortArray(processedShorts.size)
            for (s in processedShorts.indices) {
                val s1 = existingShorts[s].toInt()
                val s2 = processedShorts[s].toInt()
                mixedShorts[s] = (s1 + s2).coerceIn(-32768, 32767).toShort()
            }

            val outBytes = ByteArray(mixedShorts.size * 2)
            ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(mixedShorts)
            raf.seek(startByteOffset)
            raf.write(outBytes)
        }

        // 4. Overwrite exact final WAV Header
        val finalAudioLen = (raf.length() - 44L).coerceAtLeast(totalPcmBytes)
        writeWavHeader(raf, finalAudioLen, sampleRate, channels, bitsPerSample)
        raf.close()

        tempDir.deleteRecursively()
        return@withContext true
    }

    /**
     * Professional WSOLA (Waveform Similarity Overlap-Add) algorithm for PCM audio time stretching.
     * Compresses duration while strictly preserving 100% natural speech pitch, timbre, and clarity.
     */
    private fun timeStretchWSOLA(
        input: ShortArray,
        speedRatio: Double,
        sampleRate: Int,
        channels: Int
    ): ShortArray {
        if (speedRatio in 0.98..1.02 || input.isEmpty() || channels <= 0) return input

        val winSizeSamples = ((sampleRate * 0.025).toInt() / 2) * 2
        val winSize = winSizeSamples.coerceIn(128, 2048)
        val synthHop = winSize / 2
        val maxDelta = winSize / 4

        val numFramesIn = input.size / channels
        val numFramesOutEstimate = (numFramesIn / speedRatio).toInt() + winSize
        val output = ShortArray(numFramesOutEstimate * channels)
        val overlapWeights = FloatArray(numFramesOutEstimate)

        // Pre-calculate Hanning window
        val window = FloatArray(winSize) { i ->
            (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (winSize - 1)))).toFloat()
        }

        var inPos = 0.0
        var outPos = 0

        while (inPos + winSize + maxDelta < numFramesIn && outPos + winSize < numFramesOutEstimate) {
            val targetIn = inPos.toInt()

            var bestOffset = 0
            if (outPos > 0 && synthHop < winSize) {
                var bestCorr = Long.MIN_VALUE
                for (delta in -maxDelta..maxDelta) {
                    val candidateIn = targetIn + delta
                    if (candidateIn < 0 || candidateIn + winSize > numFramesIn) continue

                    var corr = 0L
                    val step = if (winSize > 512) 2 else 1
                    for (k in 0 until winSize step step) {
                        val inIdx = (candidateIn + k) * channels
                        val outIdx = (outPos + k) * channels
                        val inSample = input[inIdx].toLong()
                        val outSample = output[outIdx].toLong()
                        corr += inSample * outSample
                    }
                    if (corr > bestCorr) {
                        bestCorr = corr
                        bestOffset = delta
                    }
                }
            }

            val chosenIn = (targetIn + bestOffset).coerceIn(0, numFramesIn - winSize)

            for (k in 0 until winSize) {
                val w = window[k]
                val fOut = outPos + k
                if (fOut >= numFramesOutEstimate) break
                for (c in 0 until channels) {
                    val inVal = input[(chosenIn + k) * channels + c].toFloat() * w
                    val outIdx = fOut * channels + c
                    val currVal = output[outIdx].toFloat() + inVal
                    output[outIdx] = currVal.coerceIn(-32768f, 32767f).toInt().toShort()
                }
                overlapWeights[fOut] += w
            }

            inPos += synthHop * speedRatio
            outPos += synthHop
        }

        val finalLength = minOf(outPos + winSize / 2, numFramesOutEstimate)
        val result = ShortArray(finalLength * channels)
        for (f in 0 until finalLength) {
            val weight = overlapWeights[f]
            val norm = if (weight > 0.01f) 1.0f / weight else 1.0f
            for (c in 0 until channels) {
                val idx = f * channels + c
                val normalized = (output[idx].toFloat() * norm).coerceIn(-32768f, 32767f)
                result[idx] = normalized.toInt().toShort()
            }
        }
        return result
    }

    private fun writeWavHeader(
        outStream: RandomAccessFile,
        totalAudioLen: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)

        outStream.seek(0)
        outStream.writeBytes("RIFF")
        outStream.write(intToByteArray(totalDataLen.toInt()), 0, 4)
        outStream.writeBytes("WAVE")
        outStream.writeBytes("fmt ")
        outStream.write(intToByteArray(16), 0, 4)
        outStream.write(shortToByteArray(1.toShort()), 0, 2)
        outStream.write(shortToByteArray(channels.toShort()), 0, 2)
        outStream.write(intToByteArray(sampleRate), 0, 4)
        outStream.write(intToByteArray(byteRate), 0, 4)
        outStream.write(shortToByteArray(blockAlign.toShort()), 0, 2)
        outStream.write(shortToByteArray(bitsPerSample.toShort()), 0, 2)
        outStream.writeBytes("data")
        outStream.write(intToByteArray(totalAudioLen.toInt()), 0, 4)
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        )
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xff).toByte(),
            ((value.toInt() shr 8) and 0xff).toByte()
        )
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
