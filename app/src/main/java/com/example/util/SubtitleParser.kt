package com.example.util

import java.io.BufferedReader
import java.io.StringReader

data class SubtitleEntry(
    val index: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
)

object SubtitleParser {

    fun parseSrtOrVtt(content: String): List<SubtitleEntry> {
        val reader = BufferedReader(StringReader(content))
        val list = mutableListOf<SubtitleEntry>()
        var line: String? = reader.readLine()

        // Regex patterns to match timestamps of SRT and VTT formats
        // e.g. 00:01:20,000 --> 00:01:23,120 or 01:20.000 --> 01:23.120
        val timestampRegex = Regex("""(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})""")
        val shortTimestampRegex = Regex("""(\d{1,2}):(\d{2})\.(\d{3})\s*-->\s*(\d{1,2}):(\d{2})\.(\d{3})""")

        var currentIndex = 1
        var currentStartMs = 0L
        var currentEndMs = 0L
        val currentTextBuilder = StringBuilder()

        while (line != null) {
            val trimmed = line.trim()

            // Skip VTT header
            if (trimmed.equals("WEBVTT", ignoreCase = true) || trimmed.startsWith("NOTE")) {
                line = reader.readLine()
                continue
            }

            // A blank line signifies the end of a subtitle card block
            if (trimmed.isEmpty()) {
                if (currentTextBuilder.isNotEmpty()) {
                    list.add(
                        SubtitleEntry(
                            index = currentIndex++,
                            startTimeMs = currentStartMs,
                            endTimeMs = currentEndMs,
                            text = cleanSubtitleText(currentTextBuilder.toString().trim())
                        )
                    )
                    currentTextBuilder.clear()
                }
                line = reader.readLine()
                continue
            }

            // Check if it matches index number or timestamps
            val isIndex = trimmed.toIntOrNull() != null
            val timeMatch = timestampRegex.find(trimmed)
            val shortTimeMatch = shortTimestampRegex.find(trimmed)

            if (timeMatch != null) {
                currentStartMs = parseTimeToMs(
                    hours = timeMatch.groupValues[1].toInt(),
                    minutes = timeMatch.groupValues[2].toInt(),
                    seconds = timeMatch.groupValues[3].toInt(),
                    millis = timeMatch.groupValues[4].toInt()
                )
                currentEndMs = parseTimeToMs(
                    hours = timeMatch.groupValues[5].toInt(),
                    minutes = timeMatch.groupValues[6].toInt(),
                    seconds = timeMatch.groupValues[7].toInt(),
                    millis = timeMatch.groupValues[8].toInt()
                )
            } else if (shortTimeMatch != null) {
                currentStartMs = parseTimeToMs(
                    hours = 0,
                    minutes = shortTimeMatch.groupValues[1].toInt(),
                    seconds = shortTimeMatch.groupValues[2].toInt(),
                    millis = shortTimeMatch.groupValues[3].toInt()
                )
                currentEndMs = parseTimeToMs(
                    hours = 0,
                    minutes = shortTimeMatch.groupValues[4].toInt(),
                    seconds = shortTimeMatch.groupValues[5].toInt(),
                    millis = shortTimeMatch.groupValues[6].toInt()
                )
            } else if (isIndex) {
                // If we hit an index, we can parse it as sequence id if we don't have active subtitle building.
                // Normally SRT starts with an index. We can safely skip assigning it manually because we assign continuous IDs.
            } else {
                // It is a text line
                if (currentTextBuilder.isNotEmpty()) {
                    currentTextBuilder.append("\n")
                }
                currentTextBuilder.append(trimmed)
            }

            line = reader.readLine()
        }

        // Add final block if left
        if (currentTextBuilder.isNotEmpty()) {
            list.add(
                SubtitleEntry(
                    index = currentIndex++,
                    startTimeMs = currentStartMs,
                    endTimeMs = currentEndMs,
                    text = cleanSubtitleText(currentTextBuilder.toString().trim())
                )
            )
        }

        return list
    }

    private fun cleanSubtitleText(rawText: String): String {
        // Clean style tags from the text content (e.g. <b>, <i>, color tags)
        var cleanText = rawText.replace(Regex("<[^>]*>"), "")
        // Remove SSA/ASS styled curly bracket specifications (e.g., {\an5})
        cleanText = cleanText.replace(Regex("\\{[^}]*\\}"), "")
        return cleanText
    }

    private fun parseTimeToMs(hours: Int, minutes: Int, seconds: Int, millis: Int): Long {
        return (hours * 3600000L) + (minutes * 60000L) + (seconds * 1000L) + millis
    }

    fun getCleanTextOnly(content: String): String {
        val entries = parseSrtOrVtt(content)
        return entries.joinToString("\n\n") { it.text }
    }
}
