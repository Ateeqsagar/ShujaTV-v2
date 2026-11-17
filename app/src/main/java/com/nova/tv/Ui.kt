package com.nova.tv

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil.Coil
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

val Bg = Color(0xFF141414)
val Panel = Color(0xFF1F1F1F)
val Red = Color(0xFFE50914)
val Dim = Color(0xFFB3B3B3)
val Light = Color(0xFFE5E5E5)
val KeyBg = Color(0xFF333333)
val RedBrush: Brush = SolidColor(Red)

/** Frosted-glass look shared by every popup: see-through (about 80%) so the screen behind still shows. */
val GlassBrush: Brush = Brush.verticalGradient(listOf(Color(0xCC2B1426), Color(0xCC101015)))
val GlassBtn = Color.White.copy(alpha = 0.13f)
fun Modifier.glass(radius: Dp = 16.dp): Modifier =
    this.clip(RoundedCornerShape(radius)).background(GlassBrush).border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(radius))

/** A titled group of items: a category, or a special row such as Continue Watching. cont is set only for Continue Watching. */
class Section(val title: String, val items: List<Item>, val cont: List<Hist>? = null, val pinned: Boolean = false, val catId: String = "")

/** Focus can race the first layout of a new window; retry briefly instead of crashing. */
suspend fun FocusRequester.grab() {
    repeat(6) {
        try {
            requestFocus()
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            delay(80)
        }
    }
}

/**
 * Moves focus with the D-pad ourselves, and survives the Compose bug where the focus search touches a list item
 * that has just been removed ("LayoutCoordinate operations are only valid when isAttached is true").
 * In that rare case this one key press does nothing instead of closing the app.
 */
@Composable
fun Modifier.safeDpad(): Modifier {
    val fm = LocalFocusManager.current
    return this.onKeyEvent { e ->
        val dir = when (e.key) {
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            Key.DirectionLeft -> FocusDirection.Left
            Key.DirectionRight -> FocusDirection.Right
            else -> null
        }
        if (dir == null || e.type != KeyEventType.KeyDown) {
            false
        } else {
            try {
                fm.moveFocus(dir)
            } catch (ex: IllegalStateException) {
                true
            }
        }
    }
}

/**
 * TV remote: OK = normal click, hold OK for about half a second = options menu (replaces the Menu key,
 * which many remotes do not have). The menu opens when the key is released, so the held key can never
 * click something inside the menu by accident. The Menu key still works too.
 */
@Composable
fun Modifier.okHold(onHold: () -> Unit, onTap: () -> Unit): Modifier {
    val down = remember { booleanArrayOf(false, false) } // [0] key went down on this item, [1] held long
    return this.onPreviewKeyEvent { e ->
        val k = e.key
        if (k == Key.Menu && e.type == KeyEventType.KeyDown) {
            onHold()
            true
        } else if (k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter) {
            if (e.type == KeyEventType.KeyDown) {
                if (!down[0]) { down[0] = true; down[1] = false }
                if (e.nativeKeyEvent.repeatCount > 0) down[1] = true
                true
            } else if (e.type == KeyEventType.KeyUp) {
                if (down[0]) {
                    val ne = e.nativeKeyEvent
                    val long = down[1] || ne.eventTime - ne.downTime >= 550L
                    down[0] = false; down[1] = false
                    if (long) onHold() else onTap()
                }
                true
            } else false
        } else false
    }
}

@Composable
fun NovaApp(vm: Vm, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Bg).safeDpad()) {
        if (vm.showIntro) {
            Intro(vm)
        } else {
            Home(vm)
            if (vm.settingsOpen) SettingsScreen(vm)
            if (vm.detail != null) SeriesScreen(vm)
            if (vm.movie != null) MovieScreen(vm)
            vm.playing?.let { p -> PlayerScreen(p.first, p.second, vm) { vm.stop() } }
        }
        vm.pinReq?.let { r -> PinDialog(r.create, r.title, { vm.submitPin(it) }) { vm.cancelPin() } }
        vm.menu?.let { m -> MenuDialog(vm, m) }
        vm.groupMenu?.let { g -> GroupMenuDialog(vm, g) }
        vm.resumeReq?.let { r -> ResumeDialog(vm, r) }
        if (vm.exitAsk) ExitDialog(vm, onExit)
    }
}

@Composable
fun FullDialog(onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(Bg).safeDpad()) { content() }
    }
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
    password: Boolean = false, ime: ImeAction = ImeAction.Next, onGo: (() -> Unit)? = null
) {
    var f by remember { mutableStateOf(false) }
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
        cursorBrush = SolidColor(Color.White),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = ime),
        keyboardActions = KeyboardActions(onSearch = { onGo?.invoke() }, onDone = { onGo?.invoke() }),
        modifier = modifier.onFocusChanged { f = it.hasFocus },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(KeyBg)
                    .border(2.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(6.dp)).padding(14.dp)
            ) {
                if (value.isEmpty()) Text(hint, color = Dim, fontSize = 18.sp, maxLines = 1)
                inner()
            }
        }
    )
}

/**
 * Search box for TV: D-pad focus only highlights it. Pressing OK turns it into a text field and opens the keyboard.
 */
@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, onGo: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var f by remember { mutableStateOf(false) }
    var got by remember(editing) { mutableStateOf(false) }
    val tf = remember { FocusRequester() }
    val view = LocalView.current
    val ctx = LocalContext.current
    fun showKeyboard() {
        try {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        } catch (e: Exception) {
        }
    }
    LaunchedEffect(editing) {
        if (editing) {
            tf.grab()
            delay(80)
            showKeyboard()
        }
    }
    val shape = RoundedCornerShape(6.dp)
    if (!editing) {
        Box(
            modifier.clip(shape).background(KeyBg)
                .border(2.dp, if (f) Color.White else Color.Transparent, shape)
                .onFocusChanged { f = it.hasFocus }
                .onPreviewKeyEvent { e ->
                    val k = e.key
                    val center = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
                    if (center && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0) {
                        // long press on the search bar: start a fresh search
                        onChange("")
                        editing = true
                        true
                    } else false
                }
                .clickable { editing = true }.padding(14.dp)
        ) {
            Text(
                if (value.isEmpty()) "Search all" else value,
                color = if (value.isEmpty()) Dim else Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onGo(); editing = false }, onDone = { onGo(); editing = false }),
            modifier = modifier.focusRequester(tf)
                .onFocusChanged {
                    if (it.isFocused) got = true else if (got) editing = false
                }
                .onPreviewKeyEvent { e ->
                    val k = e.key
                    if (e.type == KeyEventType.KeyUp && (k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter)) {
                        showKeyboard()
                        true
                    } else false
                },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().clip(shape).background(KeyBg)
                        .border(2.dp, Color.White, shape).padding(14.dp)
                ) { inner() }
            }
        )
    }
}

@Composable
fun Btn(
    text: String, modifier: Modifier = Modifier, fill: Color = Red, textColor: Color = Color.White,
    fontSize: TextUnit = 20.sp, hPad: Dp = 28.dp, onClick: () -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.05f else 1f, tween(100), label = "s")
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.graphicsLayer { scaleX = s; scaleY = s }.clip(shape).background(fill)
            .border(3.dp, if (f) Color.White else Color.Transparent, shape)
            .onFocusChanged { f = it.hasFocus }.clickable(onClick = onClick).padding(horizontal = hPad, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = textColor, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
fun NavTab(text: String, selected: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(if (f) Color.White else Color.Transparent)
            .onFocusChanged { f = it.hasFocus }.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text, fontSize = 18.sp, fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Normal,
            color = if (f) Color.Black else if (selected) Color.White else Dim, maxLines = 1
        )
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Text(
        text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(color).padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

// ---------------- PIN + action dialogs ----------------

@Composable
fun PinDialog(create: Boolean, title: String, onSubmit: (String) -> String?, onDismiss: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val fr = remember { FocusRequester() }

    fun push(d: String) {
        if (entry.length >= 4) return
        entry += d
        if (entry.length == 4) {
            val p = entry
            if (create) {
                val f = first
                if (f == null) { first = p; entry = ""; msg = null }
                else if (f == p) { onSubmit(p) }
                else { first = null; entry = ""; msg = "PINs didn't match. Try again." }
            } else {
                msg = onSubmit(p)
                entry = ""
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(360.dp).glass(10.dp).padding(28.dp)
                .safeDpad()
                .onPreviewKeyEvent { e ->
                    val c = e.utf16CodePoint
                    if (e.type == KeyEventType.KeyDown && c in '0'.code..'9'.code) { push((c - '0'.code).toString()); true } else false
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (create) (if (first == null) title else "Confirm your PIN") else title,
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { i -> Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < entry.length) Color.White else Color(0xFF555555))) }
            }
            Text(msg ?: " ", color = Color(0xFFE87C03), fontSize = 14.sp)
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Del", "0", "Cancel")
            keys.chunked(3).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { c, k ->
                        Btn(
                            k, Modifier.width(92.dp).then(if (r == 0 && c == 0) Modifier.focusRequester(fr) else Modifier),
                            fill = GlassBtn, fontSize = if (k.length > 1) 16.sp else 22.sp, hPad = 4.dp
                        ) {
                            when (k) { "Del" -> entry = entry.dropLast(1); "Cancel" -> onDismiss(); else -> push(k) }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { fr.grab() }
        }
    }
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = s % 3600 / 60
    val x = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
}

@Composable
fun ResumeDialog(vm: Vm, r: ResumeReq) {
    val fr = remember { FocusRequester() }
    val cur = r.list.getOrNull(r.index)
    val title = cur?.name ?: ""
    val noun = if (cur?.type == T_EP) "episode" else "movie"
    val at = formatTime(r.posMs)
    Dialog(onDismissRequest = { vm.cancelResume() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(500.dp).glass(14.dp).padding(32.dp).safeDpad(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("RESUME PLAYBACK", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "You previously watched this $noun up to $at. Would you like to continue from where you stopped, or start over from the beginning?",
                color = Dim, fontSize = 15.sp
            )
            Spacer(Modifier.height(4.dp))
            Btn("Resume from $at", Modifier.fillMaxWidth().focusRequester(fr)) { vm.chooseResume(r, false) }
            Btn("Start from Beginning", Modifier.fillMaxWidth(), fill = GlassBtn) { vm.chooseResume(r, true) }
            Btn("Cancel", Modifier.fillMaxWidth(), fill = Color.White.copy(.07f), fontSize = 18.sp) { vm.cancelResume() }
            LaunchedEffect(Unit) { fr.grab() }
        }
    }
}

@Composable
fun ExitDialog(vm: Vm, onExit: () -> Unit) {
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = { vm.exitAsk = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(460.dp).glass(14.dp).padding(32.dp).safeDpad(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(painter = painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(72.dp))
            Text("Exit Shuja TV v2", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "Are you sure you want to exit the application? Your viewing progress has been saved.",
                color = Dim, fontSize = 16.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Btn("Cancel", Modifier.focusRequester(fr), fill = GlassBtn, hPad = 36.dp) { vm.exitAsk = false }
                Btn("Exit", hPad = 44.dp) {
                    vm.exitAsk = false
                    onExit()
                }
            }
            LaunchedEffect(Unit) { fr.grab() }
        }
    }
}

@Composable
fun MenuDialog(vm: Vm, m: MenuTarget) {
    val fr = remember { FocusRequester() }

    val ik = vm.itemKey(m.item)
    val ck = vm.catKey(m.item.type, m.catId)
    val noun = when (m.item.type) {
        T_LIVE -> "channel"
        T_MOVIE -> "movie"
        else -> "series"
    }
    Dialog(onDismissRequest = { vm.closeMenu() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(440.dp).glass(10.dp).padding(28.dp).safeDpad(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(m.item.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Btn(if (ik in vm.favKeys) "Remove from favorites" else "Add to favorites", Modifier.fillMaxWidth().focusRequester(fr), fill = GlassBtn) { vm.toggleFav(m.item) }
            Btn(if (ck in vm.pinKeys) "Unpin group: ${m.catName}" else "Pin group to top: ${m.catName}", Modifier.fillMaxWidth(), fill = GlassBtn, fontSize = 18.sp) { vm.togglePin(ck) }
            Btn(if (ik in vm.lockedKeys) "Unlock $noun (PIN)" else "Lock $noun", Modifier.fillMaxWidth(), fill = GlassBtn) { vm.toggleLock(ik) }
            Btn(if (ik in vm.hiddenKeys) "Unhide $noun" else "Hide $noun", Modifier.fillMaxWidth(), fill = GlassBtn) { vm.toggleHide(ik) }
            Btn(if (ck in vm.lockedKeys) "Unlock category: ${m.catName}" else "Lock category: ${m.catName}", Modifier.fillMaxWidth(), fill = GlassBtn, fontSize = 18.sp) { vm.toggleLock(ck) }
            Btn(if (ck in vm.hiddenKeys) "Unhide category: ${m.catName}" else "Hide category: ${m.catName}", Modifier.fillMaxWidth(), fill = GlassBtn, fontSize = 18.sp) { vm.toggleHide(ck) }
            Btn("Close", Modifier.fillMaxWidth(), fill = Color.White.copy(.07f)) { vm.closeMenu() }
            LaunchedEffect(Unit) { fr.grab() }
        }
    }
}

@Composable
fun GroupMenuDialog(vm: Vm, g: GroupTarget) {
    val fr = remember { FocusRequester() }
    val key = vm.catKey(g.type, g.catId)
    val pinned = key in vm.pinKeys
    Dialog(onDismissRequest = { vm.closeGroupMenu() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(440.dp).glass(16.dp).padding(28.dp).safeDpad(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("GROUP", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text(g.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (pinned) "This group is pinned and appears at the top of the list."
                else "Pin this group to keep it at the top, so you never need to scroll to find it. You can pin as many groups as you like.",
                color = Dim, fontSize = 15.sp
            )
            Spacer(Modifier.height(2.dp))
            Btn(if (pinned) "Unpin group" else "Pin group to top", Modifier.fillMaxWidth().focusRequester(fr)) { vm.togglePin(key) }
            Btn("Close", Modifier.fillMaxWidth(), fill = Color.White.copy(.07f), fontSize = 18.sp) { vm.closeGroupMenu() }
            LaunchedEffect(Unit) { fr.grab() }
        }
    }
}

/** Small touch button beside a group title. */
@Composable
fun PinPill(pinned: Boolean, onClick: () -> Unit) {
    Text(
        if (pinned) "Unpin" else "Pin to top", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(.14f))
            .border(1.dp, Color.White.copy(.25f), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

// ---------------- screens ----------------

/** True on Android TV / set-top boxes (remote control), false on phones and tablets (touch). */
@Composable
fun rememberIsTv(): Boolean {
    val c = LocalContext.current
    return remember { c.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
}

@Composable
fun TopBar(vm: Vm) {
    val fm = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().background(Bg).padding(horizontal = 48.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("SHUJA TV", color = Red, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Spacer(Modifier.width(16.dp))
        NavTab("Live TV", vm.tab == 0) { vm.selectTab(0) }
        NavTab("Movies", vm.tab == 1) { vm.selectTab(1) }
        NavTab("Series", vm.tab == 2) { vm.selectTab(2) }
        Spacer(Modifier.weight(1f))
        NavTab("Settings", false) { vm.settingsOpen = true }
        Spacer(Modifier.width(8.dp))
        SearchBox(vm.query, { vm.query = it }, Modifier.width(260.dp)) { fm.moveFocus(FocusDirection.Down) }
    }
}

@Composable
fun Home(vm: Vm) {
    val tab = vm.tab
    val cfg = vm.cfg
    val q = vm.query
    val lib = vm.libs[tab]
    LaunchedEffect(tab) { vm.ensure(tab) }
    BackHandler(enabled = q.isNotEmpty()) { vm.query = "" }
    BackHandler(enabled = q.isEmpty() && !vm.exitAsk) { vm.exitAsk = true }

    val fm = LocalFocusManager.current
    val contentFr = remember { FocusRequester() }
    var pendingFocus by remember { mutableStateOf(false) }
    val lastNav = remember { longArrayOf(0L) }
    // After a tab change by the edge keys, focus goes into the new list as soon as it is ready.
    LaunchedEffect(tab, lib != null) {
        if (pendingFocus && lib != null) {
            repeat(30) {
                delay(100)
                val ok = try { contentFr.requestFocus(); true } catch (e: Exception) { false }
                if (ok) { pendingFocus = false; return@LaunchedEffect }
            }
            pendingFocus = false
        }
    }

    var res by remember { mutableStateOf<SearchRes?>(null) }
    val libCount = vm.libs.size
    LaunchedEffect(q, libCount, vm.hiddenKeys, vm.unlocked, cfg.hideAdult) {
        if (q.isBlank()) {
            res = null
        } else {
            delay(300)
            res = withContext(Dispatchers.Default) { vm.search(q) }
        }
    }

    val rows = remember(lib, vm.hiddenKeys, vm.unlocked, cfg.hideAdult, cfg.sort) {
        if (lib == null) emptyList<Pair<Category, List<Item>>>() else vm.rowsFor(lib, tab)
    }
    val favs = remember(lib, vm.favKeys, vm.hiddenKeys, vm.unlocked, cfg.hideAdult) {
        if (lib == null) emptyList<Item>() else vm.favItems(lib)
    }
    val cont = remember(vm.hist, tab, cfg.contRow, vm.hiddenKeys, vm.unlocked) { vm.contFor(tab) }
    val recent = remember(lib, tab, cfg.recentRow, vm.hiddenKeys, vm.unlocked, cfg.hideAdult) {
        if (lib == null || tab == T_LIVE) emptyList<Item>() else vm.recentFor(lib)
    }
    val pins = vm.pinKeys
    val sections = remember(cont, recent, favs, rows, pins) {
        val out = ArrayList<Section>()
        if (cont.isNotEmpty()) out.add(Section("Continue Watching", cont.map { vm.contItem(it) }, cont))
        // Pinned groups come first, right after Continue Watching.
        for ((c, l) in rows) if (vm.catKey(tab, c.id) in pins) out.add(Section(c.name, l, null, true, c.id))
        if (recent.isNotEmpty()) out.add(Section("Recently Added", recent))
        if (favs.isNotEmpty()) out.add(Section("Favorites", favs))
        for ((c, l) in rows) if (vm.catKey(tab, c.id) !in pins) out.add(Section(c.name, l, null, false, c.id))
        out
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(vm)
        Box(
            Modifier.weight(1f).fillMaxWidth().focusRequester(contentFr).focusGroup()
                // A held D-pad key repeats faster than the list can follow; skipping some repeats keeps scrolling smooth.
                .onPreviewKeyEvent { e ->
                    val k = e.key
                    val nav = k == Key.DirectionUp || k == Key.DirectionDown || k == Key.DirectionLeft || k == Key.DirectionRight
                    if (nav && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0) {
                        val t = SystemClock.uptimeMillis()
                        if (t - lastNav[0] < 90L) true else { lastNav[0] = t; false }
                    } else false
                }
                // Left at the far left edge opens the previous tab, Right at the far right edge opens the next one.
                .onKeyEvent { e ->
                    val dir = when (e.key) {
                        Key.DirectionLeft -> FocusDirection.Left
                        Key.DirectionRight -> FocusDirection.Right
                        else -> null
                    }
                    if (dir == null || e.type != KeyEventType.KeyDown || vm.query.isNotBlank()) {
                        false
                    } else {
                        val moved = try { fm.moveFocus(dir) } catch (ex: IllegalStateException) { true }
                        if (!moved && e.nativeKeyEvent.repeatCount == 0) {
                            val nt = vm.tab + (if (dir == FocusDirection.Right) 1 else -1)
                            if (nt in 0..2) { pendingFocus = true; vm.selectTab(nt) }
                        }
                        true
                    }
                }
        ) {
            if (q.isNotBlank()) {
                SearchResults(vm, res, q)
            } else if (lib != null) {
                key(tab, cfg.layout) {
                    if (cfg.layout == 1) GridLayout(vm, sections)
                    else Shelves(vm, sections, rows.firstOrNull())
                }
            } else {
                val err = vm.errs[tab]
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (err != null) {
                        Text(err, color = Color(0xFFE87C03), fontSize = 18.sp, textAlign = TextAlign.Center)
                        Btn("Retry") { vm.reload(tab) }
                    } else {
                        Text("Loading…", color = Light, fontSize = 22.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun Shelves(vm: Vm, sections: List<Section>, heroSrc: Pair<Category, List<Item>>?) {
    if (sections.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing to show here.", color = Dim, fontSize = 20.sp)
        }
        return
    }
    val heroList = if (vm.cfg.hero) heroSrc else null
    val heroItem = heroList?.second?.firstOrNull { it.icon != null && !vm.isLocked(it) }
    LazyColumn(Modifier.fillMaxSize()) {
        if (heroList != null && heroItem != null) {
            item { Hero(heroItem, heroList.first.name) { vm.open(heroList.second, heroList.second.indexOf(heroItem)) } }
        }
        items(sections) { sec -> ShelfRow(vm, sec.title, sec.items, sec.cont, sec.pinned, sec.catId) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CatRow(title: String, count: Int, selected: Boolean, pinned: Boolean, onMenu: () -> Unit, onFocus: () -> Unit, onClick: () -> Unit) {
    val tv = rememberIsTv()
    var f by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (f) Color.White else if (selected) KeyBg else Color.Transparent)
            .onFocusChanged { f = it.hasFocus; if (it.hasFocus) onFocus() }
            // Remote: hold OK (or Menu key). Touch: press and hold. Both open the pin menu.
            .okHold(onHold = onMenu, onTap = onClick)
            .combinedClickable(onClick = onClick, onLongClick = if (tv) null else onMenu)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = if (f) Color.Black else if (selected) Color.White else Dim, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (pinned) Tag("PIN", Red)
        Text("$count", color = if (f) Color(0xFF333333) else Dim, fontSize = 13.sp, maxLines = 1)
    }
}

/** Category list on the left, poster grid on the right. */
@Composable
fun GridLayout(vm: Vm, sections: List<Section>) {
    if (sections.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing to show here.", color = Dim, fontSize = 20.sp)
        }
        return
    }
    val fm = LocalFocusManager.current
    var sel by remember { mutableIntStateOf(0) }
    val cur = sel.coerceIn(0, sections.lastIndex)
    val sec = sections[cur]
    val live = vm.tab == T_LIVE
    val k = when (vm.cfg.cardSize) {
        0 -> 0.85f
        2 -> 1.25f
        else -> 1f
    }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.width(290.dp).fillMaxHeight().padding(start = 32.dp, top = 8.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            itemsIndexed(sections) { i, s ->
                CatRow(
                    s.title, s.items.size, i == cur, s.pinned,
                    { if (s.catId.isNotEmpty()) vm.openGroupMenu(vm.tab, s.catId, s.title) },
                    { sel = i }
                ) { fm.moveFocus(FocusDirection.Right) }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive((if (live) 220.dp else 156.dp) * k),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(start = 16.dp, end = 32.dp, top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            gridItemsIndexed(sec.items) { i, item ->
                PosterCard(vm, item) {
                    val c = sec.cont
                    if (c != null) vm.resumeHist(c[i]) else vm.open(sec.items, i)
                }
            }
        }
    }
}

@Composable
fun SearchResults(vm: Vm, res: SearchRes?, q: String) {
    if (res == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Searching…", color = Light, fontSize = 22.sp) }
        return
    }
    val total = res.live.size + res.movies.size + res.series.size
    if (total == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (vm.loadingTypes.isNotEmpty()) "No matches yet. Still loading content…" else "No results for \"$q\"",
                color = Dim, fontSize = 20.sp
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (res.live.isNotEmpty()) item { ShelfRow(vm, "Live TV  (${res.live.size})", res.live) }
        if (res.movies.isNotEmpty()) item { ShelfRow(vm, "Movies  (${res.movies.size})", res.movies) }
        if (res.series.isNotEmpty()) item { ShelfRow(vm, "Series  (${res.series.size})", res.series) }
        if (vm.loadingTypes.isNotEmpty()) item {
            Text("Still loading more content…", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(start = 48.dp, bottom = 16.dp))
        }
    }
}

@Composable
fun Hero(item: Item, label: String, onPlay: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(260.dp)) {
        if (item.icon != null) {
            AsyncImage(
                model = item.icon, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.5f).padding(24.dp)
            )
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Bg, Bg.copy(.9f), Color.Transparent))))
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 48.dp).fillMaxWidth(.5f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(label.uppercase(), color = Red, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, maxLines = 1)
            Text(item.name, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Btn(if (item.type == T_SERIES) "Open" else "▶  Play", fill = Color.White, textColor = Color.Black, onClick = onPlay)
        }
    }
}

@Composable
fun ShelfRow(vm: Vm, title: String, list: List<Item>, cont: List<Hist>? = null, pinned: Boolean = false, catId: String = "") {
    val tv = rememberIsTv()
    Column {
        Row(
            Modifier.padding(start = 48.dp, end = 48.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, color = Light, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (pinned) Tag("PINNED", Red)
            if (!tv && catId.isNotEmpty()) PinPill(pinned) { vm.togglePin(vm.catKey(vm.tab, catId)) }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(list) { i, item ->
                PosterCard(vm, item) { if (cont != null) vm.resumeHist(cont[i]) else vm.open(list, i) }
            }
        }
    }
}

/** OK = play / open. Hold OK (or the Menu key) opens favorites, lock and hide. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(vm: Vm, item: Item, onClick: () -> Unit) {
    val tv = rememberIsTv()
    val cfg = vm.cfg
    var f by remember { mutableStateOf(false) }
    val live = item.live
    val s by animateFloatAsState(if (f && cfg.anim) 1.08f else 1f, tween(110), label = "s")
    val shape = RoundedCornerShape(6.dp)
    val k = when (cfg.cardSize) {
        0 -> 0.85f
        2 -> 1.25f
        else -> 1f
    }
    val locked = vm.isLocked(item)
    val hidden = vm.isHidden(item)
    val mask = locked && !vm.unlocked
    var broken by remember(item.id) { mutableStateOf(false) }
    val needsPoster = !live && !mask && (item.icon == null || broken)
    val fetched = if (needsPoster) vm.posters[vm.itemKey(item)] else null
    val shown: String? = fetched ?: (if (broken) null else item.icon)
    val showLabel = cfg.titles || shown == null || f
    if (needsPoster && cfg.meta) {
        LaunchedEffect(item.id, broken) { delay(450); vm.ensurePoster(item) }
    }
    val showEpg = live && cfg.epg && !mask
    val prog = if (showEpg) vm.epgMap[item.id]?.now(vm.now) else null
    if (showEpg) {
        LaunchedEffect(f) { if (f) { delay(500); vm.loadEpg(item) } }
    }
    Box(
        Modifier.width((if (live) 200.dp else 140.dp) * k)
            .aspectRatio(if (live) 16f / 9f else 2f / 3f)
            .zIndex(if (f) 1f else 0f).graphicsLayer { scaleX = s; scaleY = s }
            .clip(shape).background(Panel)
            .border(3.dp, if (f) Color.White else Color.Transparent, shape)
            .onFocusChanged { f = it.hasFocus }
            // Remote: hold OK (or Menu key) opens the menu (favorite, lock, hide). Touch: press and hold.
            .okHold(onHold = { vm.openMenu(item) }, onTap = onClick)
            .combinedClickable(onClick = onClick, onLongClick = if (tv) null else ({ vm.openMenu(item) }))
    ) {
        if (mask) {
            Text("LOCKED", color = Red, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, modifier = Modifier.align(Alignment.Center))
        } else if (shown != null) {
            AsyncImage(
                model = shown, contentDescription = null,
                contentScale = if (live) ContentScale.Fit else ContentScale.Crop,
                onError = { broken = true },
                modifier = Modifier.fillMaxSize().then(if (live) Modifier.padding(14.dp) else Modifier)
            )
        } else {
            Text(item.name.take(1).uppercase(), color = Red, fontSize = 36.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Center))
        }
        val fav = vm.favKeys.isNotEmpty() && vm.itemKey(item) in vm.favKeys
        if ((locked && !mask) || hidden || fav) {
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (fav) Tag("FAV", Color(0xFFB8860B))
                if (locked && !mask) Tag("LOCKED", Red)
                if (hidden) Tag("HIDDEN", Color(0xFF555555))
            }
        }
        if (showLabel) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(.72f)).padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(item.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (prog != null) {
                Text(prog.title, color = Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val frac = ((vm.now - prog.start * 1000f) / ((prog.end - prog.start) * 1000f)).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(3.dp).background(Color.White.copy(.25f))) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Red))
                }
            }
        }
    }
}

// ---------------- series ----------------

@Composable
fun EpRow(vm: Vm, ep: Item, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val resume = vm.resumeAt(ep) > 0
    Row(
        modifier.fillMaxWidth().clip(shape).background(if (f) Color.White else Panel)
            .onFocusChanged { f = it.hasFocus }.clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2A2A))) {
            if (ep.icon != null) AsyncImage(model = ep.icon, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(ep.name, color = if (f) Color.Black else Color.White, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (resume) Tag("CONTINUE", Red)
    }
}

@Composable
fun SeriesScreen(vm: Vm) {
    val d = vm.detail ?: return
    val info = vm.detailInfo
    val err = vm.detailErr
    var season by remember(info?.eps) { mutableIntStateOf(info?.eps?.keys?.firstOrNull() ?: 0) }
    val allEps = remember(info?.eps) { info?.eps?.values?.flatten() ?: emptyList<Item>() }
    val fr = remember { FocusRequester() }
    val back = remember { FocusRequester() }
    FullDialog({ vm.closeDetail() }) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Column(Modifier.width(250.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val meta = info?.meta
                val poster: String? = if (meta != null && meta.poster.isNotBlank()) meta.poster else d.icon
                if (poster != null) {
                    AsyncImage(
                        model = poster, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Panel)
                    )
                }
                Text(d.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (meta != null) {
                    val line = listOf(meta.year, meta.genre, if (meta.rating.isNotBlank()) "Rating ${meta.rating}" else "")
                        .filter { it.isNotBlank() }.joinToString("  |  ")
                    if (line.isNotBlank()) Text(line, color = Light, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (meta.plot.isNotBlank()) Text(meta.plot, color = Dim, fontSize = 14.sp, maxLines = 7, overflow = TextOverflow.Ellipsis)
                }
                Btn(if (vm.itemKey(d) in vm.favKeys) "Remove favorite" else "Add favorite", Modifier.fillMaxWidth(), fill = KeyBg, fontSize = 16.sp) { vm.toggleFav(d) }
                Btn("Lock / hide", Modifier.fillMaxWidth(), fill = KeyBg, fontSize = 16.sp) { vm.openMenu(d) }
                Btn("Back", Modifier.fillMaxWidth().focusRequester(back), fill = KeyBg, fontSize = 18.sp) { vm.closeDetail() }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    info == null -> {
                        if (err != null) Text(err, color = Color(0xFFE87C03), fontSize = 18.sp)
                        else Text("Loading episodes…", color = Light, fontSize = 20.sp)
                    }
                    info.eps.isEmpty() -> Text("No episodes found for this series.", color = Dim, fontSize = 18.sp)
                    else -> {
                        val seasons = info.eps.keys.toList()
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(seasons) { sn ->
                                NavTab(if (sn == 0) "Specials" else "Season $sn", sn == season) { season = sn }
                            }
                        }
                        val eps = info.eps[season] ?: emptyList()
                        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(eps) { i, ep ->
                                EpRow(vm, ep, if (i == 0) Modifier.focusRequester(fr) else Modifier) {
                                    vm.play(allEps, allEps.indexOf(ep))
                                }
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(info, season) {
            if (info != null && info.eps.isNotEmpty()) fr.grab() else back.grab()
        }
    }
}

// ---------------- movie details ----------------

@Composable
fun MovieScreen(vm: Vm) {
    val m = vm.movie ?: return
    val meta = vm.movieMeta
    val fr = remember { FocusRequester() }
    val resume = vm.resumeAt(m) > 0
    val fav = vm.itemKey(m) in vm.favKeys
    FullDialog({ vm.closeMovie() }) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp)
        ) {
            val poster: String? = if (meta != null && meta.poster.isNotBlank()) meta.poster else (vm.posters[vm.itemKey(m)] ?: m.icon)
            Box(Modifier.width(260.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Panel)) {
                if (poster != null) {
                    AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(m.name, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (meta == null) {
                    Text("Loading details…", color = Dim, fontSize = 16.sp)
                } else {
                    val line = listOf(meta.year, meta.duration, meta.genre, if (meta.rating.isNotBlank()) "Rating ${meta.rating}" else "")
                        .filter { it.isNotBlank() }.joinToString("  |  ")
                    if (line.isNotBlank()) Text(line, color = Light, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (meta.plot.isNotBlank()) {
                        Text(meta.plot, color = Dim, fontSize = 16.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text("No description available.", color = Dim, fontSize = 16.sp)
                    }
                    if (meta.cast.isNotBlank()) Text("Cast: ${meta.cast}", color = Dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Btn(if (resume) "Continue" else "Play", Modifier.focusRequester(fr), fill = Color.White, textColor = Color.Black) { vm.play(listOf(m), 0) }
                    Btn(if (fav) "Remove favorite" else "Add favorite", fill = KeyBg, fontSize = 18.sp) { vm.toggleFav(m) }
                    Btn("Lock / hide", fill = KeyBg, fontSize = 18.sp) { vm.openMenu(m) }
                    Btn("Back", fill = KeyBg, fontSize = 18.sp) { vm.closeMovie() }
                }
            }
        }
        LaunchedEffect(Unit) { fr.grab() }
    }
}

// ---------------- start-up screen ----------------

@Composable
fun Intro(vm: Vm) {
    val tab = vm.tab
    val kinds = listOf(T_LIVE to "Live TV", T_MOVIE to "Movies", T_SERIES to "Series")
    fun st(t: Int): Int = when {
        vm.libs.containsKey(t) -> 2
        vm.errs.containsKey(t) -> 3
        vm.loadingTypes.contains(t) -> 1
        else -> 0
    }
    val signedIn = vm.account != null
    val done = kinds.count { st(it.first) >= 2 }
    val ready = signedIn && st(tab) >= 2
    var minDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1800); minDone = true }
    val tabFailed = signedIn && st(tab) == 3
    LaunchedEffect(done, ready, minDone, signedIn, tabFailed) {
        if (signedIn && minDone) {
            if (done == 3) {
                delay(600)
                vm.finishIntro()
            } else if (ready) {
                delay(8000)
                vm.finishIntro()
            } else if (tabFailed) {
                // No network yet or server busy: go to the lists, which show a Retry button. Never back to Sign In.
                delay(2500)
                vm.finishIntro()
            }
        }
    }
    val frac by animateFloatAsState(done / 3f, tween(400), label = "frac")
    val inf = rememberInfiniteTransition(label = "pulse")
    val pulse by inf.animateFloat(0.94f, 1.06f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "pp")
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.grab() }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF3A0F2E), Color(0xFF150A12), Color.Black)))
            .focusRequester(fr).focusable()
            .pointerInput(ready) { detectTapGestures { if (ready) vm.finishIntro() } }
            .onKeyEvent { e ->
                val k = e.key
                if (e.type == KeyEventType.KeyUp && ready && (k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter)) {
                    vm.finishIntro()
                    true
                } else false
            }
    ) {
        Column(
            Modifier.align(Alignment.Center).width(480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.logo), contentDescription = null,
                modifier = Modifier.size(132.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Shuja", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black)
                Text(
                    "TV", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Red).padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
            Text("WATCH   •   ENJOY   •   MORE", color = Dim, fontSize = 14.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(18.dp))
            Text(
                if (!signedIn) "Signing in…" else if (done < 3) "Updating your content" else "All up to date",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((t, label) in kinds) {
                    val state = if (signedIn) st(t) else 0
                    val dot = when (state) {
                        1 -> Color(0xFFE87C03)
                        2 -> Color(0xFF46D369)
                        3 -> Red
                        else -> Color(0xFF555555)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
                        Text(label, color = Light, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        val n = vm.libs[t]?.all?.size
                        Text(
                            when (state) {
                                0 -> "Waiting"
                                1 -> "Updating…"
                                2 -> if (n != null) "%,d".format(n) else "Done"
                                else -> "Could not update"
                            },
                            color = Dim, fontSize = 16.sp
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(.15f))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Red))
            }
            Text(
                if (ready && done < 3) "Press OK or tap to continue now" else " ",
                color = Dim, fontSize = 14.sp
            )
        }
    }
}

// ---------------- settings ----------------

@Composable
fun SectionTitle(title: String) {
    Text(
        title.uppercase(), color = Color(0xFFFF6A3D), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp, start = 4.dp)
    )
}

@Composable
fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (f) Color.White else Color.White.copy(alpha = 0.07f))
            .onFocusChanged { f = it.hasFocus }.clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(label, color = if (f) Color.Black else Color.White, fontSize = 18.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(value, color = if (f) Color(0xFF333333) else Dim, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun onOff(b: Boolean) = if (b) "On" else "Off"
private val PREVIEW_MODES = listOf("Off", "On", "On (even with 1 connection)")
private fun pick(list: List<String>, i: Int) = list[i.coerceIn(0, list.lastIndex)]

private val FORMATS = listOf("HLS (m3u8)", "MPEG-TS")
private val QUALITY = listOf("Auto", "Up to 1080p", "Up to 720p")
private val BUFFERS = listOf("Small (fast zapping)", "Medium", "Large (stable)")
private val ASPECTS = listOf("Fit", "Fill", "Zoom")
private val AGENTS = listOf("Default", "VLC", "Chrome", "IPTV Smarters")
private val STARTS = listOf("Live TV", "Movies", "Series")
private val SORTS = listOf("Provider order", "A to Z")
private val LAYOUTS = listOf("Rows", "Category list and grid")
private val CARDS = listOf("Small", "Medium", "Large")

@Composable
fun SettingsScreen(vm: Vm) {
    val c = vm.cfg
    val ctx = LocalContext.current
    var showLog by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    fun toast(s: String) { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show() }

    FullDialog({ vm.settingsOpen = false }) {
        var showSources by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0xFF3A0F2E), Color(0xFF150A12), Color.Black)))
        ) {
            // Left: the app logo, centred, like a professional TV app.
            Column(
                Modifier.weight(0.36f).fillMaxHeight().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(painter = painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(170.dp))
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Shuja", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black)
                    Text(
                        "TV", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Red).padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("WATCH   •   ENJOY   •   MORE", color = Dim, fontSize = 12.sp, letterSpacing = 3.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Text("Version $APP_VERSION", color = Dim, fontSize = 14.sp)
                Spacer(Modifier.height(28.dp))
                Text(
                    "Tip: hold OK on any channel, movie or series for options (favorite, lock, hide).",
                    color = Dim.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center
                )
            }
            // Right: the settings list on a soft glass panel.
            Column(Modifier.weight(0.64f).fillMaxHeight().padding(top = 24.dp, bottom = 24.dp, end = 48.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Btn("Close", Modifier.focusRequester(fr), fill = GlassBtn, fontSize = 18.sp) { vm.settingsOpen = false }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { SectionTitle("Sources") }
                item { SettingRow("Sources & repos", "${Sources.all(ctx).count { it.on }} on  >") { showSources = true } }
                item { SettingRow("Refresh all content", "Reload lists") { vm.refreshAll(); toast("Reloading content") } }
                item {
                    var hide by remember { mutableStateOf(Health.enabled(ctx)) }
                    SettingRow("Hide dead channels", if (hide) "On" else "Off") { hide = !hide; vm.setHideDead(hide) }
                }
                item { SettingRow("Test all channels now", vm.healthNote.ifBlank { "Start" }) { vm.checkChannels(true); toast("Testing channels in the background") } }

                item { SectionTitle("Playback") }
                item { SettingRow("Live stream format", pick(FORMATS, c.liveFormat)) { vm.updateCfg(c.copy(liveFormat = (c.liveFormat + 1) % FORMATS.size)) } }
                item { SettingRow("Max video quality", pick(QUALITY, c.quality)) { vm.updateCfg(c.copy(quality = (c.quality + 1) % QUALITY.size)) } }
                item { SettingRow("Buffer size", pick(BUFFERS, c.buffer)) { vm.updateCfg(c.copy(buffer = (c.buffer + 1) % BUFFERS.size)) } }
                item { SettingRow("Aspect ratio", pick(ASPECTS, c.aspect)) { vm.updateCfg(c.copy(aspect = (c.aspect + 1) % ASPECTS.size)) } }
                item { SettingRow("Audio language", pick(AUDIO_NAMES, c.audio)) { vm.updateCfg(c.copy(audio = (c.audio + 1) % AUDIO_NAMES.size)) } }
                item { SettingRow("Subtitles", onOff(c.subs)) { vm.updateCfg(c.copy(subs = !c.subs)) } }
                item { SettingRow("Skip forward (right button)", "${SEEK_STEPS[c.seekF]} seconds") { vm.updateCfg(c.copy(seekF = (c.seekF + 1) % SEEK_STEPS.size)) } }
                item { SettingRow("Skip back (left button)", "${SEEK_STEPS[c.seekB]} seconds") { vm.updateCfg(c.copy(seekB = (c.seekB + 1) % SEEK_STEPS.size)) } }
                item { SettingRow("Live seek preview (Netflix style)", PREVIEW_MODES[c.previewMode.coerceIn(0, 2)]) { vm.updateCfg(c.copy(previewMode = (c.previewMode + 1) % PREVIEW_MODES.size)) } }
                item { SettingRow("Resume where you left off", onOff(c.resume)) { vm.updateCfg(c.copy(resume = !c.resume)) } }
                item { SettingRow("Autoplay next episode", onOff(c.autoNext)) { vm.updateCfg(c.copy(autoNext = !c.autoNext)) } }
                item { SettingRow("Connect as (user agent)", pick(AGENTS, c.ua)) { vm.updateCfg(c.copy(ua = (c.ua + 1) % AGENTS.size)) } }

                item { SectionTitle("Layout") }
                item { SettingRow("Home layout", pick(LAYOUTS, c.layout)) { vm.updateCfg(c.copy(layout = (c.layout + 1) % LAYOUTS.size)) } }
                item { SettingRow("Card size", pick(CARDS, c.cardSize)) { vm.updateCfg(c.copy(cardSize = (c.cardSize + 1) % CARDS.size)) } }
                item { SettingRow("Card titles", if (c.titles) "Always" else "Only when focused") { vm.updateCfg(c.copy(titles = !c.titles)) } }
                item { SettingRow("Continue watching row", onOff(c.contRow)) { vm.updateCfg(c.copy(contRow = !c.contRow)) } }
                item { SettingRow("Recently added row", onOff(c.recentRow)) { vm.updateCfg(c.copy(recentRow = !c.recentRow)) } }
                item { SettingRow("Find missing posters and details online", onOff(c.meta)) { vm.updateCfg(c.copy(meta = !c.meta)) } }
                item { SectionTitle("Interface") }
                item { SettingRow("Start on", pick(STARTS, c.startTab)) { vm.updateCfg(c.copy(startTab = (c.startTab + 1) % STARTS.size)) } }
                item { SettingRow("Sort lists", pick(SORTS, c.sort)) { vm.updateCfg(c.copy(sort = (c.sort + 1) % SORTS.size)) } }
                item { SettingRow("Banner at top of lists", onOff(c.hero)) { vm.updateCfg(c.copy(hero = !c.hero)) } }
                item { SettingRow("Programme guide on channels", onOff(c.epg)) { vm.updateCfg(c.copy(epg = !c.epg)) } }
                item { SettingRow("Focus zoom animation", onOff(c.anim)) { vm.updateCfg(c.copy(anim = !c.anim)) } }
                item { SettingRow("Hide adult categories", onOff(c.hideAdult)) { vm.updateCfg(c.copy(hideAdult = !c.hideAdult)) } }

                item { SectionTitle("Parental control and favorites") }
                item { SettingRow("Locked and hidden items", if (vm.unlocked) "Unlocked (tap to lock)" else "Locked (tap to unlock)") { vm.toggleUnlock() } }
                item { SettingRow("Change PIN", "") { vm.changePin() } }
                item { SettingRow("Remove all locks", "${vm.lockedKeys.size} locked") { vm.clearLocks() } }
                item { SettingRow("Unhide everything", "${vm.hiddenKeys.size} hidden") { vm.clearHidden() } }
                item { SettingRow("Clear favorites", "${vm.favKeys.size} saved") { vm.clearFavs(); toast("Favorites cleared") } }
                item { SettingRow("Unpin all groups", "${vm.pinKeys.size} pinned") { vm.clearPins(); toast("Pinned groups cleared") } }

                item { SectionTitle("Storage and diagnostics") }
                item {
                    SettingRow("Clear image cache", "") {
                        try {
                            val l = Coil.imageLoader(ctx)
                            l.memoryCache?.clear()
                            l.diskCache?.clear()
                            toast("Image cache cleared")
                        } catch (e: Exception) { toast("Could not clear cache") }
                    }
                }
                item { SettingRow("Clear resume history", "") { vm.clearResume(); toast("Resume history cleared") } }
                item { SettingRow("Last crash log", "View") { showLog = true } }
                item {
                    val rt = Runtime.getRuntime()
                    val used = (rt.totalMemory() - rt.freeMemory()) / 1048576
                    val max = rt.maxMemory() / 1048576
                    SettingRow("About", "Shuja TV v$APP_VERSION  |  ${Build.MODEL}  |  Android ${Build.VERSION.RELEASE}  |  ${used}/${max} MB") {}
                }
            }
            }
        }
        LaunchedEffect(Unit) { fr.grab() }
        if (showSources) SourcesScreen(vm) { showSources = false }
        if (showLog) {
            val log = ctx.getSharedPreferences("nova_crash", 0).getString("log", null) ?: "No crash recorded."
            FullDialog({ showLog = false }) {
                Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Last crash log", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(log, color = Dim, fontSize = 13.sp, maxLines = 28, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Btn("Close", fill = KeyBg) { showLog = false }
                }
            }
        }
    }
}


private val KIND_NAMES = listOf("Auto-detect", "Live TV", "Movies", "Series")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourceRow(s: Src, onTap: () -> Unit, onHold: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (f) Color.White else Color.White.copy(alpha = 0.07f))
            .onFocusChanged { f = it.hasFocus }
            .okHold(onHold = onHold, onTap = onTap)
            .combinedClickable(onClick = onTap, onLongClick = onHold)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            if (s.on) "ON" else "OFF",
            color = if (s.on) Color(0xFF3DDC84) else Color(0xFFE87C03), fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(40.dp)
        )
        Text(s.name, color = if (f) Color.Black else Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            if (s.builtin) "built-in" else "yours  |  " + KIND_NAMES[s.kind.coerceIn(0, 3)],
            color = if (f) Color(0xFF333333) else Dim, fontSize = 14.sp, maxLines = 1
        )
    }
}

/** Turn built-in sources on or off, and add your own playlists or repos (they are loaded together with the built-in ones). */
@Composable
fun SourcesScreen(vm: Vm, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Sources.all(ctx)) }
    var changed by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var kind by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    fun refresh() { list = Sources.all(ctx); changed = true }
    fun close() {
        if (changed) {
            vm.refreshAll()
            Toast.makeText(ctx, "Reloading with your new sources", Toast.LENGTH_SHORT).show()
        }
        onClose()
    }
    FullDialog({ close() }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Sources & repos", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Btn("Done", Modifier.focusRequester(fr), fill = GlassBtn, fontSize = 18.sp) { close() }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { SectionTitle("Add your own repo or playlist") }
                item { Field(name, { name = it }, "Name (optional)") }
                item {
                    Field(
                        url, { url = it }, "Link: M3U playlist, GitHub repo or file, sources.json, or archive:search words",
                        ime = ImeAction.Done
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("Type: " + KIND_NAMES[kind], fill = KeyBg, fontSize = 17.sp) { kind = (kind + 1) % 4 }
                        Btn("Add", fontSize = 17.sp) {
                            if (url.isBlank()) {
                                msg = "Paste a link first."
                            } else {
                                Sources.addCustom(ctx, name.trim(), url.trim(), kind)
                                name = ""; url = ""; msg = "Added. It loads when you press Done."
                                refresh()
                            }
                        }
                    }
                }
                if (msg.isNotEmpty()) item { Text(msg, color = Color(0xFF3DDC84), fontSize = 15.sp) }
                item {
                    Text(
                        "A repo can be one playlist file, or a sources.json file that lists many playlists. " +
                            "Add it once here and anything you later add to that file shows up after a refresh.",
                        color = Dim, fontSize = 13.sp
                    )
                }
                item { SectionTitle("Sources  (OK = on / off,  hold OK = remove your own)") }
                items(list) { s ->
                    SourceRow(
                        s,
                        onTap = { Sources.toggle(ctx, s); refresh() },
                        onHold = { if (!s.builtin) { Sources.removeCustom(ctx, s.url); refresh() } }
                    )
                }
                item { SettingRow("Switch all built-in sources back on", "") { Sources.resetBuiltins(ctx); refresh() } }
            }
        }
        LaunchedEffect(Unit) { fr.grab() }
    }
}
