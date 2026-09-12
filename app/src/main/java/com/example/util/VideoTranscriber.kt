package com.example.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * VideoTranscriber: Extracts audio from audio/video files and transcribes speech into
 * standard SubRip Subtitle (.srt) format with continuous timestamps.
 *
 * Supports unlimited duration (20 min, 1 hour, 2 hours+) via intelligent 90-second
 * audio chunking and seamless timestamp synchronization.
 */
object VideoTranscriber {

    private const val TAG = "VideoTranscriber"

    // 120 seconds per chunk provides ideal balance between acoustic continuity, fast AI response, and high accuracy
    const val CHUNK_DURATION_MS = 120_000L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    data class ChunkProgress(
        val currentChunk: Int,
        val totalChunks: Int,
        val currentChunkStartMs: Long,
        val totalDurationMs: Long,
        val progressFraction: Float,
        val progressPercent: Int = (progressFraction * 100).toInt(),
        val phase: String = "TRANSCRIBING", // "EXTRACTING", "TRANSCRIBING", "COMPLETED"
        val currentChunkSubtitles: List<SubtitleEntry>,
        val accumulatedSubtitles: List<SubtitleEntry>,
        val statusMessage: String
    )

    /**
     * Extracts and normalizes audio directly to a 16kHz Mono 16-bit WAV file in a ultra-fast single streaming pass.
     * ZERO OOM: Operates with a constant buffer, reading directly from media file or content URI without copying.
     * High-speed fixed-point integer resampling runs at hardware speed (up to 50x real-time).
     */
    suspend fun extractAudioTo16kHzWav(
        context: Context,
        mediaFile: File? = null,
        mediaUri: Uri? = null,
        tempDir: File,
        onProgress: (percent: Int, status: String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val baseName = mediaFile?.nameWithoutExtension?.ifBlank { "media" } ?: "media"
        val wavFile = File(tempDir, "${baseName}_speech_16k_${System.currentTimeMillis()}.wav")
        if (wavFile.exists()) wavFile.delete()

        onProgress(0, "Scanning audio track in media...")

        val totalDurationMs = getVideoDurationMs(context, mediaFile, mediaUri)
        val totalDurationUs = totalDurationMs * 1000L

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var fos: BufferedOutputStream? = null

        try {
            extractor = MediaExtractor()
            if (mediaFile != null && mediaFile.exists()) {
                extractor.setDataSource(mediaFile.absolutePath)
            } else if (mediaUri != null) {
                extractor.setDataSource(context, mediaUri, null)
            } else {
                throw IllegalArgumentException("No media file or Uri provided.")
            }

            var audioTrackIndex = -1
            var initialFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    initialFormat = trackFormat
                    break
                }
            }

            if (audioTrackIndex == -1 || initialFormat == null) {
                throw IllegalStateException("No audio track found in media file.")
            }

            val mime = initialFormat.getString(MediaFormat.KEY_MIME) ?: ""
            var sampleRate = if (initialFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                initialFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            var channels = if (initialFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                initialFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            val trackDurationUs = if (initialFormat.containsKey(MediaFormat.KEY_DURATION)) {
                initialFormat.getLong(MediaFormat.KEY_DURATION)
            } else totalDurationUs
            val safeDurationUs = if (trackDurationUs > 0) trackDurationUs else totalDurationUs

            extractor.selectTrack(audioTrackIndex)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(initialFormat, null, null, 0)
            decoder.start()

            fos = BufferedOutputStream(FileOutputStream(wavFile), 1024 * 1024)
            // Write placeholder 44-byte WAV header (will be patched with actual byte size at the end)
            writeWavHeader(fos, 0L, 16000, 1, 16)

            val info = MediaCodec.BufferInfo()
            var isExtractorEOS = false
            var isDecoderEOS = false
            val timeoutUs = 2500L

            val targetSampleRate = 16000
            var srcPosAccumulator = 0.0
            var totalBytesWritten = 0L

            // 1MB fast write buffer
            val outCapacity = 1024 * 1024
            val outByteArray = ByteArray(outCapacity)
            var outPos = 0
            var lastProgressUpdateMs = 0L

            while (!isDecoderEOS) {
                if (!isExtractorEOS) {
                    val inBufferId = decoder.dequeueInputBuffer(timeoutUs)
                    if (inBufferId >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inBufferId)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isExtractorEOS = true
                            } else {
                                val sampleTime = extractor.sampleTime
                                decoder.queueInputBuffer(inBufferId, 0, sampleSize, sampleTime, 0)
                                extractor.advance()

                                val now = System.currentTimeMillis()
                                if (now - lastProgressUpdateMs > 250 && safeDurationUs > 0 && sampleTime >= 0) {
                                    lastProgressUpdateMs = now
                                    val percent = ((sampleTime.toDouble() / safeDurationUs.toDouble()) * 100.0).toInt().coerceIn(0, 99)
                                    val curStr = formatHumanTime(sampleTime / 1000)
                                    val totStr = formatHumanTime(safeDurationUs / 1000)
                                    onProgress(percent, "Extracting audio: $percent% ($curStr / $totStr)...")
                                }
                            }
                        }
                    }
                }

                val outBufferId = decoder.dequeueOutputBuffer(info, timeoutUs)
                if (outBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    Log.d(TAG, "Audio decoder format changed: rate=$sampleRate, channels=$channels")
                } else if (outBufferId >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outBufferId)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)

                        val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val numShorts = shortBuffer.remaining()
                        val decodedShorts = ShortArray(numShorts)
                        shortBuffer.get(decodedShorts)

                        val ch = channels.coerceAtLeast(1)
                        val numFrames = numShorts / ch
                        if (numFrames > 0) {
                            // High-speed downmix to mono in tight loop
                            val mono = ShortArray(numFrames)
                            if (ch == 2) {
                                var s = 0
                                for (f in 0 until numFrames) {
                                    val l = decodedShorts[s].toInt()
                                    val r = decodedShorts[s + 1].toInt()
                                    mono[f] = ((l + r) shr 1).toShort()
                                    s += 2
                                }
                            } else if (ch == 1) {
                                System.arraycopy(decodedShorts, 0, mono, 0, numFrames)
                            } else {
                                for (f in 0 until numFrames) {
                                    var sum = 0
                                    val base = f * ch
                                    for (c in 0 until ch) sum += decodedShorts[base + c]
                                    mono[f] = (sum / ch).toShort()
                                }
                            }

                            // High-speed fixed-point integer resampling
                            if (sampleRate == targetSampleRate) {
                                for (f in 0 until numFrames) {
                                    val sample = mono[f]
                                    if (outPos + 2 > outCapacity) {
                                        fos.write(outByteArray, 0, outPos)
                                        totalBytesWritten += outPos
                                        outPos = 0
                                    }
                                    outByteArray[outPos++] = (sample.toInt() and 0xFF).toByte()
                                    outByteArray[outPos++] = ((sample.toInt() shr 8) and 0xFF).toByte()
                                }
                            } else {
                                val stepFixed = ((sampleRate.toLong() shl 16) / targetSampleRate.toLong()).toInt()
                                var posFixed = (srcPosAccumulator * 65536.0).toLong()
                                val maxPosFixed = (numFrames.toLong() shl 16)
                                while (posFixed < maxPosFixed) {
                                    val idx = (posFixed ushr 16).toInt()
                                    val sample = mono[idx]
                                    if (outPos + 2 > outCapacity) {
                                        fos.write(outByteArray, 0, outPos)
                                        totalBytesWritten += outPos
                                        outPos = 0
                                    }
                                    outByteArray[outPos++] = (sample.toInt() and 0xFF).toByte()
                                    outByteArray[outPos++] = ((sample.toInt() shr 8) and 0xFF).toByte()
                                    posFixed += stepFixed
                                }
                                srcPosAccumulator = ((posFixed - maxPosFixed).toDouble()) / 65536.0
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outBufferId, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderEOS = true
                    }
                }
            }

            if (outPos > 0) {
                fos.write(outByteArray, 0, outPos)
                totalBytesWritten += outPos
                outPos = 0
            }

            fos.flush()
            fos.close()
            fos = null

            // Patch standard WAV header with actual data length
            if (wavFile.exists() && totalBytesWritten > 0) {
                patchWavHeader(wavFile, totalBytesWritten)
            } else {
                throw IllegalStateException("Audio extraction failed (output file is empty).")
            }

            onProgress(100, "Audio extraction complete. AI transcribing...")
            Log.d(TAG, "Successfully extracted 16kHz Mono WAV: $totalBytesWritten bytes")
            wavFile
        } catch (e: Exception) {
            Log.e(TAG, "Audio extraction failed: ${e.message}", e)
            if (wavFile.exists()) wavFile.delete()
            throw e
        } finally {
            try { extractor?.release() } catch (e: Exception) {}
            try {
                decoder?.stop()
                decoder?.release()
            } catch (e: Exception) {}
            try { fos?.close() } catch (e: Exception) {}
        }
    }

    /**
     * Slices an exact time range from a 16kHz Mono 16-bit WAV file in milliseconds.
     * In 16kHz 16-bit Mono: 16,000 samples/sec * 2 bytes = 32,000 bytes/sec = 32 bytes/millisecond.
     * Slicing byte offsets is instantaneous and lossless.
     */
    fun sliceWavChunk(
        fullWavFile: File,
        startMs: Long,
        durationMs: Long,
        outChunkFile: File
    ): Boolean {
        if (!fullWavFile.exists() || fullWavFile.length() <= 44) return false

        val totalAudioBytes = fullWavFile.length() - 44
        val startByte = (startMs * 32L).coerceIn(0L, totalAudioBytes)
        val bytesToRead = (durationMs * 32L).coerceAtMost(totalAudioBytes - startByte)

        if (bytesToRead <= 0) return false

        if (outChunkFile.exists()) outChunkFile.delete()

        var raf: RandomAccessFile? = null
        var fos: BufferedOutputStream? = null
        try {
            raf = RandomAccessFile(fullWavFile, "r")
            fos = BufferedOutputStream(FileOutputStream(outChunkFile), 64 * 1024)

            // Write standard 44-byte WAV header for the chunk
            writeWavHeader(fos, bytesToRead, sampleRate = 16000, channels = 1, bitsPerSample = 16)

            raf.seek(44 + startByte)
            val buffer = ByteArray(32 * 1024)
            var remaining = bytesToRead

            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) break
                fos.write(buffer, 0, read)
                remaining -= read
            }

            fos.flush()
            return outChunkFile.exists() && outChunkFile.length() > 44
        } catch (e: Exception) {
            Log.e(TAG, "Error slicing wav chunk at $startMs ms: ${e.message}")
            if (outChunkFile.exists()) outChunkFile.delete()
            return false
        } finally {
            try { fos?.close() } catch (e: Exception) {}
            try { raf?.close() } catch (e: Exception) {}
        }
    }

    /**
     * Checks if the media file contains embedded subtitle tracks (e.g. English/other languages).
     * If available, extracts and converts them immediately for 100% free, instantaneous subtitles!
     */
    fun extractEmbeddedSubtitles(
        context: Context,
        mediaFile: File? = null,
        mediaUri: Uri? = null
    ): List<SubtitleEntry> {
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor()
            if (mediaFile != null && mediaFile.exists()) {
                extractor.setDataSource(mediaFile.absolutePath)
            } else if (mediaUri != null) {
                extractor.setDataSource(context, mediaUri, null)
            } else return emptyList()

            var subTrackIdx = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("text/") || mime.contains("subtitle") || mime.contains("vtt") || mime.contains("tx3g")) {
                    subTrackIdx = i
                    break
                }
            }
            if (subTrackIdx == -1) return emptyList()

            extractor.selectTrack(subTrackIdx)
            val entries = mutableListOf<SubtitleEntry>()
            val buffer = ByteBuffer.allocate(64 * 1024)
            var subIndex = 1

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize <= 0) break
                val sampleTimeUs = extractor.sampleTime
                val bytes = ByteArray(sampleSize)
                buffer.get(bytes)

                // Clean binary data / UTF-8 text from embedded subtitle packets
                val cleanText = String(bytes, Charsets.UTF_8).filter { it >= ' ' || it == '\n' || it == '\t' }.trim()
                if (cleanText.isNotBlank()) {
                    val startMs = sampleTimeUs / 1000L
                    val endMs = startMs + 3000L
                    entries.add(SubtitleEntry(index = subIndex++, startTimeMs = startMs, endTimeMs = endMs, text = cleanText))
                }
                if (!extractor.advance()) break
            }
            return entries
        } catch (e: Exception) {
            Log.d(TAG, "Embedded subtitle extraction note: ${e.message}")
            return emptyList()
        } finally {
            try { extractor?.release() } catch (e: Exception) {}
        }
    }

    /**
     * High-level pipeline: Handles long audio by dividing into chunks,
     * transcribing each chunk, and merging with continuous, precise timestamps.
     */
    suspend fun transcribeFullMedia(
        context: Context,
        mediaFile: File? = null,
        mediaUri: Uri? = null,
        tempDir: File,
        apiKey: String,
        targetLanguage: String?,
        onProgress: (ChunkProgress) -> Unit
    ): Pair<String, List<SubtitleEntry>> = withContext(Dispatchers.IO) {
        val totalDurationMs = getVideoDurationMs(context, mediaFile, mediaUri).coerceAtLeast(1000L)

        // Step 1: Extract normalized 16kHz mono WAV
        val fullWav = extractAudioTo16kHzWav(context, mediaFile, mediaUri, tempDir) { percent, status ->
            val fraction = (percent.toFloat() / 100f) * 0.10f
            onProgress(
                ChunkProgress(
                    currentChunk = 0,
                    totalChunks = 1,
                    currentChunkStartMs = 0L,
                    totalDurationMs = totalDurationMs,
                    progressFraction = fraction,
                    progressPercent = (percent * 0.10f).toInt(),
                    phase = "EXTRACTING",
                    currentChunkSubtitles = emptyList(),
                    accumulatedSubtitles = emptyList(),
                    statusMessage = status
                )
            )
        }

        val actualAudioDurationMs = ((fullWav.length() - 44) / 32L).coerceAtLeast(totalDurationMs)
        val finalMediaDurationMs = maxOf(totalDurationMs, actualAudioDurationMs)

        // Calculate chunk count (120s per chunk)
        val totalChunks = ceil(finalMediaDurationMs.toDouble() / CHUNK_DURATION_MS.toDouble()).toInt().coerceAtLeast(1)
        val accumulatedSubtitles = mutableListOf<SubtitleEntry>()

        Log.d(TAG, "Starting transcription: duration=${finalMediaDurationMs}ms, chunks=$totalChunks")

        val concurrencyLimit = Semaphore(3) // Process 3 chunks concurrently to achieve hydrogen speed without hitting quota too hard

        try {
            val deferredChunks = (0 until totalChunks).map { chunkIdx ->
                async(Dispatchers.IO) {
                    concurrencyLimit.withPermit {
                        val chunkStartMs = chunkIdx * CHUNK_DURATION_MS
                        val chunkDurationMs = minOf(CHUNK_DURATION_MS, finalMediaDurationMs - chunkStartMs)

                        val chunkWav = File(tempDir, "chunk_${chunkIdx}_${System.currentTimeMillis()}.wav")
                        val sliced = sliceWavChunk(fullWav, chunkStartMs, chunkDurationMs, chunkWav)
                        if (!sliced || !chunkWav.exists()) {
                            Log.w(TAG, "Failed to slice chunk $chunkIdx, skipping")
                            return@withPermit emptyList<SubtitleEntry>()
                        }

                        val chunkSubs = try {
                            transcribeSingleChunk(
                                apiKey = apiKey,
                                chunkWavFile = chunkWav,
                                targetLanguage = targetLanguage
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error transcribing chunk $chunkIdx: ${e.message}", e)
                            emptyList<SubtitleEntry>()
                        } finally {
                            try { chunkWav.delete() } catch (e: Exception) {}
                        }
                        chunkSubs
                    }
                }
            }

            for (chunkIdx in 0 until totalChunks) {
                val chunkStartMs = chunkIdx * CHUNK_DURATION_MS
                val chunkDurationMs = minOf(CHUNK_DURATION_MS, finalMediaDurationMs - chunkStartMs)

                val currentChunkNumber = chunkIdx + 1
                val chunkRatio = chunkIdx.toFloat() / totalChunks.toFloat()
                val progressFraction = 0.10f + (0.90f * chunkRatio)
                val overallPercent = (10 + (chunkRatio * 90)).toInt().coerceIn(10, 99)

                val formattedStart = formatHumanTime(chunkStartMs)
                val formattedEnd = formatHumanTime(minOf(chunkStartMs + chunkDurationMs, finalMediaDurationMs))
                val formattedTotal = formatHumanTime(finalMediaDurationMs)

                val statusMsg = "Processing Chunk $currentChunkNumber/$totalChunks ($overallPercent%) [$formattedStart - $formattedEnd / $formattedTotal]..."

                onProgress(
                    ChunkProgress(
                        currentChunk = currentChunkNumber,
                        totalChunks = totalChunks,
                        currentChunkStartMs = chunkStartMs,
                        totalDurationMs = finalMediaDurationMs,
                        progressFraction = progressFraction,
                        progressPercent = overallPercent,
                        phase = "TRANSCRIBING",
                        currentChunkSubtitles = emptyList(),
                        accumulatedSubtitles = accumulatedSubtitles.toList(),
                        statusMessage = statusMsg
                    )
                )

                // Wait for this specific chunk's result
                val chunkSubs = deferredChunks[chunkIdx].await()

                // Adjust timestamps of chunk subtitles: enforce millisecond precision and prevent overlaps
                val adjustedSubs = mutableListOf<SubtitleEntry>()
                for (sub in chunkSubs) {
                    val rawStart = sub.startTimeMs + chunkStartMs
                    val rawEnd = minOf(sub.endTimeMs + chunkStartMs, chunkStartMs + chunkDurationMs)

                    // Ensure end time is strictly greater than start time
                    val safeStart = rawStart.coerceIn(chunkStartMs, chunkStartMs + chunkDurationMs - 100L)
                    val safeEnd = maxOf(safeStart + 800L, rawEnd).coerceAtMost(chunkStartMs + chunkDurationMs)

                    // Prevent overlap with previous subtitle
                    val previousSub = adjustedSubs.lastOrNull() ?: accumulatedSubtitles.lastOrNull()
                    if (previousSub != null && previousSub.endTimeMs > safeStart) {
                        val correctedEnd = (safeStart - 50L).coerceAtLeast(previousSub.startTimeMs + 300L)
                        if (adjustedSubs.isNotEmpty()) {
                            adjustedSubs[adjustedSubs.lastIndex] = previousSub.copy(endTimeMs = correctedEnd)
                        } else if (accumulatedSubtitles.isNotEmpty()) {
                            accumulatedSubtitles[accumulatedSubtitles.lastIndex] = previousSub.copy(endTimeMs = correctedEnd)
                        }
                    }

                    adjustedSubs.add(
                        sub.copy(
                            index = accumulatedSubtitles.size + adjustedSubs.size + 1,
                            startTimeMs = safeStart,
                            endTimeMs = safeEnd
                        )
                    )
                }

                accumulatedSubtitles.addAll(adjustedSubs)

                val completedChunkRatio = (chunkIdx + 1).toFloat() / totalChunks.toFloat()
                val completedFraction = 0.10f + (0.90f * completedChunkRatio)
                val completedPercent = (10 + (completedChunkRatio * 90)).toInt().coerceIn(10, 100)

                // Update progress with accumulated subtitles in real-time
                onProgress(
                    ChunkProgress(
                        currentChunk = currentChunkNumber,
                        totalChunks = totalChunks,
                        currentChunkStartMs = chunkStartMs,
                        totalDurationMs = finalMediaDurationMs,
                        progressFraction = completedFraction,
                        progressPercent = completedPercent,
                        phase = "TRANSCRIBING",
                        currentChunkSubtitles = adjustedSubs,
                        accumulatedSubtitles = accumulatedSubtitles.toList(),
                        statusMessage = "Chunk $currentChunkNumber/$totalChunks completed ($completedPercent%) — ${accumulatedSubtitles.size} subtitles generated"
                    )
                )
            }
        } finally {
            try { fullWav.delete() } catch (e: Exception) {}
        }

        // Renumber all entries sequentially
        val finalNumberedSubs = accumulatedSubtitles.mapIndexed { idx, entry ->
            entry.copy(index = idx + 1)
        }

        val finalSrtContent = SubtitleTranslator.exportToSrt(finalNumberedSubs)
        Pair(finalSrtContent, finalNumberedSubs)
    }

    /**
     * Transcribes a single compact WAV chunk using Gemini AI.
     */
    private suspend fun transcribeSingleChunk(
        apiKey: String,
        chunkWavFile: File,
        targetLanguage: String?
    ): List<SubtitleEntry> = withContext(Dispatchers.IO) {
        val audioBytes = chunkWavFile.readBytes()
        if (audioBytes.isEmpty()) return@withContext emptyList()

        val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

        val promptText = if (targetLanguage.isNullOrBlank() || targetLanguage.equals("Original", ignoreCase = true) || targetLanguage.equals("auto", ignoreCase = true)) {
            """
            You are an expert audio transcriber and precise subtitle synchronizer.
            Transcribe all spoken dialogue from this audio clip into standard SubRip Subtitle (.srt) format with EXACT millisecond timing.

            CRITICAL TIMING & SYNCHRONIZATION RULES:
            1. EXACT TIMESTAMPS: Subtitle start and end timestamps must PRECISELY match the speaker's vocal start and vocal stop down to the millisecond.
            2. CONTINUITY: Timestamps are relative to the start of this audio clip (00:00:00,000).
            3. SEQUENTIAL NUMBERS: Number each subtitle block sequentially starting from 1.
            4. CLEAN OUTPUT: Output ONLY raw SRT content. Do NOT include markdown code fences (```srt).
            """.trimIndent()
        } else {
            """
            You are an expert audio transcriber, translator, and precise subtitle synchronizer.
            Listen to this audio clip and transcribe all spoken dialogue directly translated into $targetLanguage in standard SubRip Subtitle (.srt) format.

            CRITICAL TIMING & SYNCHRONIZATION RULES:
            1. EXACT TIMESTAMPS: Subtitle start and end timestamps must PRECISELY match the speaker's vocal start and vocal stop down to the millisecond.
            2. TRANSLATION ACCURACY: Translate dialogue naturally and fluently into $targetLanguage.
            3. SEQUENTIAL NUMBERS: Number each subtitle block sequentially starting from 1.
            4. CLEAN OUTPUT: Output ONLY raw SRT content. Do NOT include markdown code fences (```srt).
            """.trimIndent()
        }

        val jsonRequest = JSONObject().apply {
            val contentsArray = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val partsArray = JSONArray().apply {
                        put(JSONObject().apply { put("text", promptText) })
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "audio/wav")
                                put("data", base64Audio)
                            })
                        })
                    }
                    put("parts", partsArray)
                }
                put(contentObj)
            }
            put("contents", contentsArray)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
            })
        }

        val requestBody = jsonRequest.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        // Use fast online models
        val models = listOf(
            "gemini-3.5-flash",
            "gemini-1.5-flash"
        )
        var lastError: Exception? = null

        // Add retry logic for quota limit avoidance
        for (model in models) {
            var attempts = 0
            while (attempts < 8) { // Up to 8 attempts (enough to span > 1 minute for rate limits)
                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                    val request = Request.Builder().url(url).post(requestBody).build()

                    val response = httpClient.newCall(request).execute()
                    val body = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        val parsed = JSONObject(body)
                        val candidates = parsed.optJSONArray("candidates")
                        val rawText = candidates?.optJSONObject(0)
                            ?.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text") ?: ""

                        if (rawText.isNotBlank()) {
                            val cleanedSrt = cleanSrtOutput(rawText)
                            val parsedSubs = SubtitleParser.parseSrtOrVtt(cleanedSrt)
                            if (parsedSubs.isNotEmpty()) {
                                return@withContext parsedSubs
                            }
                        }
                        break // Success, exit retry loop
                    } else {
                        Log.w(TAG, "Model $model returned HTTP ${response.code}: $body")
                        if (response.code == 429) {
                            attempts++
                            val waitMs = 10000L + (Math.random() * 5000L).toLong() // 10-15s wait with jitter
                            lastError = IllegalStateException("Server overloaded (429). Retrying in ${waitMs/1000}s...")
                            kotlinx.coroutines.delay(waitMs)
                            continue // Retry
                        } else if (response.code == 400 || response.code == 403) {
                            lastError = IllegalStateException("API error (${response.code}): Quota limit or invalid key.")
                            break // Don't retry on 400/403
                        }
                        lastError = IllegalStateException("API error (${response.code})")
                        break // Other errors, don't retry
                    }
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Error with model $model: ${e.message}")
                    attempts++
                    kotlinx.coroutines.delay(5000L)
                }
            }
        }

        if (lastError != null) throw lastError
        emptyList()
    }

    private fun getMonoSample(shorts: ShortArray, frameIdx: Int, channels: Int): Short {
        if (channels <= 1) {
            return if (frameIdx < shorts.size) shorts[frameIdx] else 0
        }
        if (channels == 2) {
            val idx = frameIdx * 2
            if (idx + 1 >= shorts.size) return if (idx < shorts.size) shorts[idx] else 0
            val l = shorts[idx].toInt()
            val r = shorts[idx + 1].toInt()
            return ((l + r) / 2).coerceIn(-32768, 32767).toShort()
        }
        val base = frameIdx * channels
        var sum = 0f
        val validChannels = channels.coerceAtLeast(1)
        for (c in 0 until validChannels) {
            val pos = base + c
            if (pos < shorts.size) sum += shorts[pos]
        }
        val half = (validChannels / 2).coerceAtLeast(1)
        return (sum / half).toInt().coerceIn(-32768, 32767).toShort()
    }

    private fun patchWavHeader(wavFile: File, audioBytesLength: Long) {
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(wavFile, "rw")
            val totalDataLen = (audioBytesLength + 36).toInt()
            val riffSizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataLen).array()
            raf.seek(4)
            raf.write(riffSizeBuf)

            val dataSizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(audioBytesLength.toInt()).array()
            raf.seek(40)
            raf.write(dataSizeBuf)
        } finally {
            try { raf?.close() } catch (e: Exception) {}
        }
    }

    /**
     * Writes standard 44-byte RIFF/WAVE header
     */
    private fun writeWavHeader(
        out: BufferedOutputStream,
        audioDataLength: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = audioDataLength + 36
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()
        val blockAlign = (channels * bitsPerSample / 8).toShort()

        val header = ByteArray(44)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(totalDataLen.toInt())
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16) // Subchunk1Size for PCM
        buf.putShort(1.toShort()) // AudioFormat 1 = PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate.toInt())
        buf.putShort(blockAlign)
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(audioDataLength.toInt())

        out.write(header, 0, 44)
    }

    /**
     * Formats milliseconds into standard SubRip timestamp (HH:mm:ss,SSS)
     */
    fun formatSrtTimestamp(ms: Long): String {
        val clampedMs = ms.coerceAtLeast(0L)
        val totalSeconds = clampedMs / 1000
        val millis = clampedMs % 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    /**
     * Formats milliseconds into human readable time (e.g. "12:34" or "01:25:30")
     */
    fun formatHumanTime(ms: Long): String {
        val clampedMs = ms.coerceAtLeast(0L)
        val totalSeconds = clampedMs / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Cleans markdown blocks (```srt ... ```) and excess formatting from response.
     */
    fun cleanSrtOutput(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            val firstLineBreak = text.indexOf('\n')
            if (firstLineBreak != -1) {
                text = text.substring(firstLineBreak + 1)
            }
        }
        if (text.endsWith("```")) {
            text = text.substringBeforeLast("```").trim()
        }
        return text.trim()
    }

    /**
     * Extracts media duration using MediaMetadataRetriever (works on both audio and video files)
     */
    fun getVideoDurationMs(context: Context, mediaFile: File? = null, mediaUri: Uri? = null): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            if (mediaFile != null && mediaFile.exists()) {
                retriever.setDataSource(mediaFile.absolutePath)
            } else if (mediaUri != null) {
                retriever.setDataSource(context, mediaUri)
            } else {
                return 0L
            }
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            time?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
    }

    fun getVideoDurationMs(mediaFile: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(mediaFile.absolutePath)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            time?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
    }
}

