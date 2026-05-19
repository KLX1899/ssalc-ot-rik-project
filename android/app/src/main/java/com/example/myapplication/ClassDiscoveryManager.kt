package com.example.myapplication

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ClassDiscoveryManager {

    private const val DISCOVERED_FILE = "discovered_classes.json"
    private const val SCHEDULE_FILE = "schedule.json"

    data class DiscoveredClass(
        val name: String,
        val url: String,
        val days: List<String>,
        val start: String,
        val end: String,
        var platform: String = "LMS",
        var enabled: Boolean = true
    )

    /**
     * IMPORTANT:
     * We must match LONGER Persian weekday names before "شنبه",
     * because words like "یکشنبه", "دوشنبه", "سه شنبه", ... all contain "شنبه".
     */
    private val orderedDayMatchers = listOf(
        "چهارشنبه" to "wed",
        "چهار شنبه" to "wed",
        "پنجشنبه" to "thu",
        "پنج شنبه" to "thu",
        "دوشنبه" to "mon",
        "دوشنبه" to "mon",
        "سه‌شنبه" to "tue",
        "سه شنبه" to "tue",
        "یکشنبه" to "sun",
        "یک شنبه" to "sun",
        "جمعه" to "fri",
        "شنبه" to "sat"
    )

    /**
     * Normalize Persian text so matching is more reliable.
     */
    private fun normalizePersian(text: String): String {
        return text
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace('\u200c', ' ')   // ZWNJ → space
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun padTime(time: String): String {
        val parts = time.trim().split(":")
        val h = parts[0].toInt()
        val m = parts[1].toInt()
        return "%02d:%02d".format(h, m)
    }

    /**
     * Example input:
     *   "یکشنبه 15:00 - 16:30 / سه شنبه 15:00 - 16:30"
     *
     * Output:
     *   [("sun","15:00","16:30"), ("tue","15:00","16:30")]
     */
    fun parseScheduleText(raw: String): List<Triple<String, String, String>> {
        val results = mutableListOf<Triple<String, String, String>>()
        val normalizedRaw = normalizePersian(raw)

        val segments = normalizedRaw
            .split("/")
            .map { normalizePersian(it) }
            .filter { it.isNotBlank() }

        val timeRegex = Regex("""(\d{1,2}:\d{2})\s*-\s*(\d{1,2}:\d{2})""")

        for (segment in segments) {
            val timeMatch = timeRegex.find(segment) ?: continue

            val startTime = padTime(timeMatch.groupValues[1])
            val endTime = padTime(timeMatch.groupValues[2])

            // Everything before the time is assumed to contain the day name
            val dayPart = normalizePersian(segment.substring(0, timeMatch.range.first))

            var matchedDayCode: String? = null

            // Match longer/more specific names first
            for ((persianDay, code) in orderedDayMatchers) {
                if (dayPart.contains(normalizePersian(persianDay))) {
                    matchedDayCode = code
                    break
                }
            }

            if (matchedDayCode != null) {
                results.add(Triple(matchedDayCode, startTime, endTime))
            }
        }

        return results
    }

    fun saveDiscoveredClasses(context: Context, classes: List<DiscoveredClass>) {
        val arr = JSONArray()
        for (c in classes) {
            val obj = JSONObject().apply {
                put("name", c.name)
                put("url", c.url)
                put("days", JSONArray(c.days))
                put("start", c.start)
                put("end", c.end)
                put("platform", c.platform)
                put("enabled", c.enabled)
            }
            arr.put(obj)
        }
        File(context.filesDir, DISCOVERED_FILE).writeText(arr.toString(2))
    }

    fun loadDiscoveredClasses(context: Context): List<DiscoveredClass> {
        val file = File(context.filesDir, DISCOVERED_FILE)
        if (!file.exists()) return emptyList()

        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val daysArr = obj.getJSONArray("days")
                DiscoveredClass(
                    name = obj.getString("name"),
                    url = obj.optString("url", ""),
                    days = (0 until daysArr.length()).map { daysArr.getString(it) },
                    start = obj.getString("start"),
                    end = obj.getString("end"),
                    platform = obj.optString("platform", "LMS"),
                    enabled = obj.optBoolean("enabled", true)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun generateScheduleJson(context: Context, classes: List<DiscoveredClass>) {
        val enabledClasses = classes.filter { it.enabled && it.days.isNotEmpty() }

        data class Key(
            val name: String,
            val start: String,
            val end: String,
            val platform: String
        )

        val grouped = mutableMapOf<Key, MutableSet<String>>()

        for (c in enabledClasses) {
            val key = Key(c.name, c.start, c.end, c.platform)
            grouped.getOrPut(key) { mutableSetOf() }.addAll(c.days)
        }

        val arr = JSONArray()
        for ((key, days) in grouped) {
            arr.put(
                JSONObject().apply {
                    put("name", key.name)
                    put("days", JSONArray(days.toList()))
                    put("start", key.start)
                    put("end", key.end)
                    put("platform", key.platform)
                }
            )
        }

        File(context.filesDir, SCHEDULE_FILE).writeText(arr.toString(2))
    }

    fun loadScheduleJson(context: Context): String {
        val internalFile = File(context.filesDir, SCHEDULE_FILE)
        if (internalFile.exists()) {
            return internalFile.readText()
        }

        return try {
            context.assets.open(SCHEDULE_FILE).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "[]"
        }
    }

    /**
     * Optional helper: wipe discovered/schedule files so the user can rediscover cleanly.
     */
    fun clearSavedDiscovery(context: Context) {
        File(context.filesDir, DISCOVERED_FILE).delete()
        File(context.filesDir, SCHEDULE_FILE).delete()
    }
}