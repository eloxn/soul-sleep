package app.glyph

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class Track(val id: Long, val path: String, val title: String, val artist: String, val album: String, val durSec: Int)
enum class Grain { WORD, LINE }
data class Found(val text: String, val grain: Grain, val source: String)

private data class LrcRedCandidate(
    val id: String,
    val isrc: String,
    val lyricsUrl: String,
    val timingType: String,
    val duration: Int
)

/** Priority: lrc.red word-sync -> LRCLIB line-sync. Plain (unsynced) text is never written. */
object Sources {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { it.proceed(it.request().newBuilder().header("User-Agent", "soul-sleep/1.0 (personal lyric tagger)").build()) }.build()

    private const val LRCRED_SEARCH = "https://lrc.red/api/v1?q="

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun find(t: Track): Found? {
        return findWord(t) ?: findLine(t)
    }

    private fun cleanTitle(raw: String): String {
        return raw
            // Remove artist prefix if title is "Juice WRLD - Lucid Dreams"
            .replace(Regex("""^[^-–—]+[-–—]\s*"""), "")
            // Remove bracketed noise: (Official Video), [Explicit], (feat. ...), etc.
            .replace(Regex("""(?i)\s*[\(\[](?:official\s*(?:video|audio|music\s*video|lyric\s*video|visualizer)|explicit|clean|audio|remaster(?:ed)?|lyrics?|bonus\s*track)[\)\]]"""), "")
            .replace(Regex("""(?i)\s*[\(\[]\s*(?:feat|ft)\.?\s+[^)\]]+[\)\]]"""), "")
            .trim()
    }

    private fun cleanArtist(raw: String): String {
        return raw.split(Regex("""(?i)[,&/]|(\s+\b(?:feat|ft)\.?\b)""")).firstOrNull()?.trim() ?: raw
    }

    private fun queryLrcRed(query: String): List<LrcRedCandidate> {
        val body = get(LRCRED_SEARCH + enc(query)) ?: return emptyList()
        return runCatching {
            val root = JSONObject(body)
            val results = root.optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { i ->
                val obj = results.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id", "")
                val isrc = obj.optString("isrc", id)
                val url = obj.optString("lyricsUrl", "")
                val timing = obj.optString("timing_type", "")
                val dur = obj.optInt("duration", 0)
                if (id.isNotBlank() || isrc.isNotBlank() || url.isNotBlank()) {
                    LrcRedCandidate(id = id, isrc = isrc, lyricsUrl = url, timingType = timing, duration = dur)
                } else null
            }
        }.getOrDefault(emptyList())
    }

    /** Searches specifically for word-by-word synchronized lyrics from lrc.red (Apple Music TTML). */
    fun findWord(t: Track): Found? {
        val cleanT = cleanTitle(t.title)
        val cleanA = cleanArtist(t.artist)

        // 1. Try embedded ISRC in file metadata if present
        val fileIsrc = Library.isrc(t.path)
        if (fileIsrc != null) {
            val isrcClean = fileIsrc.replace("-", "").uppercase()
            val ttml = get("https://lrc.red/s/$isrcClean.ttml")
            if (ttml != null) {
                val elrc = runCatching { Ttml.toElrc(ttml) }.getOrNull()
                if (elrc != null) return Found(elrc, Grain.WORD, "lrc.red")
            }
        }

        // 2. Query lrc.red with multiple query variations
        val queries = listOfNotNull(
            "$cleanT $cleanA",
            "${t.title} ${t.artist}",
            cleanT.takeIf { it.isNotBlank() && it != "$cleanT $cleanA" }
        ).distinct()

        for (q in queries) {
            val candidates = queryLrcRed(q)
            // Filter strictly for word-by-word synced items
            val wordCandidates = candidates.filter { it.timingType.equals("word", ignoreCase = true) }
            if (wordCandidates.isEmpty()) continue

            // Prioritize closest duration match if track duration is known
            val sorted = if (t.durSec > 0) {
                wordCandidates.sortedBy { Math.abs(it.duration - t.durSec) }
            } else {
                wordCandidates
            }

            // Try the top candidate(s)
            for (c in sorted.take(3)) {
                val url = if (c.lyricsUrl.isNotBlank()) {
                    c.lyricsUrl
                } else {
                    val code = (c.isrc.ifBlank { c.id }).replace("-", "").uppercase()
                    "https://lrc.red/s/$code.ttml"
                }
                val ttml = get(url) ?: continue
                val elrc = runCatching { Ttml.toElrc(ttml) }.getOrNull()
                if (elrc != null) {
                    return Found(elrc, Grain.WORD, "lrc.red")
                }
            }
        }

        return null
    }

    /** Searches for line-synced lyrics from LRCLIB. */
    fun findLine(t: Track): Found? {
        val cleanT = cleanTitle(t.title)
        val cleanA = cleanArtist(t.artist)

        // 1. Try exact /api/get with title & artist
        val q1 = "track_name=${enc(cleanT)}&artist_name=${enc(cleanA)}"
        val exactObj: JSONObject? = runCatching {
            val url = if (t.durSec > 0) {
                "https://lrclib.net/api/get?$q1&duration=${t.durSec}"
            } else {
                "https://lrclib.net/api/get?$q1"
            }
            get(url)?.let { JSONObject(it) }
        }.getOrNull()

        if (exactObj != null) {
            val lrc = exactObj.optString("syncedLyrics", "")
            if (lrc.isNotBlank()) return Found(lrc, Grain.LINE, "lrclib")
        }

        // 2. Try search by clean title & artist, or fallback to broad query
        val searchResults = runCatching {
            get("https://lrclib.net/api/search?$q1")?.let { JSONArray(it) }
        }.getOrNull() ?: runCatching {
            get("https://lrclib.net/api/search?q=${enc("$cleanT $cleanA")}")?.let { JSONArray(it) }
        }.getOrNull()

        if (searchResults != null && searchResults.length() > 0) {
            val list = (0 until searchResults.length()).mapNotNull { i ->
                runCatching { searchResults.getJSONObject(i) }.getOrNull()
            }.filter { !it.isNull("syncedLyrics") && it.optString("syncedLyrics").isNotBlank() }

            // Prefer duration within 6 seconds, otherwise fallback to the first synced match
            val best = if (t.durSec > 0) {
                list.firstOrNull { Math.abs(it.optDouble("duration", 0.0).toInt() - t.durSec) <= 6 }
                    ?: list.firstOrNull()
            } else {
                list.firstOrNull()
            }

            val lrc = best?.optString("syncedLyrics", "") ?: ""
            if (lrc.isNotBlank()) return Found(lrc, Grain.LINE, "lrclib")
        }

        return null
    }
}

/** Apple-style TTML (what lrc.red serves) -> Enhanced LRC:  [mm:ss.xx]<mm:ss.xx>word <mm:ss.xx>word */
object Ttml {
    private val para = Regex("<p\\b([^>]*)>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
    private val span = Regex("<span\\b([^>]*)>(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
    private fun attr(a: String, n: String) = Regex("""\b$n="([^"]*)"""").find(a)?.groupValues?.get(1)
    private fun secs(s: String) = s.removeSuffix("s").split(":").map { it.toDouble() }.fold(0.0) { acc, v -> acc * 60 + v }
    private fun stamp(t: Double): String { val cs = (t * 100).roundToInt(); return "%02d:%02d.%02d".format(cs / 6000, (cs / 100) % 60, cs % 100) }
    private fun clean(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").trim()

    fun toElrc(xml: String): String? {
        val sb = StringBuilder(); var words = 0
        for (m in para.findAll(xml)) {
            val begin = attr(m.groupValues[1], "begin") ?: continue
            val inner = m.groupValues[2]
            val spans = span.findAll(inner).filter { attr(it.groupValues[1], "ttm:role") == null && attr(it.groupValues[1], "begin") != null }.toList()
            if (spans.isEmpty()) continue // line-only TTML is rejected so LRCLIB gets its turn
            sb.append("[${stamp(secs(begin))}]")
            var prev = 0
            spans.forEachIndexed { i, s ->
                val gap = inner.substring(prev, s.range.first); prev = s.range.last + 1
                if (i > 0 && gap.any { it.isWhitespace() }) sb.append(' ')
                sb.append("<${stamp(secs(attr(s.groupValues[1], "begin")!!))}>${clean(s.groupValues[2])}"); words++
            }
            attr(m.groupValues[1], "end")?.let { sb.append(" <${stamp(secs(it))}>") }
            sb.append('\n')
        }
        return if (words > 0) sb.toString().trim() else null
    }
}
