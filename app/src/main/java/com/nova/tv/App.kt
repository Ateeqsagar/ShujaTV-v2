package com.nova.tv

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Last line of defence: if anything still throws, the error is saved (Settings > Last crash log)
 * and the app relaunches itself instead of leaving the TV on the home screen.
 * A 20 second guard prevents restart loops.
 */
class App : Application(), ImageLoaderFactory {
    /** Lighter image loading: smaller memory cache (no GC stalls on TVs with little RAM), disk cache, no fade. */
    override fun newImageLoader(): ImageLoader {
        val http = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 24; maxRequestsPerHost = 8 })
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
        return ImageLoader.Builder(this)
            .okHttpClient(http)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(200L * 1024 * 1024).build() }
            .allowRgb565(true)
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
    }

    /** Disk cache for movies and episodes (384 MB): rewinds, replays and resume start instantly and cost no download. */
    val mediaCache: androidx.media3.datasource.cache.SimpleCache by lazy {
        androidx.media3.datasource.cache.SimpleCache(
            cacheDir.resolve("media"),
            androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(384L * 1024 * 1024),
            androidx.media3.database.StandaloneDatabaseProvider(this)
        )
    }

    /** One shared state for the whole life of the app process, so reopening the app does not reload everything. */
    val vm: Vm by lazy { Vm(this) }

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val now = System.currentTimeMillis()
                val sp = getSharedPreferences("nova_crash", 0)
                val last = sp.getLong("t", 0L)
                sp.edit().putString("log", ("" + java.util.Date(now) + "\n" + sw.toString()).take(6000)).putLong("t", now).commit()
                if (now - last > 20_000) {
                    val i = Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
                    val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    am.set(AlarmManager.RTC, now + 700, pi)
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            } catch (t: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
