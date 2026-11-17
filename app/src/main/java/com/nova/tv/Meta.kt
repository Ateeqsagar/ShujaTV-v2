package com.nova.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.TreeMap

/** Poster + details for a movie or series. Any field may be blank. */
data class Meta(
    val poster: String = "",
    val plot: String = "",
    val rating: String = "",
    val year: String = "",
    val genre: String = "",
    val cast: String = "",
    val duration: String = ""
) {
    fun merge(o: Meta?): Meta = if (o == null) this else Meta(
        poster.ifBlank { o.poster }, plot.ifBlank { o.plot }, rating.ifBlank { o.rating },
        year.ifBlank { o.year }, genre.ifBlank { o.genre }, cast.ifBlank { o.cast }, duration.ifBlank { o.duration }
    )

    val complete: Boolean get() = poster.isNotBlank() && plot.isNotBlank()
}

private fun JSONObject.v(k: String): String {
    val x = optString(k)
    return if (x == "null") "" else x
}

private fun JSONArray?.joinTop(max: Int): String {
    if (this == null) return ""
    val out = ArrayList<String>()
    for (i in 0 until minOf(length(), max)) {
        val s = optString(i)
        if (s.isNotBlank() && s != "null") out.add(s)
    }
    return out.joinToString(", ")
}

/**
 * Fills in missing posters and details from several free public sources, in this order:
 * Cinemeta (IMDb data), TVMaze (series), iTunes (movies), Wikipedia. Every call is best effort.
 */
object MetaLookup {
    private val RX_YEAR = Regex("""\b(19|20)\d{2}\b""")
    private val RX_BRACKETS = Regex("""\[[^\]]*]|\([^)]*\)|\{[^}]*}""")
    private val RX_PREFIX1 = Regex("""^\s*\|[^|]*\|\s*""")
    private val RX_PREFIX2 = Regex("""^\s*[A-Za-z]{2,3}\s*[-:|]\s+""")
    private val RX_TAGS = Regex(
        """\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|hevc|x264|x265|h264|h265|multi|multisub|dubbed|vod|web-?dl|bluray|brrip)\b""",
        RegexOption.IGNORE_CASE
    )
    private val RX_SEASON = Regex("""\bS\d{1,2}\s*E\d{1,3}\b|\bseason\s*\d+\b""", RegexOption.IGNORE_CASE)
    private val RX_SPACES = Regex("""\s+""")
    private val RX_HTML = Regex("<[^>]*>")

    /** Turns "EN - The Matrix (1999) 4K [MULTI]" into ("The Matrix", "1999"). */
    fun clean(raw: String): Pair<String, String> {
        var year = ""
        for (b in RX_BRACKETS.findAll(raw)) {
            val y = RX_YEAR.find(b.value)
            if (y != null) year = y.value
        }
        var t = RX_BRACKETS.replace(raw, " ")
        if (year.isEmpty()) {
            val y = RX_YEAR.findAll(t).lastOrNull()
            if (y != null && t.trim().length > 6) {
                year = y.value
                t = t.replaceRange(y.range, " ")
            }
        }
        t = RX_PREFIX1.replace(t, "")
        t = RX_PREFIX2.replace(t, "")
        t = RX_SEASON.replace(t, " ")
        t = RX_TAGS.replace(t, " ")
        t = t.replace('_', ' ').replace('.', ' ')
        t = RX_SPACES.replace(t, " ").trim(' ', '-', ':', '|', '/')
        return t to year
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun sameTitle(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val lo = minOf(x.length, y.length)
        val hi = maxOf(x.length, y.length)
        return lo >= 5 && (x.contains(y) || y.contains(x)) && lo * 10 >= hi * 6
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun http(url: String, ua: String): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "application/json")
            try {
                if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private inline fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        null
    }

    private fun cinemeta(title: String, year: String, series: Boolean, ua: String): Meta? {
        val kind = if (series) "series" else "movie"
        val q = enc(title).replace("+", "%20")
        val raw = http("https://v3-cinemeta.strem.io/catalog/$kind/top/search=$q.json", ua) ?: return null
        val metas = JSONObject(raw).optJSONArray("metas") ?: return null
        var best: JSONObject? = null
        for (i in 0 until minOf(metas.length(), 8)) {
            val m = metas.optJSONObject(i) ?: continue
            if (!sameTitle(title, m.v("name"))) continue
            if (year.isEmpty() || m.v("releaseInfo").startsWith(year)) {
                best = m
                break
            }
            if (best == null) best = m
        }
        val b = best ?: return null
        return Meta(
            poster = b.v("poster"), plot = b.v("description"), rating = b.v("imdbRating"),
            year = b.v("releaseInfo").take(4), genre = b.optJSONArray("genres").joinTop(3),
            cast = b.optJSONArray("cast").joinTop(6), duration = b.v("runtime")
        )
    }

    private fun tvmaze(title: String, ua: String): Meta? {
        val raw = http("https://api.tvmaze.com/search/shows?q=" + enc(title), ua) ?: return null
        val arr = JSONArray(raw)
        for (i in 0 until minOf(arr.length(), 6)) {
            val sh = arr.optJSONObject(i)?.optJSONObject("show") ?: continue
            if (!sameTitle(title, sh.v("name"))) continue
            val img = sh.optJSONObject("image")
            val poster = if (img != null) img.v("original").ifBlank { img.v("medium") } else ""
            val rating = sh.optJSONObject("rating")?.v("average") ?: ""
            return Meta(
                poster = poster, plot = RX_HTML.replace(sh.v("summary"), "").trim(), rating = rating,
                year = sh.v("premiered").take(4), genre = sh.optJSONArray("genres").joinTop(3)
            )
        }
        return null
    }

    private fun itunes(title: String, year: String, ua: String): Meta? {
        val raw = http("https://itunes.apple.com/search?media=movie&entity=movie&limit=8&term=" + enc(title), ua) ?: return null
        val arr = JSONObject(raw).optJSONArray("results") ?: return null
        var best: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!sameTitle(title, o.v("trackName"))) continue
            if (year.isEmpty() || o.v("releaseDate").startsWith(year)) {
                best = o
                break
            }
            if (best == null) best = o
        }
        val b = best ?: return null
        return Meta(
            poster = b.v("artworkUrl100").replace("100x100bb", "600x600bb"),
            plot = b.v("longDescription").ifBlank { b.v("shortDescription") },
            year = b.v("releaseDate").take(4), genre = b.v("primaryGenreName")
        )
    }

    private fun wiki(title: String, series: Boolean, ua: String): Meta? {
        val q = enc(title + if (series) " TV series" else " film")
        val raw = http(
            "https://en.wikipedia.org/w/api.php?action=query&format=json&prop=pageimages%7Cextracts&exintro=1&explaintext=1" +
                "&exchars=500&piprop=thumbnail&pithumbsize=500&generator=search&gsrlimit=3&gsrsearch=" + q, ua
        ) ?: return null
        val pages = JSONObject(raw).optJSONObject("query")?.optJSONObject("pages") ?: return null
        val ks = pages.keys()
        while (ks.hasNext()) {
            val p = pages.optJSONObject(ks.next()) ?: continue
            if (!norm(p.v("title")).contains(norm(title))) continue
            val th = p.optJSONObject("thumbnail")?.v("source") ?: ""
            val ex = p.v("extract")
            if (th.isBlank() && ex.isBlank()) continue
            return Meta(poster = th, plot = ex)
        }
        return null
    }

    suspend fun find(name: String, series: Boolean, ua: String): Meta? = withContext(Dispatchers.IO) {
        val (title, year) = clean(name)
        if (title.length < 2) return@withContext null
        var m = Meta()
        m = m.merge(safe { cinemeta(title, year, series, ua) })
        if (!m.complete) {
            m = if (series) m.merge(safe { tvmaze(title, ua) }) else m.merge(safe { itunes(title, year, ua) })
        }
        if (!m.complete) m = m.merge(safe { wiki(title, series, ua) })
        if (m.poster.isBlank() && m.plot.isBlank()) null else m
    }
}

class M3uData(
    val items: Map<Int, List<Item>>,
    val cats: Map<Int, List<Category>>,
    val eps: Map<String, Map<Int, List<Item>>>
)

/** Streaming M3U / M3U8 playlist reader. Sorts entries into Live TV, Movies and Series. */
object M3u {
    private val RX_SE = Regex("""^(.*?)[\s._-]*[Ss](\d{1,2})\s*[Ee](\d{1,3})(.*)$""")
    private val VIDEO_EXT = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".m4v")

    suspend fun load(url: String, ua: String): M3uData = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", ua)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("Server returned HTTP $code")
            c.inputStream.bufferedReader(Charsets.UTF_8).use { parse(it, 0, "") }
        } finally {
            c.disconnect()
        }
    }

    private fun attr(l: String, key: String): String {
        val i = l.indexOf("$key=\"")
        if (i < 0) return ""
        val s = i + key.length + 2
        val e = l.indexOf('"', s)
        return if (e < 0) "" else l.substring(s, e).trim()
    }

    /** Parses playlist text. force: 0 auto, 1 Live TV, 2 Movies, 3 Series. tag prefixes every category (e.g. "PK · News"). */
    fun parseText(text: String, force: Int, tag: String): M3uData = parse(text.reader().buffered(), force, tag)

    private fun parse(r: BufferedReader, force: Int, tag: String): M3uData {
        val lists = arrayOf(ArrayList<Item>(), ArrayList<Item>(), ArrayList<Item>())
        val cats = arrayOf(LinkedHashSet<String>(), LinkedHashSet<String>(), LinkedHashSet<String>())
        val seriesMap = HashMap<String, Item>()
        val eps = HashMap<String, TreeMap<Int, ArrayList<Item>>>()
        var name = ""
        var logo = ""
        var group = ""
        var line = r.readLine()
        while (line != null) {
            val l = line.trim()
            if (l.startsWith("#EXTINF")) {
                logo = attr(l, "tvg-logo")
                group = attr(l, "group-title")
                val q = l.lastIndexOf('"')
                val comma = l.indexOf(',', if (q >= 0) q else 0)
                name = if (comma >= 0) l.substring(comma + 1).trim() else ""
                if (name.isBlank()) name = attr(l, "tvg-name")
            } else if (l.isNotEmpty() && !l.startsWith("#")) {
                addEntry(name, logo, group, l, lists, cats, seriesMap, eps, force, tag)
                name = ""
                logo = ""
                group = ""
            }
            line = r.readLine()
        }
        val catMap = HashMap<Int, List<Category>>()
        for (t in 0..2) catMap[t] = cats[t].map { Category(it, it) }
        val epOut = HashMap<String, Map<Int, List<Item>>>()
        for ((k, v) in eps) epOut[k] = v
        val itemMap = HashMap<Int, List<Item>>()
        itemMap[T_LIVE] = lists[0]
        itemMap[T_MOVIE] = lists[1]
        itemMap[T_SERIES] = lists[2]
        return M3uData(itemMap, catMap, epOut)
    }

    private fun addEntry(
        name: String, logo: String, group: String, url: String,
        lists: Array<ArrayList<Item>>, cats: Array<LinkedHashSet<String>>,
        seriesMap: HashMap<String, Item>, eps: HashMap<String, TreeMap<Int, ArrayList<Item>>>,
        force: Int, tag: String
    ) {
        val u = url.lowercase()
        val g = group.lowercase()
        val isFile = VIDEO_EXT.any { u.substringBefore('?').endsWith(it) }
        val se = RX_SE.matchEntire(name)
        val type = when {
            force == 1 -> T_LIVE
            force == 2 -> T_MOVIE
            force == 3 -> T_SERIES
            u.contains("/series/") || g.contains("series") || g.contains("season") -> T_SERIES
            se != null && isFile -> T_SERIES
            u.contains("/movie/") || g.contains("movie") || g.contains("film") || g.contains("vod") || g.contains("cinema") || isFile -> T_MOVIE
            else -> T_LIVE
        }
        val baseCat = group.ifBlank { if (tag.isNotBlank()) "General" else "Uncategorized" }
        val cat = if (tag.isBlank() || baseCat.startsWith(tag)) baseCat else "$tag · $baseCat"
        val icon: String? = if (logo.isBlank()) null else logo
        val uid = "m" + Integer.toHexString(url.hashCode())
        if (type != T_SERIES) {
            lists[type].add(Item(uid, name.ifBlank { uid }, icon, cat, type, "", 0L, url))
            cats[type].add(cat)
            return
        }
        var showRaw = name
        var season = 1
        var epn = 0
        var epTitle = ""
        if (se != null) {
            showRaw = se.groupValues[1].trim(' ', '-', '_', '.')
            season = se.groupValues[2].toIntOrNull() ?: 1
            epn = se.groupValues[3].toIntOrNull() ?: 1
            epTitle = se.groupValues[4].trim(' ', '-', '_', '.', ':')
        }
        val show = showRaw.ifBlank { name.ifBlank { uid } }
        val sid = "m3u:" + show.lowercase()
        if (!seriesMap.containsKey(sid)) {
            val s = Item(sid, show, icon, cat, T_SERIES)
            seriesMap[sid] = s
            lists[T_SERIES].add(s)
            cats[T_SERIES].add(cat)
        }
        val seasons = eps.getOrPut(sid) { TreeMap<Int, ArrayList<Item>>() }
        val seasonList = seasons.getOrPut(season) { ArrayList<Item>() }
        val n = if (epn > 0) epn else seasonList.size + 1
        seasonList.add(Item(uid, "E$n  ${epTitle.ifBlank { show }}", icon, season.toString(), T_EP, "", 0L, url))
    }
}
