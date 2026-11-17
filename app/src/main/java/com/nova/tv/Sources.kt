package com.nova.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * One place the app loads channels or films from.
 * kind: 0 = auto-detect, 1 = Live TV, 2 = Movies, 3 = Series.
 * url : an M3U/M3U8 link, a GitHub link, a "sources.json" manifest, or "archive:<search>" (Internet Archive films).
 */
data class Src(
    val name: String,
    val url: String,
    val kind: Int = 0,
    val tag: String = "",
    val on: Boolean = true,
    val builtin: Boolean = false
)

object Sources {
    private const val IPTV = "https://iptv-org.github.io/iptv/"

    /**
     * Free, publicly available sources only: the community iptv-org lists (free-to-air / official public streams),
     * Free-TV, Pluto TV's free channels, and public-domain films on the Internet Archive.
     * A source that is offline or has moved is skipped on its own; it never stops the others from loading.
     * Order matters: Pakistan first, then India, then the rest.
     */
    val BUILTIN: List<Src> = listOf(
        // ---- Pakistan ----
        Src("Pakistan - all channels", IPTV + "countries/pk.m3u", 1, "PK", builtin = true),
        Src("Urdu language channels", IPTV + "languages/urd.m3u", 1, "Urdu", builtin = true),
        Src("Punjabi language channels", IPTV + "languages/pan.m3u", 1, "Punjabi", builtin = true),
        // ---- India ----
        Src("India - all channels", IPTV + "countries/in.m3u", 1, "IN", builtin = true),
        Src("Hindi language channels", IPTV + "languages/hin.m3u", 1, "Hindi", builtin = true),
        Src("Tamil language channels", IPTV + "languages/tam.m3u", 1, "Tamil", builtin = true),
        Src("Telugu language channels", IPTV + "languages/tel.m3u", 1, "Telugu", builtin = true),
        Src("Bengali language channels", IPTV + "languages/ben.m3u", 1, "Bengali", builtin = true),
        // ---- Movies and series (free) ----
        Src("Classic Hindi / Bollywood films (Internet Archive)", "archive:subject:(bollywood OR \"hindi film\" OR \"hindi movie\") AND mediatype:movies", 2, "Hindi Films", builtin = true),
        Src("Urdu / Pakistani films (Internet Archive)", "archive:subject:(urdu OR pakistani OR lollywood) AND mediatype:movies", 2, "Urdu Films", builtin = true),
        Src("Public-domain feature films (Internet Archive)", "archive:collection:feature_films AND mediatype:movies", 2, "Classics", builtin = true),
        Src("Movie channels", IPTV + "categories/movies.m3u", 1, "Movies", builtin = true),
        Src("Series channels", IPTV + "categories/series.m3u", 1, "Series", builtin = true),
        // ---- Everything else ----
        Src("Entertainment", IPTV + "categories/entertainment.m3u", 1, "Entertainment", builtin = true),
        Src("News", IPTV + "categories/news.m3u", 1, "News", builtin = true),
        Src("Sports", IPTV + "categories/sports.m3u", 1, "Sports", builtin = true),
        Src("Kids", IPTV + "categories/kids.m3u", 1, "Kids", builtin = true),
        Src("Music", IPTV + "categories/music.m3u", 1, "Music", builtin = true),
        Src("Comedy", IPTV + "categories/comedy.m3u", 1, "Comedy", builtin = true),
        Src("Documentary", IPTV + "categories/documentary.m3u", 1, "Docs", builtin = true),
        Src("Religious", IPTV + "categories/religious.m3u", 1, "Religious", builtin = true),
        Src("Free-TV worldwide", "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8", 1, "Free-TV", builtin = true),
        Src("Pluto TV (free)", "https://i.mjh.nz/PlutoTV/all.m3u8", 1, "Pluto", builtin = true)
    )

    // ---------- storage: custom repos + switched-off built-ins ----------

    private fun prefs(c: Context) = c.getSharedPreferences("nova_src", 0)

    fun custom(c: Context): List<Src> {
        val out = ArrayList<Src>()
        try {
            val a = JSONArray(prefs(c).getString("custom", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Src(o.optString("name"), o.optString("url"), o.optInt("kind"), o.optString("tag"), o.optBoolean("on", true)))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun saveCustom(c: Context, list: List<Src>) {
        val a = JSONArray()
        for (s in list) a.put(JSONObject().put("name", s.name).put("url", s.url).put("kind", s.kind).put("tag", s.tag).put("on", s.on))
        prefs(c).edit().putString("custom", a.toString()).apply()
    }

    private fun offSet(c: Context): Set<String> = prefs(c).getStringSet("off", emptySet())?.toSet() ?: emptySet()

    /** Built-in sources (minus those switched off) followed by the user's own repos. */
    fun all(c: Context): List<Src> {
        val off = offSet(c)
        return BUILTIN.map { it.copy(on = it.url !in off) } + custom(c)
    }

    fun addCustom(c: Context, name: String, url: String, kind: Int) {
        val u = normalize(url)
        val list = custom(c).filter { it.url != u }.toMutableList()
        list.add(Src(name.ifBlank { u.substringAfter("://").substringBefore('/') }, u, kind, "My"))
        saveCustom(c, list)
    }

    fun removeCustom(c: Context, url: String) = saveCustom(c, custom(c).filter { it.url != url })

    fun toggle(c: Context, s: Src) {
        if (s.builtin) {
            val off = offSet(c).toMutableSet()
            if (!off.remove(s.url)) off.add(s.url)
            prefs(c).edit().putStringSet("off", HashSet(off)).apply()
        } else {
            saveCustom(c, custom(c).map { if (it.url == s.url) it.copy(on = !it.on) else it })
        }
    }

    fun resetBuiltins(c: Context) { prefs(c).edit().remove("off").apply() }

    /** Turns GitHub page links into raw links. A bare repo link points at its sources.json. */
    fun normalize(raw: String): String {
        val u = raw.trim()
        if (u.startsWith("archive:")) return u
        val m = Regex("""^https?://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$""").matchEntire(u)
        if (m != null) return "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}/${m.groupValues[4]}"
        val r = Regex("""^https?://github\.com/([^/]+)/([^/#?]+?)(\.git)?/?$""").matchEntire(u)
        if (r != null) return "https://raw.githubusercontent.com/${r.groupValues[1]}/${r.groupValues[2]}/main/sources.json"
        return if (u.startsWith("http")) u else "http://$u"
    }

    // ---------- loading ----------

    private fun merge(parts: List<M3uData>): M3uData {
        val items = HashMap<Int, ArrayList<Item>>()
        val cats = HashMap<Int, LinkedHashSet<Category>>()
        val seen = HashSet<String>()
        val eps = HashMap<String, Map<Int, List<Item>>>()
        for (t in 0..2) { items[t] = ArrayList(); cats[t] = LinkedHashSet() }
        for (d in parts) {
            for (t in 0..2) {
                for (x in d.items[t] ?: emptyList()) {
                    if (seen.add("$t|${x.id}")) items[t]!!.add(x)
                }
                cats[t]!!.addAll(d.cats[t] ?: emptyList())
            }
            for ((k, v) in d.eps) if (!eps.containsKey(k)) eps[k] = v
        }
        return M3uData(items.mapValues { it.value }, cats.mapValues { it.value.toList() }, eps)
    }

    /** Loads every switched-on source in parallel. Failing sources are skipped; throws only if nothing at all loaded. */
    suspend fun loadAll(list: List<Src>, ua: String): M3uData = coroutineScope {
        val gate = Semaphore(6)
        val jobs = list.filter { it.on }.map { s ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    try { loadOne(s, ua, 0) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { null }
                }
            }
        }
        val parts = jobs.awaitAll().filterNotNull()
        val data = merge(parts)
        if (data.items.values.sumOf { it.size } == 0) throw IOException("No channels could be loaded. Check the internet connection and try again.")
        data
    }

    private suspend fun loadOne(s: Src, ua: String, depth: Int): M3uData? {
        if (s.url.startsWith("archive:")) return ArchiveApi.search(s.url.removePrefix("archive:"), s.tag.ifBlank { s.name }, ua)
        val text = fetchText(s.url, ua)
        val head = text.trimStart().take(1)
        if (head == "[" || head == "{") {
            if (depth > 0) return null
            val subs = parseManifest(text)
            val parts = subs.mapNotNull { sub -> try { loadOne(sub, ua, depth + 1) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { null } }
            return if (parts.isEmpty()) null else merge(parts)
        }
        return M3u.parseText(text, s.kind, s.tag)
    }

    /** sources.json: [{"name":"..","url":"..","type":"live|movie|series|auto","tag":".."}] or {"sources":[...]} */
    private fun parseManifest(text: String): List<Src> {
        val arr = try {
            val t = text.trimStart()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("sources") ?: JSONArray()
        } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Src>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            val kind = when (o.optString("type").lowercase()) { "live", "tv" -> 1; "movie", "movies", "film" -> 2; "series", "show", "shows" -> 3; else -> 0 }
            out.add(Src(o.optString("name").ifBlank { url }, normalize(url), kind, o.optString("tag")))
        }
        return out
    }

    private fun fetchText(url: String, ua: String): String {
        val tryUrls = if (url.endsWith("/main/sources.json")) listOf(url, url.replace("/main/sources.json", "/master/sources.json")) else listOf(url)
        var last: Throwable? = null
        for (u in tryUrls) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 40_000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", ua)
                try {
                    if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                    return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally { c.disconnect() }
            } catch (e: IOException) { last = e }
        }
        throw last ?: IOException("Could not load $url")
    }
}

/** Free films from the Internet Archive (search API + file lookup when you press play). */
object ArchiveApi {
    suspend fun search(query: String, tag: String, ua: String): M3uData = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://archive.org/advancedsearch.php?q=$q&fl%5B%5D=identifier&fl%5B%5D=title&sort%5B%5D=downloads+desc&rows=200&page=1&output=json"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", ua)
        val body = try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
        val docs = JSONObject(body).optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        val list = ArrayList<Item>()
        for (i in 0 until docs.length()) {
            val o = docs.getJSONObject(i)
            val id = o.optString("identifier")
            if (id.isBlank()) continue
            val title = o.optString("title").ifBlank { id }
            list.add(Item("ia:$id", title, "https://archive.org/services/img/$id", tag, T_MOVIE, "", 0L, "archive://$id"))
        }
        M3uData(
            mapOf(T_LIVE to emptyList<Item>(), T_MOVIE to list, T_SERIES to emptyList<Item>()),
            mapOf(T_LIVE to emptyList<Category>(), T_MOVIE to listOf(Category(tag, tag)), T_SERIES to emptyList<Category>()),
            emptyMap()
        )
    }

    /** Finds the best playable video file of an Archive item. */
    suspend fun resolve(id: String): String? = withContext(Dispatchers.IO) {
        val c = URL("https://archive.org/metadata/${URLEncoder.encode(id, "UTF-8")}").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        val body = try {
            if (c.responseCode !in 200..299) return@withContext null
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
        val files = JSONObject(body).optJSONArray("files") ?: return@withContext null
        var best: String? = null
        var bestScore = -1
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val name = f.optString("name")
            val fmt = f.optString("format").lowercase()
            val low = name.lowercase()
            if (!(low.endsWith(".mp4") || low.endsWith(".m4v") || low.endsWith(".mkv"))) continue
            val score = when {
                fmt == "h.264" -> 3
                fmt.contains("mpeg4") && !fmt.contains("512") -> 2
                else -> 1
            }
            if (score > bestScore) { bestScore = score; best = name }
        }
        best?.let { "https://archive.org/download/$id/" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }
}
