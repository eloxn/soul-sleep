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

/** Priority: lrc.red word-sync -> LRCLIB line-sync. Plain (unsynced) text is never written. */
object Sources {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { it.proceed(it.request().newBuilder().header("User-Agent", "soul-sleep/1.0 (personal lyric tagger)").build()) }.build()

    // UNVERIFIED: lrc.red's search route/params. /s/{ISRC}.ttml is confirmed; tweak this one line if search 404s.
    private const val LRCRED_SEARCH = "https://lrc.red/api/v1?q="

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun find(t: Track): Found? = lrcRed(t) ?: lrclib(t)

    private fun lrcRed(t: Track): Found? {
        val isrc = Library.isrc(t.path) ?: get(LRCRED_SEARCH + enc("${t.title} ${t.artist}"))
            ?.let { Regex(""""isrc"\s*:\s*"([A-Za-z0-9-]{12,14})"""").find(it)?.groupValues?.get(1) } ?: return null
        val ttml = get("https://lrc.red/s/${isrc.replace("-", "").uppercase()}.ttml") ?: return null
        return Ttml.toElrc(ttml)?.let { Found(it, Grain.WORD, "lrc.red") }
    }

    private fun lrclib(t: Track): Found? {
        val q = "track_name=${enc(t.title)}&artist_name=${enc(t.artist)}"
        val o: JSONObject = get("https://lrclib.net/api/get?$q&album_name=${enc(t.album)}&duration=${t.durSec}")?.let { JSONObject(it) }
            ?: get("https://lrclib.net/api/search?$q")?.let { s ->
                val a = JSONArray(s)
                (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { !it.isNull("syncedLyrics") && kotlin.math.abs(it.optInt("duration") - t.durSec) <= 3 }
            } ?: return null
        val lrc = o.optString("syncedLyrics", "")
        return if (lrc.isNotBlank()) Found(lrc, Grain.LINE, "lrclib") else null
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
