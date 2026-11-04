package dev.krist.ghostcraft

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.ime
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.system.Os
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

// Runs in the separate :game process (see AndroidManifest.xml): one JVM per
// process, and a crashing game can't take the launcher down with it.
//
// Until the game opens its window the screen shows the launch log; then the
// GameSurface takes the whole screen and the log moves into a small menu.
class GameActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_ID = "instance"

        fun start(ctx: Context, inst: Instance) =
            ctx.startActivity(Intent(ctx, GameActivity::class.java).putExtra(EXTRA_ID, inst.id))

        // A JVM can be created only once per process, even if the activity is recreated
        private val status = MutableStateFlow("Готовлю запуск…")
        @Volatile private var started = false
    }

    private var surface: GameSurface? = null
    private var ready by mutableStateOf(false)
    private var menu by mutableStateOf(false)
    private var closing = false

    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { pushClipboard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getStringExtra(EXTRA_ID)
        val inst = id?.let { Instance.load(Store.instancesDir(this).resolve(it)) }
        if (inst == null) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Loads our GLFW into this process before the game's JVM looks for it
        val bounds = windowManager.maximumWindowMetrics.bounds
        val dm = resources.displayMetrics
        GlfwBridge.setDisplay(
            maxOf(bounds.width(), bounds.height()), minOf(bounds.width(), bounds.height()),
            display?.refreshRate?.roundToInt() ?: 60, dm.xdpi, dm.ydpi,
        )
        clipboard.addPrimaryClipChangedListener(clipListener)

        if (!started) {
            started = true
            Thread({ run(inst) }, "ghostcraft-game").start()
        }
        val scale = Settings.scale(this)
        val controls = Controls(this, GameOptions(inst.gameDir.resolve("options.txt")))
        // Chat opens a text screen; the keyboard follows once the game has it up
        controls.onChat = { window.decorView.postDelayed({ surface?.showKeyboard() }, 250) }
        setContent {
            val palette = remember { Palette(emptyMap(), isLight = false, sekai = true) }
            val text by status.collectAsStateWithLifecycle()
            // The game owns the cursor mode and the clipboard; follow both
            LaunchedEffect(Unit) {
                while (true) {
                    ready = GlfwBridge.windowReady()
                    surface?.setCursorMode(GlfwBridge.cursorMode())
                    GlfwBridge.takeClipboard()?.let {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Minecraft", String(it, Charsets.UTF_8)))
                    }
                    delay(100)
                }
            }
            CompositionLocalProvider(LocalPalette provides palette) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    // The keyboard slides the game up instead of covering the
                    // chat line; resizing would change the GUI scale
                    val ime = WindowInsets.ime
                    AndroidView(
                        { GameSurface(it, scale).also { s -> s.controls = controls; surface = s } },
                        Modifier.fillMaxSize().offset { IntOffset(0, -ime.getBottom(this)) },
                    )
                    if (ready) ControlsOverlay(controls) { controls.editing = false }
                    when {
                        !ready -> GameScreen(inst, text, opaque = true) { IconCircle("stop", palette.tile, palette.onSurface, size = 44.dp, onClick = ::stop) }
                        menu -> GameScreen(inst, text, opaque = false) {
                            IconCircle("gamepad", palette.tile, palette.onSurface, size = 44.dp) {
                                menu = false
                                controls.startEditing()
                            }
                            Spacer(Modifier.width(Tokens.spaceSmall))
                            IconCircle("keyboard", palette.tile, palette.onSurface, size = 44.dp) {
                                menu = false
                                surface?.toggleKeyboard()
                            }
                            Spacer(Modifier.width(Tokens.spaceSmall))
                            IconCircle("power_settings_new", palette.tile, palette.onSurface, size = 44.dp, onClick = ::stop)
                            Spacer(Modifier.width(Tokens.spaceSmall))
                            IconCircle("close", palette.primary, palette.onPrimary, size = 44.dp) { menu = false }
                        }
                        controls.editing -> Unit
                        else -> IconCircle(
                            "more_horiz", palette.tile.copy(alpha = 0.55f), palette.onSurface, size = 34.dp, iconSize = 18.sp,
                            modifier = Modifier.padding(Tokens.spaceSmall),
                        ) { menu = true }
                    }
                }
            }
        }
    }

    // Asks the game to quit, so it saves the world; a second press kills it
    private fun stop() {
        if (!ready || closing) {
            Process.killProcess(Process.myPid())
            return
        }
        closing = true
        menu = false
        status.value = "Закрываю игру…"
        GlfwBridge.requestClose()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (menu && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) menu = false
            return true
        }
        if (ready && !menu && surface?.key(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        GlfwBridge.focus(true)
    }

    override fun onPause() {
        super.onPause()
        GlfwBridge.focus(false)
    }

    // Reading the clipboard needs focus on Android 10+
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) pushClipboard()
    }

    private fun pushClipboard() {
        val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        if (text != null) GlfwBridge.setClipboard(text.toByteArray(Charsets.UTF_8))
    }

    // Leaving the game screen ends the process: its JVM can't be started again
    override fun onDestroy() {
        super.onDestroy()
        clipboard.removePrimaryClipChangedListener(clipListener)
        if (isFinishing) Process.killProcess(Process.myPid())
    }

    private fun run(inst: Instance) {
        val log = Launch.logFile(inst).apply { parentFile?.mkdirs() }
        try {
            val java = Launch.installJava(this, inst.javaMajor) { status.value = it }
            Launch.installLwjgl(this) { status.value = it }
            val mesa = Launch.installMesa(this) { status.value = it }
            Launch.environment(this, inst, mesa).forEach { (k, v) -> Os.setenv(k, v, true) }
            PhoneIncompatible.disable(inst.modsDir).forEach { log.appendText("[GhostCraft] Отключён: $it\n") }
            val nick = Settings.nick(this)
            val args = Launch.command(this, inst, nick)
            status.value = "Java ${inst.javaMajor} запущена"
            val code = GameBridge.launch(java.path, args.toTypedArray(), inst.gameDir.path, log.path)
            status.value = "Игра завершилась, код $code"
            // a clean exit goes back to the launcher; on errors the log stays
            if (code == 0) runOnUiThread { finish() }
        } catch (e: Throwable) {
            log.appendText("[GhostCraft] ${e.stackTraceToString()}\n")
            status.value = e.message ?: e.toString()
        }
    }
}

@Composable
private fun GameScreen(inst: Instance, status: String, opaque: Boolean, actions: @Composable RowScope.() -> Unit) {
    val p = LocalPalette.current
    val pad = WindowInsets.systemBars.asPaddingValues()
    Column(
        Modifier.fillMaxSize().background(if (opaque) p.surface else p.surface.copy(alpha = 0.88f))
            .padding(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding())
            .padding(horizontal = Tokens.spaceLarge, vertical = if (opaque) 0.dp else Tokens.spaceNormal),
        verticalArrangement = Arrangement.spacedBy(Tokens.spaceNormal),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = Tokens.spaceNormal), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                GText(inst.name, p.onSurface, size = 22.sp, weight = FontWeight.Bold)
                GText(status, p.onSurfaceVariant, size = 13.sp, maxLines = 2)
            }
            Spacer(Modifier.width(Tokens.spaceNormal))
            actions()
        }
        Card(Modifier.fillMaxWidth().weight(1f)) {
            LogView(Launch.logFile(inst))
        }
    }
}

// Tails the launch log: the JVM writes it from native code, so just poll the file
@Composable
fun LogView(file: File, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val lines by produceState(emptyList<String>(), file) {
        var last = -1L
        while (true) {
            val len = file.length()
            if (len != last) {
                last = len
                value = withContext(Dispatchers.IO) { tail(file) }
            }
            delay(400)
        }
    }
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.lastIndex) }
    val style = remember(p) { TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = p.onSurface) }
    LazyColumn(modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(Tokens.padNormal)) {
        itemsIndexed(lines) { _, line ->
            val err = line.contains("Exception") || line.contains("ERROR") || line.startsWith("\tat ") || line.contains("Error:")
            Text(line, style = if (err) style.copy(color = p.primary) else style)
        }
    }
}

private fun tail(file: File, bytes: Long = 192 * 1024): List<String> = runCatching {
    RandomAccessFile(file, "r").use { f ->
        val start = maxOf(0, f.length() - bytes)
        f.seek(start)
        val buf = ByteArray((f.length() - start).toInt())
        f.readFully(buf)
        val lines = String(buf).lines()
        (if (start > 0) lines.drop(1) else lines).dropLastWhile { it.isEmpty() }
    }
}.getOrDefault(emptyList())
