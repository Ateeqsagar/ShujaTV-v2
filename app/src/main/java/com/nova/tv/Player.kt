package com.nova.tv

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.LayoutInflater
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private fun fmt(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = s % 3600 / 60; val x = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%02d:%02d".format(m, x)
}

/**
 * Fast start and no interruptions:
 *  - big, size-capped buffers (time AND bytes), 30 s of back buffer for instant rewinds
 *  - movies and episodes go through a disk cache, so jumping back and replays need no download
 *  - many more automatic retries on bad network instead of stopping the stream
 */
private fun buildPlayer(ctx: Context, cfg: Cfg, ua: String, vod: Boolean): ExoPlayer {
    val b = when (cfg.buffer) {
        0 -> intArrayOf(5000, 20000, 1000, 2000)
        2 -> intArrayOf(30000, 120000, 2500, 8000)
        else -> intArrayOf(15000, 60000, 1500, 4000)
    }
    val load = DefaultLoadControl.Builder()
        .setBufferDurationsMs(b[0], b[1], b[2], b[3])
        .setTargetBufferBytes(if (cfg.buffer == 2) 96 * 1024 * 1024 else 64 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .setBackBuffer(if (vod) 30000 else 0, vod)
        .build()
    val rf = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
    val http = DefaultHttpDataSource.Factory().setUserAgent(ua)
        .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(10000).setReadTimeoutMs(15000)
    val dsf: DataSource.Factory = if (vod) {
        try {
            CacheDataSource.Factory()
                .setCache((ctx.applicationContext as App).mediaCache)
                .setUpstreamDataSourceFactory(http)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } catch (e: Throwable) {
            http
        }
    } else http
    val p = ExoPlayer.Builder(ctx, rf).setLoadControl(load)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dsf).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8)))
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true).build()
    p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    var tp = p.trackSelectionParameters.buildUpon()
    if (cfg.quality == 1) tp = tp.setMaxVideoSize(1920, 1080)
    else if (cfg.quality == 2) tp = tp.setMaxVideoSize(1280, 720)
    val lang = AUDIO_CODES.getOrElse(cfg.audio) { "" }
    if (lang.isNotEmpty()) tp = tp.setPreferredAudioLanguage(lang)
    if (!cfg.subs) tp = tp.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    p.trackSelectionParameters = tp.build()
    return p
}

/**
 * Second, tiny player used only for the live seek preview: video only, no sound, lowest quality,
 * paused. Seeking it to the jump target shows the real frame at that time (like Netflix).
 */
private fun buildPreviewPlayer(ctx: Context, ua: String): ExoPlayer {
    val load = DefaultLoadControl.Builder()
        .setBufferDurationsMs(1500, 5000, 400, 800)
        .setTargetBufferBytes(8 * 1024 * 1024)
        .build()
    val http = DefaultHttpDataSource.Factory().setUserAgent(ua)
        .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(8000).setReadTimeoutMs(10000)
    val p = ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
        .setLoadControl(load)
        .setMediaSourceFactory(DefaultMediaSourceFactory(http))
        .build()
    p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    p.volume = 0f
    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .setMaxVideoSize(640, 360)
        .setForceLowestBitrate(true)
        .build()
    p.playWhenReady = false
    return p
}

/** Full-screen window on top of the app, so the lists underneath keep their scroll position and focus. */
@Composable
fun PlayerScreen(list: List<Item>, start: Int, vm: Vm, onExit: () -> Unit) {
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        PlayerBody(list, start, vm, onExit)
    }
}

@Composable
private fun PlayerBody(list: List<Item>, start: Int, vm: Vm, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val tv = rememberIsTv()
    val scope = rememberCoroutineScope()
    var isPlay by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var menuPage by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    val okLong = remember { booleanArrayOf(false) }
    var idx by remember { mutableIntStateOf(start.coerceIn(0, list.lastIndex)) }
    val item = list[idx]
    val cur by rememberUpdatedState(item)
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var show by remember { mutableStateOf(true) }
    var ping by remember { mutableIntStateOf(0) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var tries by remember { mutableIntStateOf(0) }
    var firstStart by remember { mutableStateOf(true) }
    val fr = remember { FocusRequester() }
    val cfg = vm.cfg
    var scrub by remember { mutableStateOf<Long?>(null) }
    // After a jump is applied, the seek card stays up until the picture is back, so the screen never shows plain black.
    var hold by remember { mutableStateOf<Long?>(null) }
    val lastJump = remember { longArrayOf(0L) }
    var scrubDir by remember { mutableIntStateOf(1) }
    val fwdMs = SEEK_STEPS[cfg.seekF.coerceIn(0, SEEK_STEPS.lastIndex)] * 1000L
    val backMs = SEEK_STEPS[cfg.seekB.coerceIn(0, SEEK_STEPS.lastIndex)] * 1000L

    val player = remember { buildPlayer(ctx, vm.cfg, vm.ua(), !list[start.coerceIn(0, list.lastIndex)].live) }

    // ---- live seek preview: a second small player that shows the real frame at the jump target ----
    var pv by remember { mutableStateOf<ExoPlayer?>(null) }
    var pvBusy by remember { mutableStateOf(false) }
    var pvFailed by remember { mutableStateOf(false) }
    // Many providers allow one connection only; a second stream would cut the movie, so no live preview then.
    val oneConn = vm.info.maxCons.trim().toIntOrNull() == 1
    // Off: never. On: skipped when the account allows only 1 connection. Always: tried anyway.
    val previewOk = cfg.previewMode != 0 && !item.live && !pvFailed && !(oneConn && cfg.previewMode == 1)
    var pvFrame by remember { mutableStateOf(false) }
    val pvListener = remember {
        object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) { pvBusy = s == Player.STATE_BUFFERING }
            override fun onRenderedFirstFrame() { pvFrame = true }
            override fun onPlayerError(e: PlaybackException) { pvFailed = true }
        }
    }
    fun releasePreview() {
        val p = pv ?: return
        pv = null
        pvBusy = false
        pvFrame = false
        try { p.removeListener(pvListener); p.release() } catch (e: Exception) { }
    }
    fun ensurePreview(): ExoPlayer? {
        val have = pv
        if (have != null) return have
        if (!previewOk) return null
        return try {
            val p = buildPreviewPlayer(ctx, vm.ua())
            p.addListener(pvListener)
            p.setMediaItem(MediaItem.fromUri(vm.urlOf(cur)), player.currentPosition.coerceAtLeast(0L))
            p.prepare()
            pvBusy = true
            pv = p
            p
        } catch (e: Throwable) {
            pvFailed = true
            null
        }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                buffering = s == Player.STATE_BUFFERING
                if (s == Player.STATE_READY) { error = null; tries = 0; hold = null }
                if (s == Player.STATE_ENDED && !cur.live && vm.cfg.autoNext && idx + 1 < list.size) idx += 1
            }
            override fun onPlayWhenReadyChanged(w: Boolean, r: Int) { isPlay = w }
            override fun onPlayerError(e: PlaybackException) {
                tries++
                val c = cur
                if (tries > 5) { error = "Can't play this stream (${e.errorCodeName})"; return }
                val preferTs = vm.cfg.liveFormat == 1
                val u = if (c.live && tries == 1) vm.urlOf(c, !preferTs) else vm.urlOf(c, preferTs)
                val at = if (c.live) -1L else player.currentPosition.coerceAtLeast(0L)
                // Bad network: wait a little longer after each failure, then continue from the same second.
                scope.launch {
                    delay(if (tries == 1) 300L else 1200L * tries)
                    if (at >= 0L) player.setMediaItem(MediaItem.fromUri(u), at) else player.setMediaItem(MediaItem.fromUri(u))
                    player.prepare()
                    player.playWhenReady = true
                }
            }
        }
        player.addListener(l)
        onDispose {
            try {
                if (!cur.live) vm.savePos(cur, player.currentPosition, player.duration.coerceAtLeast(0L))
            } catch (e: Exception) { }
            player.removeListener(l)
            player.release()
            releasePreview()
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) player.pause() else if (e == Lifecycle.Event.ON_START) player.play()
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    LaunchedEffect(idx) {
        tries = 0; error = null; buffering = true; ping++
        val u = vm.urlOf(item, vm.cfg.liveFormat == 1)
        val at = if (firstStart) vm.takeStartAt().coerceAtLeast(0L) else 0L
        firstStart = false
        if (at > 0) player.setMediaItem(MediaItem.fromUri(u), at) else player.setMediaItem(MediaItem.fromUri(u))
        player.prepare()
        player.playWhenReady = true
    }
    // Stuck for a long time without an error (frozen connection): start the connection again from the same second.
    LaunchedEffect(buffering, idx) {
        if (buffering && error == null) {
            delay(if (item.live) 20_000L else 30_000L)
            if (player.playbackState == Player.STATE_BUFFERING && error == null) {
                val u = vm.urlOf(item, vm.cfg.liveFormat == 1)
                if (item.live) player.setMediaItem(MediaItem.fromUri(u))
                else player.setMediaItem(MediaItem.fromUri(u), player.currentPosition.coerceAtLeast(0L))
                player.prepare()
                player.playWhenReady = true
            }
        }
    }
    LaunchedEffect(idx) {
        if (!item.live) {
            while (true) {
                delay(10_000)
                vm.savePos(item, player.currentPosition, player.duration.coerceAtLeast(0L))
            }
        }
    }
    LaunchedEffect(idx) {
        scrub = null; hold = null
        releasePreview()
        pvFailed = false
    }
    LaunchedEffect(pvFailed) { if (pvFailed) releasePreview() }

    fun applySeek(t: Long) {
        player.seekTo(t)
        player.playWhenReady = true   // after a jump the video always plays by itself
        pos = t
        hold = t
        scrub = null
        lastJump[0] = SystemClock.uptimeMillis()
    }
    // Seek is applied a moment after the last press, so several presses add up and the preview can follow.
    LaunchedEffect(scrub) {
        val t = scrub
        if (t != null) {
            delay(650)
            applySeek(t)
        }
    }
    LaunchedEffect(hold) {
        if (hold != null) { delay(3000); hold = null }
    }
    // The preview player follows the jump target.
    LaunchedEffect(scrub) {
        val t = scrub
        if (t != null && previewOk) {
            val p = ensurePreview()
            if (p != null) {
                delay(80)
                p.seekTo(t)
            }
        }
    }
    // The second connection is closed soon after the jump is finished.
    LaunchedEffect(scrub, hold, idx) {
        if (scrub == null && hold == null) {
            delay(120_000)
            releasePreview()
        }
    }
    // Open the preview player ahead of time (as soon as the video plays or the controls appear),
    // so the very first jump already shows a real picture instead of black.
    LaunchedEffect(ping, idx, previewOk, buffering) {
        if (previewOk && pv == null && !item.live && !buffering) {
            delay(600)
            if (player.playbackState == Player.STATE_READY) ensurePreview()
        }
    }
    // If the preview never produces a picture (decoder busy, server refuses), stop trying and use the simple time label.
    LaunchedEffect(pv) {
        if (pv != null) {
            delay(12_000)
            if (!pvFrame && pv != null) pvFailed = true
        }
    }
    LaunchedEffect(ping) { show = true; delay(4000); show = false }
    LaunchedEffect(show, idx, scrub, hold) {
        while ((show || scrub != null || hold != null) && !item.live) { pos = player.currentPosition; dur = player.duration.coerceAtLeast(0L); delay(500) }
    }
    LaunchedEffect(Unit) { fr.grab() }

    val epg = vm.epgMap[item.id]
    val prog = epg?.now(vm.now)
    val nxt = epg?.next(vm.now)
    LaunchedEffect(idx) {
        vm.loadEpg(list[idx])
        if (list.size > 1) {
            vm.loadEpg(list[(idx + 1) % list.size])
            vm.loadEpg(list[(idx - 1 + list.size) % list.size])
        }
    }
    LaunchedEffect(vm.now / 60_000) { vm.loadEpg(cur) }

    fun step(d: Int): Int {
        var i = idx
        repeat(list.size) {
            i = (i + d + list.size) % list.size
            val c = list[i]
            if (vm.unlocked || (!vm.isLocked(c) && !vm.isHidden(c))) return i
        }
        return idx
    }

    fun seekBy(dir: Int) {
        if (cur.live) return
        val d = player.duration.coerceAtLeast(0L)
        val base = scrub ?: player.currentPosition
        scrubDir = dir
        scrub = (base + dir * (if (dir > 0) fwdMs else backMs)).coerceIn(0L, if (d > 0L) d else Long.MAX_VALUE)
        ping++
    }

    fun seekFrac(f: Float) {
        val d = player.duration.coerceAtLeast(0L)
        if (cur.live || d <= 0L) return
        val t = (f.coerceIn(0f, 1f) * d).toLong()
        scrubDir = if (t > player.currentPosition) 1 else -1
        scrub = t
        ping++
    }

    fun togglePlay() {
        player.playWhenReady = !player.playWhenReady
        ping++
    }

    /** OK on the remote. A jump that was just made is simply confirmed (and keeps playing); it never pauses it. */
    fun okPress() {
        val t = scrub
        if (t != null) {
            applySeek(t)
        } else if (!cur.live) {
            if (SystemClock.uptimeMillis() - lastJump[0] < 2500L) player.playWhenReady = true
            else player.playWhenReady = !player.playWhenReady
        }
        ping++
    }

    LaunchedEffect(menuOpen) { if (!menuOpen) fr.grab() }
    val showNow by rememberUpdatedState(show)
    val mode = when (cfg.aspect) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).focusable()
            // Touch: tap shows or hides the controls; double-tap the left or right half to jump back or forward.
            .pointerInput(idx) {
                detectTapGestures(
                    onTap = { val shown = showNow; if (shown) show = false; if (!shown) ping++ },
                    onDoubleTap = { o -> if (!cur.live) seekBy(if (o.x > size.width / 2f) 1 else -1) }
                )
            }
            .onPreviewKeyEvent { e ->
            val k = e.key
            val dpad = k == Key.DirectionUp || k == Key.DirectionDown || k == Key.DirectionLeft ||
                k == Key.DirectionRight || k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
            val isOk = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
            if (isOk) {
                if (e.type == KeyEventType.KeyDown) {
                    okLong[0] = e.nativeKeyEvent.repeatCount > 0
                    return@onPreviewKeyEvent true
                }
                if (e.type == KeyEventType.KeyUp) {
                    val ne = e.nativeKeyEvent
                    val wasLong = okLong[0] || ne.eventTime - ne.downTime >= 550L
                    okLong[0] = false
                    if (wasLong) { menuPage = 0; menuOpen = true; ping++ } else okPress()
                    return@onPreviewKeyEvent true
                }
            }
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent dpad
            when (k) {
                Key.DirectionUp, Key.ChannelUp -> { if (item.live) idx = step(1); ping++; true }
                Key.DirectionDown, Key.ChannelDown -> {
                    if (item.live) idx = step(-1)
                    else if (k == Key.DirectionDown && showNow) { menuPage = 0; menuOpen = true }
                    ping++; true
                }
                Key.DirectionRight, Key.MediaFastForward -> { seekBy(1); true }
                Key.DirectionLeft, Key.MediaRewind -> { seekBy(-1); true }
                Key.MediaNext -> { if (idx + 1 < list.size) idx += 1; true }
                Key.MediaPrevious -> { if (idx > 0) idx -= 1; true }
                Key.MediaPlayPause -> { togglePlay(); true }
                Key.MediaPlay -> { player.playWhenReady = true; ping++; true }
                Key.MediaPause -> { player.playWhenReady = false; ping++; true }
                Key.Menu -> { menuPage = 0; menuOpen = true; ping++; true }
                else -> false
            }
        }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    keepScreenOn = true
                    setKeepContentOnPlayerReset(true)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { v -> v.resizeMode = mode }
        )

        if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Red, strokeWidth = 5.dp)
        error?.let { Text(it, color = Red, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center)) }

        val sc = scrub ?: hold
        if (!item.live) {
            AnimatedVisibility(show || sc != null, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
                val shownPos = sc ?: pos
                NfControls(
                    title = item.name,
                    playing = isPlay,
                    buffering = buffering && error == null,
                    shownPos = shownPos,
                    dur = dur,
                    realPos = pos,
                    backSec = (backMs / 1000L).toInt(),
                    fwdSec = (fwdMs / 1000L).toInt(),
                    speedText = "Speed (" + (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()) + "x)",
                    hasNext = idx + 1 < list.size,
                    tv = tv,
                    jumping = sc != null,
                    landing = scrub == null,
                    preview = if (sc != null && previewOk) pv else null,
                    previewBusy = pvBusy,
                    previewFrame = pvFrame,
                    previewOn = previewOk,
                    onBack = onExit,
                    onToggle = { togglePlay() },
                    onSkip = { seekBy(it) },
                    onSeekFrac = { seekFrac(it) },
                    onOpen = { pg -> menuPage = pg; menuOpen = true; ping++ },
                    onNext = { if (idx + 1 < list.size) idx += 1 }
                )
            }
        }

        if (menuOpen) {
            PlayerSettings(
                player = player, vm = vm, live = item.live, title = item.name, speed = speed, initialPage = menuPage,
                onSpeed = { v -> speed = v; player.playbackParameters = PlaybackParameters(v) },
                onClose = { menuOpen = false; ping++ }
            )
        }

        if (!tv && item.live) {
            AnimatedVisibility(show, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!item.live) {
                        TouchBtn("«  ${backMs / 1000}s", 60) { seekBy(-1) }
                        TouchBtn(if (isPlay) "❚❚" else "▶", 80) { player.playWhenReady = !player.playWhenReady; ping++ }
                        TouchBtn("${fwdMs / 1000}s  »", 60) { seekBy(1) }
                    } else {
                        TouchBtn("‹  Previous", 60) { idx = step(-1); ping++ }
                        TouchBtn("Next  ›", 60) { idx = step(1); ping++ }
                    }
                }
            }
            AnimatedVisibility(show, Modifier.align(Alignment.TopEnd).padding(20.dp), enter = fadeIn(), exit = fadeOut()) {
                TouchBtn("✕", 48) { onExit() }
            }
        }

        AnimatedVisibility(show && item.live, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.92f))))
                    .padding(horizontal = 48.dp, vertical = 36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.icon != null) {
                    AsyncImage(model = item.icon, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(.08f)))
                    Spacer(Modifier.width(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(item.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    if (item.live) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("LIVE", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Red).padding(horizontal = 8.dp, vertical = 2.dp))
                            Text("   ${idx + 1} / ${list.size}", color = Dim, fontSize = 16.sp)
                        }
                        if (prog != null) {
                            Spacer(Modifier.height(6.dp))
                            Text("Now: ${prog.title}", color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (prog.desc.isNotBlank()) Text(prog.desc, color = Dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val pf = ((vm.now - prog.start * 1000f) / ((prog.end - prog.start) * 1000f)).coerceIn(0f, 1f)
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(.2f))) {
                                Box(Modifier.fillMaxWidth(pf).fillMaxHeight().background(RedBrush))
                            }
                            nxt?.let { Text("Next: ${it.title}", color = Dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    } else {
                        val shownPos = scrub ?: pos
                        val frac = if (dur > 0) (shownPos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                        // Touch: tap or drag on the bar to jump to that point.
                        Box(
                            Modifier.fillMaxWidth().height(26.dp)
                                .pointerInput(dur) {
                                    detectTapGestures { o -> if (dur > 0L) { scrubDir = if (o.x / size.width * dur > pos) 1 else -1; scrub = (o.x / size.width * dur).toLong().coerceIn(0L, dur); ping++ } }
                                }
                                .pointerInput(dur) {
                                    detectHorizontalDragGestures { change, _ ->
                                        if (dur > 0L) {
                                            val t = (change.position.x / size.width * dur).toLong().coerceIn(0L, dur)
                                            scrubDir = if (t > pos) 1 else -1
                                            scrub = t; ping++
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(.2f))) {
                                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(RedBrush))
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text("${fmt(shownPos)} / ${fmt(dur)}", color = Dim, fontSize = 16.sp)
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(.14f))
                            .border(1.dp, Color.White.copy(.3f), RoundedCornerShape(50))
                            .clickable { menuOpen = true; ping++ }.padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GearIcon(Modifier.size(22.dp))
                        Text("Settings", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    if (tv) Text(
                        if (item.live) "▲ ▼  channel     Hold OK  settings" else "◀ ${backMs / 1000}s   ${fwdMs / 1000}s ▶     OK  pause     Hold OK  settings",
                        color = Dim, fontSize = 14.sp
                    )
                }
            }
        }
    }
}

/**
 * Netflix style controls for movies and episodes: title on top, big play/pause with skip buttons in the middle,
 * a thin red progress bar with the remaining time, and the options row at the bottom.
 * On a TV the remote does everything (see the hint line); on a phone every item is tappable.
 */
@Composable
private fun NfControls(
    title: String, playing: Boolean, buffering: Boolean, shownPos: Long, dur: Long, realPos: Long,
    backSec: Int, fwdSec: Int, speedText: String, hasNext: Boolean, tv: Boolean,
    jumping: Boolean, landing: Boolean, preview: ExoPlayer?, previewBusy: Boolean, previewFrame: Boolean, previewOn: Boolean,
    onBack: () -> Unit, onToggle: () -> Unit, onSkip: (Int) -> Unit, onSeekFrac: (Float) -> Unit,
    onOpen: (Int) -> Unit, onNext: () -> Unit
) {
    val frac = if (dur > 0L) (shownPos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val remaining = (dur - shownPos).coerceAtLeast(0L)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(130.dp)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(.8f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(300.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.92f)))))

        // ---- top: back, title, settings ----
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 40.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(26.dp)) {
                    val w = this.size.width
                    val h = this.size.height
                    val st = 3.dp.toPx()
                    drawLine(Color.White, Offset(w * 0.92f, h * 0.5f), Offset(w * 0.12f, h * 0.5f), strokeWidth = st, cap = StrokeCap.Round)
                    drawLine(Color.White, Offset(w * 0.46f, h * 0.12f), Offset(w * 0.1f, h * 0.5f), strokeWidth = st, cap = StrokeCap.Round)
                    drawLine(Color.White, Offset(w * 0.46f, h * 0.88f), Offset(w * 0.1f, h * 0.5f), strokeWidth = st, cap = StrokeCap.Round)
                }
            }
            Text(
                title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
            )
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable { onOpen(0) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GearIcon(Modifier.size(22.dp))
                Text("Settings", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }

        // ---- centre: skip back, play / pause, skip forward ----
        Row(
            Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(96.dp)
        ) {
            SkipIcon(backSec, false, 64.dp) { onSkip(-1) }
            Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                if (!buffering) PlayPauseIcon(playing, 88.dp, onToggle)
            }
            SkipIcon(fwdSec, true, 64.dp) { onSkip(1) }
        }

        // ---- bottom: progress bar, remaining time, options ----
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 40.dp, end = 40.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoxWithConstraints(
                    Modifier.weight(1f).height(36.dp)
                        .pointerInput(dur) { detectTapGestures { o -> onSeekFrac(o.x / size.width) } }
                        .pointerInput(dur) { detectHorizontalDragGestures { change, _ -> onSeekFrac(change.position.x / size.width) } }
                ) {
                    val w = maxWidth
                    Box(
                        Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp)
                            .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(.3f))
                    ) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Red))
                    }
                    val knob = if (jumping) 20.dp else 16.dp
                    Box(Modifier.align(Alignment.CenterStart).offset(x = w * frac - knob / 2).size(knob).clip(CircleShape).background(Red))
                    if (jumping) {
                        // Preview follows the knob, like Netflix: same place above the bar, centred on the jump target.
                        if (previewOn) {
                            val cw = 240.dp
                            val cx = (w * frac - cw / 2).coerceIn(0.dp, (w - cw).coerceAtLeast(0.dp))
                            Box(Modifier.align(Alignment.TopStart).offset(x = cx, y = -(181.dp))) {
                                NfPreview(preview, previewBusy, previewFrame, shownPos, realPos, landing)
                            }
                        } else {
                            // Preview is off (or not possible): only a small time label above the knob.
                            val cw = 120.dp
                            val cx = (w * frac - cw / 2).coerceIn(0.dp, (w - cw).coerceAtLeast(0.dp))
                            Box(Modifier.align(Alignment.TopStart).offset(x = cx, y = -(48.dp)).width(cw)) {
                                SeekChip(shownPos, realPos)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Text(fmt(remaining), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                NfOpt(speedText) { onOpen(4) }
                NfOpt("Aspect ratio") { onOpen(1) }
                NfOpt("Audio & Subtitles") { onOpen(2) }
                if (hasNext) NfOpt("Next episode", onNext)
            }
            if (tv) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "◀ ▶  skip $backSec / $fwdSec s      OK  play / pause      ▼  options      Hold OK  settings",
                    color = Dim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun NfOpt(text: String, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/** Preview above the bar: the real frame at the jump target (second small player), the time, and how far we jump. */
@Composable
private fun NfPreview(p: ExoPlayer?, busy: Boolean, frame: Boolean, target: Long, current: Long, landing: Boolean) {
    val delta = (target - current) / 1000L
    val fwd = delta >= 0L
    val a = if (delta < 0L) -delta else delta
    val dText = if (a >= 60L) "${a / 60}m ${a % 60}s" else "${a}s"
    val accent = if (fwd) Color(0xFF46D369) else Color(0xFFE87C03)
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.width(240.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.width(240.dp).height(135.dp).clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF2A1220), Color(0xFF14090F))))
                .border(2.dp, Color.White, shape)
        ) {
            if (p != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        (LayoutInflater.from(c).inflate(R.layout.preview_player_view, null, false) as PlayerView).apply {
                            isFocusable = false
                            isFocusableInTouchMode = false
                        }
                    },
                    update = { v -> if (v.player !== p) v.player = p },
                    onRelease = { v -> v.player = null }
                )
                if (!frame) {
                    // Nothing has been drawn yet: cover the (still black) video surface with the card background.
                    Box(
                        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2A1220), Color(0xFF14090F)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(30.dp), color = Color.White, strokeWidth = 3.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(if (landing) "Jumping…" else "Loading preview", color = Dim, fontSize = 13.sp)
                        }
                    }
                } else if (busy) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(.3f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 3.dp)
                    }
                }
            } else {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (fwd) "»" else "«", color = accent, fontSize = 44.sp, fontWeight = FontWeight.Black)
                    Text(
                        if (landing) "Jumping…" else "Loading preview",
                        color = Dim, fontSize = 13.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(fmt(target), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(if (fwd) "+$dText" else "-$dText", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PlayPauseIcon(playing: Boolean, dp: Dp, onClick: () -> Unit) {
    Canvas(Modifier.size(dp).clip(CircleShape).clickable(onClick = onClick)) {
        val w = this.size.width
        val h = this.size.height
        if (playing) {
            val bw = w * 0.2f
            drawRect(Color.White, Offset(w * 0.27f, h * 0.2f), androidx.compose.ui.geometry.Size(bw, h * 0.6f))
            drawRect(Color.White, Offset(w * 0.53f, h * 0.2f), androidx.compose.ui.geometry.Size(bw, h * 0.6f))
        } else {
            val path = Path().apply {
                moveTo(w * 0.32f, h * 0.2f)
                lineTo(w * 0.8f, h * 0.5f)
                lineTo(w * 0.32f, h * 0.8f)
                close()
            }
            drawPath(path, Color.White)
        }
    }
}

/** Circular arrow with the number of seconds inside (back = arrow on the left, forward = arrow on the right). */
@Composable
private fun SkipIcon(sec: Int, forward: Boolean, dp: Dp, onClick: () -> Unit) {
    Box(Modifier.size(dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = this.size.minDimension * 0.07f
            val r = this.size.minDimension / 2f - sw * 2.2f
            val c = center
            val tipDeg = if (forward) -60f else -120f
            drawArc(
                Color.White, tipDeg, if (forward) -300f else 300f, false,
                Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                style = Stroke(sw, cap = StrokeCap.Round)
            )
            val th = Math.toRadians(tipDeg.toDouble())
            val px = c.x + r * cos(th).toFloat()
            val py = c.y + r * sin(th).toFloat()
            val dx = if (forward) -sin(th).toFloat() else sin(th).toFloat()
            val dy = if (forward) cos(th).toFloat() else -cos(th).toFloat()
            val hh = sw * 3.4f
            val bx = px - dx * hh * 0.3f
            val by = py - dy * hh * 0.3f
            val path = Path().apply {
                moveTo(px + dx * hh, py + dy * hh)
                lineTo(bx - dy * hh * 0.9f, by + dx * hh * 0.9f)
                lineTo(bx + dy * hh * 0.9f, by - dx * hh * 0.9f)
                close()
            }
            drawPath(path, Color.White)
        }
        Text("$sec", color = Color.White, fontSize = (dp.value * 0.28f).sp, fontWeight = FontWeight.Bold)
    }
}

/** Round on-screen button for touch screens. */
@Composable
private fun TouchBtn(label: String, size: Int, onClick: () -> Unit) {
    Box(
        Modifier.height(size.dp).widthIn(min = size.dp).clip(RoundedCornerShape(50))
            .background(Color.Black.copy(.55f)).border(1.dp, Color.White.copy(.3f), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = if (size >= 80) 24.sp else 16.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

// ---------------- player settings popup ----------------

private class TrackOpt(val group: Tracks.Group, val index: Int, val name: String, val selected: Boolean)

private fun trackName(f: Format, n: Int, audio: Boolean): String {
    val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
        val nm = java.util.Locale(code).displayLanguage
        if (nm.isNotBlank()) nm.replaceFirstChar { it.uppercase() } else code
    }
    val base = f.label?.takeIf { it.isNotBlank() } ?: lang ?: "Track ${n + 1}"
    val ch = if (audio) when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null } else null
    return if (ch != null) "$base  ·  $ch" else base
}

private fun trackList(t: Tracks, type: Int): List<TrackOpt> {
    val out = ArrayList<TrackOpt>()
    for (g in t.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            out.add(TrackOpt(g, i, trackName(g.getTrackFormat(i), out.size, type == C.TRACK_TYPE_AUDIO), g.isTrackSelected(i)))
        }
    }
    return out
}

private val ASPECT_NAMES = listOf("Fit  (original ratio)", "Fill  (stretch to screen)", "Zoom  (crop the edges)")
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private fun speedName(v: Float) = if (v == 1f) "Normal" else (if (v == v.toInt().toFloat()) "${v.toInt()}x" else "${v}x")

@Composable
private fun GearIcon(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val w = r * 0.24f
        drawCircle(tint, radius = r * 0.5f, style = Stroke(w))
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val dx = Math.cos(a).toFloat()
            val dy = Math.sin(a).toFloat()
            drawLine(
                tint,
                Offset(center.x + dx * r * 0.66f, center.y + dy * r * 0.66f),
                Offset(center.x + dx * r * 0.94f, center.y + dy * r * 0.94f),
                strokeWidth = w * 1.25f, cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun OptRow(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (f) Color.White else Color.White.copy(.10f))
            .onFocusChanged { f = it.hasFocus }.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(label, color = if (f) Color.Black else Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, color = if (f) Color(0xFF333333) else Dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One popup for everything about the picture and sound: aspect ratio, audio track, subtitles and speed. */
@Composable
private fun PlayerSettings(
    player: ExoPlayer, vm: Vm, live: Boolean, title: String, speed: Float, initialPage: Int,
    onSpeed: (Float) -> Unit, onClose: () -> Unit
) {
    var page by remember { mutableIntStateOf(initialPage) }
    var tick by remember { mutableIntStateOf(0) }
    var subsOn by remember { mutableStateOf(!player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) }
    val fr = remember { FocusRequester() }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { tick++ }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    val tracks = remember(tick) { player.currentTracks }
    val audio = remember(tracks) { trackList(tracks, C.TRACK_TYPE_AUDIO) }
    val text = remember(tracks) { trackList(tracks, C.TRACK_TYPE_TEXT) }
    val aspect = vm.cfg.aspect.coerceIn(0, 2)

    fun pick(o: TrackOpt) {
        var b = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, listOf(o.index)))
        if (o.group.type == C.TRACK_TYPE_TEXT) b = b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        player.trackSelectionParameters = b.build()
        if (o.group.type == C.TRACK_TYPE_TEXT) {
            subsOn = true
            if (!vm.cfg.subs) vm.updateCfg(vm.cfg.copy(subs = true))
        }
    }
    fun subsOff() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        subsOn = false
        if (vm.cfg.subs) vm.updateCfg(vm.cfg.copy(subs = false))
    }

    val selIdx = when (page) {
        1 -> aspect
        2 -> audio.indexOfFirst { it.selected }.coerceAtLeast(0)
        3 -> if (!subsOn) 0 else (text.indexOfFirst { it.selected } + 1).coerceAtLeast(0)
        4 -> SPEEDS.indexOf(speed).coerceAtLeast(0)
        else -> 0
    }
    val listState = remember(page) { LazyListState((selIdx - 1).coerceAtLeast(0)) }
    LaunchedEffect(page) { delay(80); fr.grab() }

    Dialog(
        onDismissRequest = { if (page != 0) page = 0 else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier.width(480.dp).glass(16.dp).padding(26.dp).safeDpad(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GearIcon(Modifier.size(24.dp), Red)
                Column(Modifier.weight(1f)) {
                    Text(
                        when (page) { 1 -> "Aspect ratio"; 2 -> "Audio"; 3 -> "Subtitles"; 4 -> "Playback speed"; else -> "Player settings" },
                        color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold
                    )
                    if (page == 0) Text(title, color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LazyColumn(Modifier.heightIn(max = 300.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (page == 0) {
                    item {
                        OptRow("Aspect ratio", ASPECT_NAMES[aspect].substringBefore("  "), Modifier.focusRequester(fr)) { page = 1 }
                    }
                    item {
                        val cur = audio.firstOrNull { it.selected }
                        OptRow("Audio", if (audio.isEmpty()) "Default" else (cur?.name ?: "Auto")) { page = 2 }
                    }
                    item {
                        val cur = text.firstOrNull { it.selected }
                        OptRow("Subtitles", if (!subsOn) "Off" else (cur?.name ?: if (text.isEmpty()) "None available" else "On")) { page = 3 }
                    }
                    if (!live) item { OptRow("Playback speed", speedName(speed)) { page = 4 } }
                    item { OptRow("Close", "") { onClose() } }
                } else {
                    item { OptRow("‹  Back", "") { page = 0 } }
                    when (page) {
                        1 -> itemsIndexed(ASPECT_NAMES) { i, n ->
                            OptRow(n, if (i == aspect) "✓" else "", if (i == selIdx) Modifier.focusRequester(fr) else Modifier) {
                                vm.updateCfg(vm.cfg.copy(aspect = i))
                            }
                        }
                        2 -> if (audio.isEmpty()) {
                            item { Text("No other audio tracks in this stream.", color = Dim, fontSize = 16.sp, modifier = Modifier.padding(8.dp)) }
                        } else itemsIndexed(audio) { i, o ->
                            OptRow(o.name, if (o.selected) "✓" else "", if (i == selIdx) Modifier.focusRequester(fr) else Modifier) { pick(o) }
                        }
                        3 -> {
                            item {
                                OptRow("Off", if (!subsOn) "✓" else "", if (selIdx == 0) Modifier.focusRequester(fr) else Modifier) { subsOff() }
                            }
                            if (text.isEmpty()) {
                                item { Text("No subtitles in this stream.", color = Dim, fontSize = 16.sp, modifier = Modifier.padding(8.dp)) }
                            } else itemsIndexed(text) { i, o ->
                                OptRow(o.name, if (subsOn && o.selected) "✓" else "", if (i + 1 == selIdx) Modifier.focusRequester(fr) else Modifier) { pick(o) }
                            }
                        }
                        4 -> itemsIndexed(SPEEDS) { i, v ->
                            OptRow(speedName(v), if (v == speed) "✓" else "", if (i == selIdx) Modifier.focusRequester(fr) else Modifier) { onSpeed(v) }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

/** Small time label shown above the knob when the picture preview is off. */
@Composable
private fun SeekChip(target: Long, current: Long) {
    val delta = (target - current) / 1000L
    val fwd = delta >= 0L
    val a = if (delta < 0L) -delta else delta
    val dText = if (a >= 60L) "${a / 60}m ${a % 60}s" else "${a}s"
    val accent = if (fwd) Color(0xFF46D369) else Color(0xFFE87C03)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(.78f))
            .border(1.dp, Color.White.copy(.35f), RoundedCornerShape(8.dp)).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(fmt(target), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(if (fwd) "+$dText" else "-$dText", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
