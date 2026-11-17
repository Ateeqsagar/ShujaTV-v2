package com.nova.tv

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

const val T_LIVE = 0
const val T_MOVIE = 1
const val T_SERIES = 2
const val T_EP = 3

const val APP_VERSION = "2.0"

val SEEK_STEPS = listOf(5, 10, 15, 20, 30, 60, 120)
val AUDIO_CODES = listOf("", "en", "ar", "hi", "ur", "fr", "es", "de", "tr")
val AUDIO_NAMES = listOf("Auto", "English", "Arabic", "Hindi", "Urdu", "French", "Spanish", "German", "Turkish")

/** mode 0 = Xtream Codes, mode 1 = single M3U playlist (host holds the URL), mode 2 = built-in free sources + your own repos (no login). */
data class Account(val host: String, val user: String, val pass: String, val mode: Int = 0)
data class AcctInfo(val status: String = "", val exp: String = "", val maxCons: String = "", val activeCons: String = "")
data class Category(val id: String, val name: String)

/** Light-weight on purpose: tens of thousands of these live in memory. URLs are built on demand. */
data class Item(
    val id: String,
    val name: String,
    val icon: String?,
    val cat: String,
    val type: Int,
    val ext: String = "",
    val added: Long = 0L,
    val url: String = "",
    val lc: String = name.lowercase()
) {
    val live: Boolean get() = type == T_LIVE
}

class Lib(
    val rows: List<Pair<Category, List<Item>>>,
    val all: List<Item>,
    val adult: Set<String>,
    val names: Map<String, String>,
    val recent: List<Item>
)

class SearchRes(val live: List<Item>, val movies: List<Item>, val series: List<Item>)
class SeriesDetail(val eps: Map<Int, List<Item>>, val meta: Meta)

/** One programme. start/end are epoch seconds. */
data class Prog(val title: String, val start: Long, val end: Long, val desc: String = "")
data class Epg(val list: List<Prog>, val at: Long) {
    fun now(t: Long): Prog? = list.firstOrNull { t >= it.start * 1000 && t < it.end * 1000 }
    fun next(t: Long): Prog? = list.firstOrNull { it.start * 1000 > t }
}

/** One "continue watching" entry. item = movie or episode; series is set for episodes. */
class Hist(val item: Item, val series: Item?, val at: Long)

class ResumeReq(val list: List<Item>, val index: Int, val posMs: Long)

class PinReq(val title: String, val create: Boolean, val onOk: () -> Unit)
class MenuTarget(val item: Item, val catId: String, val catName: String)

/** A group (category) picked for the pin menu. */
class GroupTarget(val type: Int, val catId: String, val name: String)

data class Cfg(
    val startTab: Int = 0,
    val liveFormat: Int = 0,
    val quality: Int = 0,
    val buffer: Int = 1,
    val aspect: Int = 0,
    val audio: Int = 0,
    val subs: Boolean = false,
    val resume: Boolean = true,
    val autoNext: Boolean = true,
    val ua: Int = 0,
    val sort: Int = 0,
    val hero: Boolean = false,
    val epg: Boolean = true,
    val anim: Boolean = true,
    val hideAdult: Boolean = false,
    val layout: Int = 0,
    val cardSize: Int = 1,
    val titles: Boolean = true,
    val contRow: Boolean = true,
    val recentRow: Boolean = true,
    val seekF: Int = 1,
    val seekB: Int = 1,
    val meta: Boolean = true,
    // 0 = off, 1 = on (skipped on accounts with only 1 connection), 2 = always on
    val previewMode: Int = 1
) {
    val preview: Boolean get() = previewMode != 0
}

fun loadCfg(sp: SharedPreferences): Cfg {
    val d = Cfg()
    return Cfg(
        startTab = sp.getInt("startTab", d.startTab).coerceIn(0, 2),
        liveFormat = sp.getInt("liveFormat", d.liveFormat).coerceIn(0, 1),
        quality = sp.getInt("quality", d.quality).coerceIn(0, 2),
        buffer = sp.getInt("buffer", d.buffer).coerceIn(0, 2),
        aspect = sp.getInt("aspect", d.aspect).coerceIn(0, 2),
        audio = sp.getInt("audio", d.audio).coerceIn(0, AUDIO_CODES.lastIndex),
        subs = sp.getBoolean("subs", d.subs),
        resume = sp.getBoolean("resume", d.resume),
        autoNext = sp.getBoolean("autoNext", d.autoNext),
        ua = sp.getInt("ua", d.ua).coerceIn(0, 3),
        sort = sp.getInt("sort", d.sort).coerceIn(0, 1),
        hero = sp.getBoolean("hero", d.hero),
        epg = sp.getBoolean("epg", d.epg),
        anim = sp.getBoolean("anim", d.anim),
        hideAdult = sp.getBoolean("hideAdult", d.hideAdult),
        layout = sp.getInt("layout", d.layout).coerceIn(0, 1),
        cardSize = sp.getInt("cardSize", d.cardSize).coerceIn(0, 2),
        titles = sp.getBoolean("titles", d.titles),
        contRow = sp.getBoolean("contRow", d.contRow),
        recentRow = sp.getBoolean("recentRow", d.recentRow),
        seekF = sp.getInt("seekF", d.seekF).coerceIn(0, SEEK_STEPS.lastIndex),
        seekB = sp.getInt("seekB", d.seekB).coerceIn(0, SEEK_STEPS.lastIndex),
        meta = sp.getBoolean("meta", d.meta),
        previewMode = sp.getInt("pvMode", if (sp.getBoolean("preview", true)) 1 else 0).coerceIn(0, 2)
    )
}

fun saveCfg(sp: SharedPreferences, c: Cfg) {
    sp.edit()
        .putInt("startTab", c.startTab).putInt("liveFormat", c.liveFormat).putInt("quality", c.quality)
        .putInt("buffer", c.buffer).putInt("aspect", c.aspect).putInt("audio", c.audio)
        .putBoolean("subs", c.subs).putBoolean("resume", c.resume).putBoolean("autoNext", c.autoNext)
        .putInt("ua", c.ua).putInt("sort", c.sort).putBoolean("hero", c.hero)
        .putBoolean("epg", c.epg).putBoolean("anim", c.anim).putBoolean("hideAdult", c.hideAdult)
        .putInt("layout", c.layout).putInt("cardSize", c.cardSize).putBoolean("titles", c.titles)
        .putBoolean("contRow", c.contRow).putBoolean("recentRow", c.recentRow)
        .putInt("seekF", c.seekF).putInt("seekB", c.seekB).putBoolean("meta", c.meta).putInt("pvMode", c.previewMode).putBoolean("preview", c.previewMode != 0)
        .apply()
}

private fun itemJson(i: Item): JSONObject =
    JSONObject().put("id", i.id).put("n", i.name).put("i", i.icon ?: "").put("c", i.cat).put("t", i.type).put("e", i.ext)

private fun itemFrom(o: JSONObject): Item =
    Item(o.getString("id"), o.optString("n"), o.optString("i").ifBlank { null }, o.optString("c"), o.optInt("t"), o.optString("e"))

fun loadHist(sp: SharedPreferences): List<Hist> {
    val raw = sp.getString("j", null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        val out = ArrayList<Hist>()
        for (k in 0 until arr.length()) {
            val o = arr.getJSONObject(k)
            val se = o.optJSONObject("se")
            out.add(Hist(itemFrom(o.getJSONObject("it")), if (se != null) itemFrom(se) else null, o.optLong("at")))
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}

fun saveHist(sp: SharedPreferences, list: List<Hist>) {
    val arr = JSONArray()
    for (h in list) {
        val o = JSONObject().put("it", itemJson(h.item)).put("at", h.at)
        val se = h.series
        if (se != null) o.put("se", itemJson(se))
        arr.put(o)
    }
    sp.edit().putString("j", arr.toString()).apply()
}

private fun JSONObject.s(k: String): String {
    val v = optString(k)
    return if (v == "null") "" else v
}

private fun JsonReader.str(): String = when (peek()) {
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    else -> {
        skipValue()
        ""
    }
}

private fun isAdult(name: String): Boolean {
    val n = name.lowercase()
    return n.contains("adult") || n.contains("xxx") || n.contains("porn") || n.contains("18+") || n.contains("erotic")
}

private fun parseItems(r: JsonReader, type: Int, idKey: String, iconKey: String): List<Item> {
    if (r.peek() != JsonToken.BEGIN_ARRAY) {
        r.skipValue()
        return emptyList()
    }
    val out = ArrayList<Item>(4096)
    r.beginArray()
    while (r.hasNext()) {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue()
            continue
        }
        var id = ""
        var name = ""
        var icon = ""
        var cat = ""
        var ext = ""
        var added = 0L
        r.beginObject()
        while (r.hasNext()) {
            val key = r.nextName()
            when (key) {
                idKey -> id = r.str()
                "name" -> name = r.str()
                iconKey -> icon = r.str()
                "category_id" -> cat = r.str()
                "container_extension" -> ext = r.str()
                "added", "last_modified" -> {
                    val v = r.str().toLongOrNull() ?: 0L
                    if (v > added) added = v
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (id.isNotEmpty()) {
            val ic = if (icon.isNotBlank() && icon != "null") icon else null
            out.add(Item(id, name.ifBlank { id }, ic, cat, type, ext, added))
        }
    }
    r.endArray()
    return out
}

class Xtream(private val a: Account, private val ua: String) {
    val base = a.host.trim().let { if (it.startsWith("http")) it else "http://$it" }.trimEnd('/')
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun open(q: String): HttpURLConnection {
        val c = URL("$base/player_api.php?username=${enc(a.user)}&password=${enc(a.pass)}$q")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 45_000
        c.setRequestProperty("User-Agent", ua)
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw IOException("Server returned HTTP $code")
        }
        return c
    }

    private suspend fun text(q: String): String = withContext(Dispatchers.IO) {
        val c = open(q)
        try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Streams the JSON straight off the socket, so huge channel lists never sit in memory as one string or tree. */
    private suspend fun <T> read(q: String, parse: (JsonReader) -> T): T = withContext(Dispatchers.IO) {
        val c = open(q)
        try {
            JsonReader(InputStreamReader(c.inputStream, Charsets.UTF_8).buffered()).use { r ->
                r.isLenient = true
                parse(r)
            }
        } finally {
            c.disconnect()
        }
    }

    fun url(item: Item, ts: Boolean): String = when (item.type) {
        T_LIVE -> "$base/live/${a.user}/${a.pass}/${item.id}.${if (ts) "ts" else "m3u8"}"
        T_MOVIE -> "$base/movie/${a.user}/${a.pass}/${item.id}.${item.ext.ifBlank { "mp4" }}"
        else -> "$base/series/${a.user}/${a.pass}/${item.id}.${item.ext.ifBlank { "mp4" }}"
    }

    suspend fun login(): AcctInfo {
        val ui = JSONObject(text("")).optJSONObject("user_info") ?: error("Invalid server response")
        if (ui.optInt("auth") != 1) error("Wrong username or password")
        val status = ui.s("status")
        if (status.equals("Expired", true)) error("Subscription expired")
        val exp = ui.s("exp_date").toLongOrNull()
            ?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it * 1000)) } ?: "Unlimited"
        return AcctInfo(status.ifBlank { "Active" }, exp, ui.s("max_connections"), ui.s("active_cons"))
    }

    suspend fun categories(type: Int): List<Category> {
        val kind = when (type) {
            T_LIVE -> "live"
            T_MOVIE -> "vod"
            else -> "series"
        }
        return read("&action=get_${kind}_categories") { r ->
            val out = ArrayList<Category>()
            if (r.peek() != JsonToken.BEGIN_ARRAY) {
                r.skipValue()
            } else {
                r.beginArray()
                while (r.hasNext()) {
                    if (r.peek() != JsonToken.BEGIN_OBJECT) {
                        r.skipValue()
                        continue
                    }
                    var id = ""
                    var name = ""
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "category_id" -> id = r.str()
                            "category_name" -> name = r.str()
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (id.isNotEmpty()) out.add(Category(id, name.ifBlank { "Category $id" }))
                }
                r.endArray()
            }
            out
        }
    }

    suspend fun items(type: Int): List<Item> = when (type) {
        T_LIVE -> read("&action=get_live_streams") { parseItems(it, T_LIVE, "stream_id", "stream_icon") }
        T_MOVIE -> read("&action=get_vod_streams") { parseItems(it, T_MOVIE, "stream_id", "stream_icon") }
        else -> read("&action=get_series") { parseItems(it, T_SERIES, "series_id", "cover") }
    }

    suspend fun seriesInfo(id: String): SeriesDetail {
        val raw = text("&action=get_series_info&series_id=$id")
        return withContext(Dispatchers.Default) {
            val root = JSONObject(raw)
            val inf = root.optJSONObject("info")
            val meta = if (inf == null) Meta() else Meta(
                poster = inf.s("cover"), plot = inf.s("plot"), rating = inf.s("rating"),
                year = inf.s("releaseDate").ifBlank { inf.s("release_date") }.take(4),
                genre = inf.s("genre"), cast = inf.s("cast"),
                duration = inf.s("episode_run_time").let { if (it.isBlank()) "" else "$it min" }
            )
            val epsObj = root.optJSONObject("episodes")
            val map = LinkedHashMap<Int, List<Item>>()
            if (epsObj != null) {
                val keys = ArrayList<String>()
                val ks = epsObj.keys()
                while (ks.hasNext()) keys.add(ks.next())
                keys.sortBy { k -> k.toIntOrNull() ?: Int.MAX_VALUE }
                for (k in keys) {
                    val arr = epsObj.optJSONArray(k) ?: continue
                    val season = k.toIntOrNull() ?: 0
                    val list = ArrayList<Item>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val eid = o.s("id")
                        if (eid.isEmpty()) continue
                        val num = o.optInt("episode_num", i + 1)
                        val title = o.s("title").ifBlank { "Episode $num" }
                        val img = o.optJSONObject("info")?.s("movie_image")?.takeIf { s -> s.isNotBlank() }
                        list.add(Item(eid, "E$num  $title", img, k, T_EP, o.s("container_extension").ifBlank { "mp4" }))
                    }
                    if (list.isNotEmpty()) map[season] = list
                }
            }
            SeriesDetail(map, meta)
        }
    }

    private suspend fun epgFrom(q: String): List<Prog> {
        val raw = text(q)
        val arr = JSONObject(raw).optJSONArray("epg_listings")
        val out = ArrayList<Prog>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val st = o.s("start_timestamp").toLongOrNull()
                val en = o.s("stop_timestamp").toLongOrNull()
                if (st != null && en != null && en > st) out.add(Prog(decode(o.s("title")), st, en, decode(o.s("description"))))
            }
        }
        return out.sortedBy { it.start }
    }

    /** Now + next programmes for one channel. Tries the short guide first, then the full day table. */
    suspend fun shortEpg(id: String): List<Prog> = try {
        var l = try {
            epgFrom("&action=get_short_epg&stream_id=$id&limit=4")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList<Prog>()
        }
        if (l.isEmpty()) {
            val t = System.currentTimeMillis() / 1000
            l = try {
                epgFrom("&action=get_simple_data_table&stream_id=$id").filter { it.end > t }.take(6)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList<Prog>()
            }
        }
        l
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList<Prog>()
    }

    /** Movie details straight from the provider. */
    suspend fun vodInfo(id: String): Meta? {
        val raw = try {
            text("&action=get_vod_info&vod_id=$id")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val info = JSONObject(raw).optJSONObject("info") ?: return null
        return Meta(
            poster = info.s("movie_image").ifBlank { info.s("cover_big") },
            plot = info.s("plot").ifBlank { info.s("description") },
            rating = info.s("rating"),
            year = info.s("releasedate").ifBlank { info.s("release_date") }.take(4),
            genre = info.s("genre"), cast = info.s("cast"), duration = info.s("duration")
        )
    }

    private fun decode(s: String): String = try {
        val d = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
        if (d.isNotBlank() && !d.contains('\uFFFD') && d.none { it.isISOControl() && it != '\n' && it != '\t' }) d else s
    } catch (e: Exception) {
        s
    }
}

/** PIN + lock/hide lists, kept in their own prefs so signing out never clears them. */
class Vault(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nova_vault", Context.MODE_PRIVATE)
    private fun hash(pin: String, salt: String) =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }
    fun hasPin() = sp.contains("h")
    fun setPin(pin: String) {
        val salt = UUID.randomUUID().toString()
        sp.edit().putString("s", salt).putString("h", hash(pin, salt)).apply()
    }
    fun check(pin: String) = sp.getString("h", null) == hash(pin, sp.getString("s", "") ?: "")
    fun get(name: String): Set<String> = sp.getStringSet(name, emptySet())?.toSet() ?: emptySet()
    fun put(name: String, v: Set<String>) { sp.edit().putStringSet(name, HashSet(v)).apply() }
}

fun friendly(e: Throwable): String = when (e) {
    is OutOfMemoryError -> "Not enough memory on this device for that list."
    is UnknownHostException -> "Can't find the server. Check the address."
    is SocketTimeoutException -> "The server took too long to answer."
    is ConnectException -> "Can't connect to the server."
    is JSONException -> "The server sent an unexpected answer. Check the address."
    else -> e.message ?: "Network error"
}

class Vm(app: Application) : AndroidViewModel(app) {
    private val sp = app.getSharedPreferences("nova", 0)
    private val cfgSp = app.getSharedPreferences("nova_cfg", 0)
    private val favSp = app.getSharedPreferences("nova_fav", 0)
    private val posSp = app.getSharedPreferences("nova_pos", 0)
    private val histSp = app.getSharedPreferences("nova_hist", 0)
    private val vault = Vault(app)
    private var api: Xtream? = null
    private var job: Job? = null
    private val loadingSet = HashSet<Int>()

    var cfg by mutableStateOf(loadCfg(cfgSp)); private set
    var account by mutableStateOf<Account?>(null); private set
    var info by mutableStateOf(AcctInfo()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var tab by mutableIntStateOf(cfg.startTab); private set
    var query by mutableStateOf("")
    val libs = mutableStateMapOf<Int, Lib>()
    val errs = mutableStateMapOf<Int, String>()
    var loadingTypes by mutableStateOf(setOf<Int>()); private set
    var playing by mutableStateOf<Pair<List<Item>, Int>?>(null); private set
    var settingsOpen by mutableStateOf(false)

    // ---- series detail ----
    var detail by mutableStateOf<Item?>(null); private set
    var detailInfo by mutableStateOf<SeriesDetail?>(null); private set
    var detailErr by mutableStateOf<String?>(null); private set

    // ---- resume prompt, exit prompt, refresh timing ----
    var resumeReq by mutableStateOf<ResumeReq?>(null); private set
    var exitAsk by mutableStateOf(false)
    private var startAt = -1L
    private var lastLoad = System.currentTimeMillis()

    // ---- continue watching ----
    var hist by mutableStateOf(loadHist(histSp)); private set
    private var histSeries: Item? = null

    // ---- intro, playlist mode, posters, movie details ----
    var showIntro by mutableStateOf(true); private set
    private var m3uEps: Map<String, Map<Int, List<Item>>> = emptyMap()
    val posters = mutableStateMapOf<String, String>()
    private val posterSp = app.getSharedPreferences("nova_poster", 0)
    private val posterTried = HashSet<String>()
    private val metaGate = Semaphore(2)
    var movie by mutableStateOf<Item?>(null); private set
    var movieMeta by mutableStateOf<Meta?>(null); private set

    // ---- favorites ----
    var favKeys by mutableStateOf<Set<String>>(favSp.getStringSet("k", emptySet())?.toSet() ?: emptySet()); private set

    // ---- pinned groups (shown at the top of Live TV, Movies and Series) ----
    var pinKeys by mutableStateOf<Set<String>>(favSp.getStringSet("pins", emptySet())?.toSet() ?: emptySet()); private set
    var groupMenu by mutableStateOf<GroupTarget?>(null); private set

    // ---- parental lock ----
    var lockedKeys by mutableStateOf(vault.get("lock")); private set
    var hiddenKeys by mutableStateOf(vault.get("hide")); private set
    var unlocked by mutableStateOf(false); private set
    var pinReq by mutableStateOf<PinReq?>(null); private set
    var menu by mutableStateOf<MenuTarget?>(null); private set
    private var fails = 0
    private var blockedUntil = 0L

    // ---- EPG ----
    var now by mutableLongStateOf(System.currentTimeMillis()); private set
    val epgMap = mutableStateMapOf<String, Epg>()
    private val inflight = HashSet<String>()
    private val gate = Semaphore(3)

    init {
        if (posSp.all.size > 500) posSp.edit().clear().apply()
        viewModelScope.launch { while (true) { delay(60_000); now = System.currentTimeMillis() } }
        // No login: the app always starts straight on the built-in free sources (plus any repos added in Settings).
        restoreSession(Account("sources", "", "", 2))
    }

    /**
     * The saved login is only ever removed by Sign out. When the app starts again (even after being closed for days)
     * the user is signed in at once; the server is contacted in the background. If the network or the server is not
     * ready yet, the lists show "Retry" instead of throwing the user back to the Sign In screen.
     */
    private fun restoreSession(a: Account) {
        account = a
        m3uEps = emptyMap()
        if (a.mode != 0) {
            api = null
            job?.cancel()
            job = viewModelScope.launch {
                busy = true
                try { doConnect(a) }
                catch (e: CancellationException) { throw e }
                catch (e: Throwable) { val m = friendly(e); for (t in 0..2) errs[t] = m }
                finally { busy = false }
            }
        } else {
            val x = Xtream(a, ua())
            api = x
            startLoading()
            viewModelScope.launch {
                try { info = x.login() }
                catch (e: CancellationException) { throw e }
                catch (e: Throwable) { /* stay signed in; account details simply stay empty until the next refresh */ }
            }
        }
    }

    fun ua(): String = when (cfg.ua) {
        1 -> "VLC/3.0.18 LibVLC/3.0.18"
        2 -> "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        3 -> "IPTVSmartersPro"
        else -> "ShujaTV/2.0"
    }

    fun updateCfg(c: Cfg) {
        val uaChanged = c.ua != cfg.ua
        cfg = c
        saveCfg(cfgSp, c)
        if (uaChanged) account?.let { if (it.mode == 0) api = Xtream(it, ua()) }
    }

    private fun run(block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            busy = true; error = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { error = friendly(e); showIntro = false }
            finally { busy = false }
        }
    }

    fun login(a0: Account) {
        var a = a0
        if (a.mode == 1) {
            // An Xtream style link (get.php?username=..&password=..) is signed in as a full Xtream account.
            val uri = android.net.Uri.parse(a.host.trim())
            val us = uri.getQueryParameter("username")
            val pw = uri.getQueryParameter("password")
            val auth = uri.authority
            if (!us.isNullOrBlank() && !pw.isNullOrBlank() && !auth.isNullOrBlank() && (uri.path ?: "").contains("get.php")) {
                a = Account((uri.scheme ?: "http") + "://" + auth, us, pw, 0)
            }
        }
        connect(a)
    }

    private fun connect(a: Account) = run { doConnect(a) }

    private suspend fun doConnect(a: Account) {
        val firstSignIn = account == null
        if (a.mode != 0) {
            val data = if (a.mode == 2) Sources.loadAll(Sources.all(getApplication<Application>()), ua()) else M3u.load(a.host.trim(), ua())
            if (data.items.values.sumOf { it.size } == 0) throw IllegalStateException("No channels found in this playlist")
            val ctx = getApplication<Application>()
            val built = withContext(Dispatchers.Default) {
                (0..2).map { t ->
                    val its = data.items[t] ?: emptyList()
                    val use = if (t == T_LIVE && a.mode == 2) Health.dropDead(ctx, its) else its
                    buildLib(data.cats[t] ?: emptyList(), use)
                }
            }
            if (a.mode == 2) {
                liveAll = data.items[T_LIVE] ?: emptyList()
                liveCats = data.cats[T_LIVE] ?: emptyList()
            }
            api = null; account = a; info = AcctInfo(if (a.mode == 2) "Free sources" else "Playlist")
            m3uEps = data.eps
            libs.clear(); errs.clear(); loadingSet.clear(); loadingTypes = emptySet()
            for (t in 0..2) libs[t] = built[t]
        } else {
            val x = Xtream(a, ua())
            val i = x.login()
            api = x; account = a; info = i; m3uEps = emptyMap()
            libs.clear(); errs.clear(); loadingSet.clear(); loadingTypes = emptySet()
        }
        sp.edit().putString("h", a.host).putString("u", a.user).putString("p", a.pass).putInt("m", a.mode).commit()
        lastLoad = System.currentTimeMillis()
        if (firstSignIn) showIntro = true
        if (a.mode == 0) startLoading()
        if (a.mode == 2) checkChannels()
    }

    // ---- channel health check (hides dead / blocked live channels) ----
    var healthNote by mutableStateOf(""); private set
    private var healthJob: Job? = null
    private var liveAll: List<Item> = emptyList()
    private var liveCats: List<Category> = emptyList()

    private suspend fun rebuildLive() {
        val ctx = getApplication<Application>()
        val keep = Health.dropDead(ctx, liveAll)
        if (keep.isEmpty()) return
        val lib = withContext(Dispatchers.Default) { buildLib(liveCats, keep) }
        libs[T_LIVE] = lib
    }

    /** Tests live channels in the background; dead ones disappear from the list when the test is done. */
    fun checkChannels(force: Boolean = false) {
        val ctx = getApplication<Application>()
        healthJob?.cancel()
        if (liveAll.isEmpty() || !Health.enabled(ctx)) return
        if (force) Health.clear(ctx)
        healthJob = viewModelScope.launch {
            try {
                val todo = Health.unknown(ctx, liveAll)
                if (todo.isNotEmpty()) {
                    healthNote = "Checking ${todo.size} channels..."
                    val ok = Health.probe(ctx, todo, ua())
                    if (ok < 0) { healthNote = "Check skipped (no internet?)"; return@launch }
                }
                rebuildLive()
                val shown = Health.dropDead(ctx, liveAll).size
                healthNote = "${liveAll.size - shown} dead channels hidden"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                healthNote = ""
            }
        }
    }

    fun setHideDead(on: Boolean) {
        val ctx = getApplication<Application>()
        Health.setEnabled(ctx, on)
        if (on) checkChannels() else {
            healthJob?.cancel()
            healthNote = ""
            viewModelScope.launch { rebuildLive() }
        }
    }

    fun finishIntro() { showIntro = false }

    // The "Updating your content" screen belongs to a fresh start only. It is shown when the app was
    // closed with Exit (or the system ended it), and skipped when the app is simply brought back to the front.
    private var closedByUser = false

    fun markClosed() { closedByUser = true }

    fun onColdLaunch() {
        if (!closedByUser) return
        closedByUser = false
        if (account == null) return
        lastLoad = System.currentTimeMillis()
        showIntro = true
        refreshAll()
    }

    /** Called when the app comes back to the front. Refreshes quietly in the background if the lists are old. */
    fun refreshIfStale() {
        val a = account ?: return
        if (showIntro || busy) return
        if (System.currentTimeMillis() - lastLoad < 6 * 3600_000L) return
        lastLoad = System.currentTimeMillis()
        if (a.mode != 0) {
            connect(a)
            return
        }
        viewModelScope.launch {
            for (t in 0..2) loadLib(t, true)
        }
    }

    /** Playlist link with the password part hidden. */
    fun serverLabel(): String {
        val a = account ?: return ""
        if (a.mode == 2) return "Free sources"
        if (a.mode != 1) return a.host
        val u = android.net.Uri.parse(a.host)
        return (u.scheme ?: "http") + "://" + (u.host ?: "playlist") + "/****"
    }

    // ---- content loading ----
    private fun startLoading() {
        viewModelScope.launch {
            loadLib(tab)
            for (t in 0..2) {
                if (t != tab) loadLib(t)
            }
        }
    }

    fun ensure(type: Int) {
        if (libs.containsKey(type) || loadingSet.contains(type)) return
        viewModelScope.launch { loadLib(type) }
    }

    fun reload(type: Int) {
        if ((account?.mode ?: 0) != 0) { refreshAll(); return }
        libs.remove(type)
        viewModelScope.launch { loadLib(type) }
    }

    fun refreshAll() {
        val a = account
        if (a != null && a.mode != 0) { connect(a); return }
        libs.clear(); errs.clear(); epgMap.clear(); posterTried.clear()
        startLoading()
    }

    private suspend fun loadLib(type: Int, quiet: Boolean = false) {
        val x = api ?: return
        if ((!quiet && libs.containsKey(type)) || !loadingSet.add(type)) return
        if (!quiet) loadingTypes = loadingTypes + type
        try {
            if (!quiet) errs.remove(type)
            val cats = try {
                x.categories(type)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val items = x.items(type)
            val lib = withContext(Dispatchers.Default) { buildLib(cats, items) }
            if (api === x) {
                libs[type] = lib
                lastLoad = System.currentTimeMillis()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!quiet && api === x) errs[type] = friendly(e)
        } finally {
            loadingSet.remove(type)
            if (!quiet) loadingTypes = loadingTypes - type
        }
    }

    private fun buildLib(cats: List<Category>, items: List<Item>): Lib {
        val grouped = items.groupBy { it.cat }
        val known = HashSet<String>()
        for (c in cats) known.add(c.id)
        val rows = ArrayList<Pair<Category, List<Item>>>()
        for (c in cats) {
            val l = grouped[c.id]
            if (l != null && l.isNotEmpty()) rows.add(c to l)
        }
        val orphans = items.filter { it.cat !in known }
        if (orphans.isNotEmpty()) rows.add(Category("", if (rows.isEmpty()) "All" else "Other") to orphans)
        val adult = HashSet<String>()
        for (c in cats) if (isAdult(c.name)) adult.add(c.id)
        val names = HashMap<String, String>()
        for (c in cats) names[c.id] = c.name
        val recent = if (items.isEmpty() || items[0].type == T_LIVE) emptyList<Item>() else items.filter { it.added > 0L }.sortedByDescending { it.added }.take(80)
        return Lib(rows, items, adult, names, recent)
    }

    fun selectTab(t: Int) {
        if (t == tab) return
        tab = t
        ensure(t)
    }

    // ---- rows / search ----
    fun rowsFor(lib: Lib, type: Int): List<Pair<Category, List<Item>>> {
        val hideA = cfg.hideAdult
        val hid = hiddenKeys
        val filterHidden = !unlocked && hid.isNotEmpty()
        val sorted = cfg.sort == 1
        val out = ArrayList<Pair<Category, List<Item>>>(lib.rows.size)
        for ((c, l) in lib.rows) {
            if (hideA && c.id in lib.adult) continue
            if (filterHidden && catKey(type, c.id) in hid) continue
            var list = if (filterHidden) l.filter { itemKey(it) !in hid } else l
            if (list.isEmpty()) continue
            if (sorted) list = list.sortedBy { it.lc }
            out.add(c to list)
        }
        return out
    }

    private fun passes(i: Item, lib: Lib): Boolean {
        if (cfg.hideAdult && i.cat in lib.adult) return false
        if (!unlocked && isHidden(i)) return false
        return true
    }

    fun favItems(lib: Lib): List<Item> {
        val keys = favKeys
        if (keys.isEmpty()) return emptyList()
        return lib.all.filter { itemKey(it) in keys && passes(it, lib) }
    }

    fun search(q: String): SearchRes {
        val toks = q.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (toks.isEmpty()) return SearchRes(emptyList(), emptyList(), emptyList())
        fun find(type: Int): List<Item> {
            val lib = libs[type] ?: return emptyList()
            val out = ArrayList<Item>()
            for (item in lib.all) {
                val n = item.lc
                var ok = true
                for (t in toks) {
                    if (!n.contains(t)) { ok = false; break }
                }
                if (ok && passes(item, lib)) {
                    out.add(item)
                    if (out.size >= 150) break
                }
            }
            return if (out.size > 1) out.sortedByDescending { it.lc.startsWith(toks[0]) } else out
        }
        return SearchRes(find(T_LIVE), find(T_MOVIE), find(T_SERIES))
    }

    // ---- playback ----
    fun urlOf(item: Item, ts: Boolean = false): String = if (item.url.isNotEmpty()) item.url else (api?.url(item, ts) ?: "")

    fun open(list: List<Item>, i: Int) {
        if (i !in list.indices) return
        val item = list[i]
        when (item.type) {
            T_SERIES -> openSeries(item)
            T_MOVIE -> openMovie(item)
            else -> play(list, i)
        }
    }

    // ---- movie details ----
    fun openMovie(item: Item) {
        if (!unlocked && isLocked(item)) askPin("Enter PIN to open") { loadMovie(item) } else loadMovie(item)
    }

    private fun loadMovie(item: Item) {
        movie = item; movieMeta = null
        val x = api
        viewModelScope.launch {
            var m = Meta()
            try {
                if (x != null && item.url.isEmpty()) m = m.merge(x.vodInfo(item.id))
                if (cfg.meta && !m.complete) m = m.merge(MetaLookup.find(item.name, false, ua()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
            }
            if (movie?.id == item.id) movieMeta = m
        }
    }

    fun closeMovie() { movie = null; movieMeta = null }

    /** Looks up a poster for a card that has none (or whose picture failed to load). */
    /** Runs inside the card's own effect, so it stops as soon as the card scrolls out of view. */
    suspend fun ensurePoster(item: Item) {
        if (!cfg.meta || item.live || item.type == T_EP) return
        val k = itemKey(item)
        if (posters.containsKey(k) || posterTried.contains(k)) return
        val saved = posterSp.getString(k, null)
        if (saved != null) { posters[k] = saved; posterTried.add(k); return }
        try {
            val m = metaGate.withPermit { MetaLookup.find(item.name, item.type == T_SERIES, ua()) }
            posterTried.add(k)
            val url = m?.poster ?: ""
            if (url.isNotBlank()) {
                posters[k] = url
                posterSp.edit().putString(k, url).apply()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            posterTried.add(k)
        }
    }

    fun play(list: List<Item>, i: Int) {
        if (i !in list.indices) return
        val item = list[i]
        if (item.url.startsWith("archive://")) {
            // Internet Archive films: look up the real video file first, then play it.
            viewModelScope.launch {
                val real = try { ArchiveApi.resolve(item.url.removePrefix("archive://")) }
                catch (e: CancellationException) { throw e }
                catch (e: Throwable) { null }
                if (real == null) {
                    android.widget.Toast.makeText(getApplication<Application>(), "This film has no playable video file", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    val copy = list.toMutableList()
                    copy[i] = item.copy(url = real)
                    play(copy, i)
                }
            }
            return
        }
        fun go() {
            val pos = resumeAt(item)
            if (pos > 0L) {
                resumeReq = ResumeReq(list, i, pos)
            } else {
                begin(list, i, 0L)
            }
        }
        if (!unlocked && isLocked(item)) askPin("Enter PIN to watch") { go() } else go()
    }

    private fun begin(list: List<Item>, i: Int, at: Long) {
        histSeries = if (list[i].type == T_EP) detail else null
        startAt = at
        playing = list to i
    }

    fun chooseResume(r: ResumeReq, fromStart: Boolean) {
        resumeReq = null
        if (r.index in r.list.indices) begin(r.list, r.index, if (fromStart) 0L else r.posMs)
    }

    fun cancelResume() { resumeReq = null }

    /** Start position chosen for the item that is about to play (read once by the player). */
    fun takeStartAt(): Long {
        val v = startAt
        startAt = -1L
        return v
    }
    fun stop() { playing = null }

    fun resumeAt(item: Item): Long = if (cfg.resume && !item.live) posSp.getLong(itemKey(item), 0L) else 0L

    fun savePos(item: Item, pos: Long, dur: Long) {
        if (item.live || !cfg.resume) return
        val k = itemKey(item)
        val keep = dur > 0 && pos > 15_000 && pos < dur - 45_000
        val e = posSp.edit()
        if (keep) e.putLong(k, pos) else e.remove(k)
        e.apply()
        if (keep) touchHist(item) else dropHist(item)
    }

    private fun touchHist(item: Item) {
        val first = hist.firstOrNull()
        if (first != null && first.item.type == item.type && first.item.id == item.id) return
        val series = if (item.type == T_EP) histSeries else null
        if (item.type == T_EP && series == null) return
        val nh = ArrayList<Hist>()
        nh.add(Hist(item, series, System.currentTimeMillis()))
        for (h in hist) {
            val same = if (item.type == T_EP) {
                h.item.type == T_EP && h.series?.id == series?.id
            } else {
                h.item.type == item.type && h.item.id == item.id
            }
            if (!same && nh.size < 30) nh.add(h)
        }
        hist = nh
        saveHist(histSp, nh)
    }

    private fun dropHist(item: Item) {
        if (hist.isEmpty()) return
        val nh = hist.filter { h -> !(h.item.type == item.type && h.item.id == item.id) }
        if (nh.size != hist.size) {
            hist = nh
            saveHist(histSp, nh)
        }
    }

    fun clearResume() {
        posSp.edit().clear().apply()
        hist = emptyList()
        histSp.edit().clear().apply()
    }

    fun contFor(tab: Int): List<Hist> {
        if (!cfg.contRow || tab == T_LIVE) return emptyList()
        val want = if (tab == T_MOVIE) T_MOVIE else T_EP
        return hist.filter { h ->
            h.item.type == want && (want == T_MOVIE || h.series != null) && (unlocked || !isHidden(h.series ?: h.item))
        }
    }

    fun contItem(h: Hist): Item = h.series ?: h.item

    fun recentFor(lib: Lib): List<Item> {
        if (!cfg.recentRow) return emptyList()
        return lib.recent.filter { passes(it, lib) }.take(40)
    }

    fun resumeHist(h: Hist) {
        if (h.item.type == T_EP) {
            val s = h.series ?: return
            if (!unlocked && isLocked(s)) askPin("Enter PIN to open") { loadSeries(s, h.item.id) } else loadSeries(s, h.item.id)
        } else {
            play(listOf(h.item), 0)
        }
    }

    // ---- series ----
    fun openSeries(item: Item) {
        if (!unlocked && isLocked(item)) askPin("Enter PIN to open") { loadSeries(item) } else loadSeries(item)
    }

    private fun loadSeries(item: Item, autoEp: String? = null) {
        val acct = account ?: return
        val x = api
        detail = item; detailInfo = null; detailErr = null
        viewModelScope.launch {
            try {
                val d = if (acct.mode != 0 || x == null) {
                    SeriesDetail(m3uEps[item.id] ?: emptyMap(), Meta(poster = item.icon ?: ""))
                } else {
                    x.seriesInfo(item.id)
                }
                if (detail?.id == item.id) {
                    detailInfo = d
                    if (autoEp != null) {
                        val all = d.eps.values.flatten()
                        val idx = all.indexOfFirst { it.id == autoEp }
                        if (idx >= 0) play(all, idx)
                    }
                }
                if (cfg.meta && !d.meta.complete && detail?.id == item.id) {
                    val m = MetaLookup.find(item.name, true, ua())
                    if (m != null && detail?.id == item.id) detailInfo = SeriesDetail(d.eps, d.meta.merge(m))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (detail?.id == item.id && detailInfo == null) detailErr = friendly(e)
            }
        }
    }

    fun closeDetail() { detail = null; detailInfo = null; detailErr = null }

    // ---- favorites ----
    fun toggleFav(item: Item) {
        val k = itemKey(item)
        val nv = if (k in favKeys) favKeys - k else favKeys + k
        favKeys = nv
        favSp.edit().putStringSet("k", HashSet(nv)).apply()
        menu = null
    }

    fun clearFavs() {
        favKeys = emptySet()
        favSp.edit().remove("k").apply()
    }

    fun togglePin(key: String) {
        val nv = if (key in pinKeys) pinKeys - key else pinKeys + key
        pinKeys = nv
        favSp.edit().putStringSet("pins", HashSet(nv)).apply()
        menu = null
        groupMenu = null
    }

    fun clearPins() {
        pinKeys = emptySet()
        favSp.edit().remove("pins").apply()
    }

    fun openGroupMenu(type: Int, catId: String, name: String) { groupMenu = GroupTarget(type, catId, name) }
    fun closeGroupMenu() { groupMenu = null }

    // ---- lock / hide ----
    fun itemKey(i: Item) = "i:${i.type}:${i.id}"
    fun catKey(type: Int, id: String) = "c:$type:$id"
    fun isLocked(i: Item): Boolean {
        if (lockedKeys.isEmpty()) return false
        return itemKey(i) in lockedKeys || catKey(i.type, i.cat) in lockedKeys
    }
    fun isHidden(i: Item): Boolean {
        if (hiddenKeys.isEmpty()) return false
        return itemKey(i) in hiddenKeys || catKey(i.type, i.cat) in hiddenKeys
    }

    fun askPin(title: String, onOk: () -> Unit) { pinReq = PinReq(title, !vault.hasPin(), onOk) }
    fun cancelPin() { pinReq = null }

    /** Returns null on success, or an error message to show. */
    fun submitPin(p: String): String? {
        val r = pinReq ?: return null
        if (r.create) vault.setPin(p) else {
            val t = System.currentTimeMillis()
            if (t < blockedUntil) return "Too many tries. Wait ${(blockedUntil - t) / 1000 + 1}s"
            if (!vault.check(p)) {
                fails++
                if (fails >= 5) { fails = 0; blockedUntil = t + 30_000 }
                return "Wrong PIN"
            }
            fails = 0
        }
        pinReq = null
        r.onOk()
        return null
    }

    fun toggleUnlock() { if (unlocked) unlocked = false else askPin("Enter PIN to unlock") { unlocked = true } }
    fun relock() { unlocked = false }

    fun changePin() {
        if (vault.hasPin()) askPin("Enter current PIN") { pinReq = PinReq("Create a new 4-digit PIN", true) {} }
        else pinReq = PinReq("Create a 4-digit PIN", true) {}
    }

    fun clearLocks() {
        if (lockedKeys.isEmpty()) return
        guard { vault.put("lock", emptySet()); lockedKeys = emptySet() }
    }

    fun clearHidden() {
        if (hiddenKeys.isEmpty()) return
        guard { vault.put("hide", emptySet()); hiddenKeys = emptySet() }
    }

    fun openMenu(i: Item) {
        menu = MenuTarget(i, i.cat, libs[i.type]?.names?.get(i.cat) ?: "this category")
    }
    fun closeMenu() { menu = null }

    private fun guard(block: () -> Unit) { if (unlocked) block() else askPin("Enter PIN") { block() } }
    private fun withPin(block: () -> Unit) { if (vault.hasPin()) block() else askPin("Create a 4-digit PIN") { block() } }

    fun toggleLock(key: String) {
        menu = null
        if (key in lockedKeys) guard { setKey("lock", key, false) } else withPin { setKey("lock", key, true) }
    }
    fun toggleHide(key: String) {
        menu = null
        if (key in hiddenKeys) guard { setKey("hide", key, false) } else withPin { setKey("hide", key, true) }
    }
    private fun setKey(name: String, key: String, on: Boolean) {
        val cur = if (name == "lock") lockedKeys else hiddenKeys
        val nv = if (on) cur + key else cur - key
        vault.put(name, nv)
        if (name == "lock") lockedKeys = nv else hiddenKeys = nv
    }

    // ---- EPG: only for the focused channel / the playing channel, cached until the programme ends ----
    suspend fun loadEpg(item: Item) {
        val x = api ?: return
        if (!item.live || !cfg.epg) return
        val t = System.currentTimeMillis()
        val cur = epgMap[item.id]
        if (cur != null && fresh(cur, t)) return
        if (!inflight.add(item.id)) return
        try {
            gate.withPermit { epgMap[item.id] = Epg(x.shortEpg(item.id), System.currentTimeMillis()) }
        } finally { inflight.remove(item.id) }
    }
    private fun fresh(e: Epg, t: Long): Boolean = when {
        e.now(t) != null -> true
        e.list.isEmpty() -> t - e.at < 10 * 60_000
        else -> t - e.at < 2 * 60_000
    }
}
