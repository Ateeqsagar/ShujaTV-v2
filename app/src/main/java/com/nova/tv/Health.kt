package com.nova.tv

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Tests live channels from THIS device's own internet connection (so geo-blocked and dead links are caught too)
 * and hides the ones that do not answer. Results are remembered for 12 hours, so later launches are instant.
 */
object Health {
    private const val TTL = 12 * 3600_000L
    private fun sp(c: Context) = c.getSharedPreferences("nova_health", 0)

    fun enabled(c: Context): Boolean = sp(c).getBoolean("_on", true)
    fun setEnabled(c: Context, v: Boolean) { sp(c).edit().putBoolean("_on", v).apply() }
    fun clear(c: Context) {
        val on = enabled(c)
        sp(c).edit().clear().putBoolean("_on", on).apply()
    }

    /** Value > 0 = alive at that time, value < 0 = dead at that time, missing = never tested. */
    private fun stateOf(snap: Map<String, *>, id: String, now: Long): Int {
        val v = (snap[id] as? Long) ?: return -1
        if (v == 0L || now - Math.abs(v) > TTL) return -1
        return if (v > 0) 1 else 0
    }

    fun dropDead(c: Context, items: List<Item>): List<Item> {
        if (!enabled(c)) return items
        val snap = sp(c).all
        val now = System.currentTimeMillis()
        return items.filter { stateOf(snap, it.id, now) != 0 }
    }

    fun unknown(c: Context, items: List<Item>): List<Item> {
        val snap = sp(c).all
        val now = System.currentTimeMillis()
        return items.filter { stateOf(snap, it.id, now) == -1 }
    }

    /** Returns how many answered, or -1 when almost nothing answered (probably no internet): then nothing is saved. */
    suspend fun probe(c: Context, todo: List<Item>, ua: String): Int {
        val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
        val gate = Semaphore(40)
        val results = ConcurrentHashMap<String, Boolean>()
        coroutineScope {
            todo.map { item ->
                async(Dispatchers.IO) {
                    gate.withPermit { results[item.id] = alive(client, item.url, ua) }
                }
            }.awaitAll()
        }
        val ok = results.count { it.value }
        if (todo.size >= 20 && ok * 100 < todo.size * 8) return -1
        val now = System.currentTimeMillis()
        val e = sp(c).edit()
        for ((id, good) in results) e.putLong(id, if (good) now else -now)
        e.apply()
        return ok
    }

    private fun alive(client: OkHttpClient, url: String, ua: String): Boolean = try {
        val req = Request.Builder().url(url).header("User-Agent", ua).build()
        client.newCall(req).execute().use { r ->
            val src = r.body?.source()
            if (!r.isSuccessful || src == null) {
                false
            } else if (!src.request(16)) {
                false
            } else {
                src.request(512)
                val head = src.buffer.snapshot().utf8().trimStart()
                !head.startsWith("<") && (!url.contains(".m3u8", true) || head.contains("#EXT"))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        false
    }
}
