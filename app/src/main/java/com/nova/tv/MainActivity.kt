package com.nova.tv

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private val vm: Vm get() = (application as App).vm

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val last = getSharedPreferences("nova_crash", 0).getLong("t", 0L)
        if (savedInstanceState == null && System.currentTimeMillis() - last < 20_000) {
            Toast.makeText(this, "Shuja TV v2 recovered from an error. Details: Settings > Last crash log", Toast.LENGTH_LONG).show()
        }
        if (savedInstanceState == null) vm.onColdLaunch()
        hideBars()
        setContent {
            // The layout is designed for a TV (about 540dp high). On phones everything is scaled down so it fits.
            val conf = LocalConfiguration.current
            val base = LocalDensity.current
            val k = (minOf(conf.screenWidthDp, conf.screenHeightDp) / 540f).coerceIn(0.65f, 1f)
            CompositionLocalProvider(LocalDensity provides Density(base.density * k, base.fontScale)) {
                NovaApp(vm) { vm.markClosed(); finishAndRemoveTask() }
            }
        }
    }

    /** Full-screen: status and navigation bars stay hidden (swipe from the edge to show them briefly). */
    private fun hideBars() {
        try {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } catch (e: Exception) {
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onStart() {
        super.onStart()
        vm.refreshIfStale()
    }

    override fun onStop() {
        super.onStop()
        vm.relock()
    }
}
