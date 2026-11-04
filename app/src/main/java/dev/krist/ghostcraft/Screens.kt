package dev.krist.ghostcraft

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import java.text.DateFormat
import java.util.Date

// Sticker card, the same surface Ghost uses for widgets
@Composable
fun Card(modifier: Modifier = Modifier, radius: Dp = Tokens.roundNormal, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier
            .sticker(p, radius)
            .clip(RoundedCornerShape(radius))
            .background(p.card)
            .then(if (onClick != null) Modifier.tap(onClick = onClick) else Modifier),
    ) { content() }
}

@Composable
fun Chip(text: String, icon: String? = null, accent: Boolean = false) {
    val p = LocalPalette.current
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (accent) p.primaryContainer else p.tile)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (accent) p.onPrimaryContainer else p.onSurfaceVariant
        if (icon != null) {
            Icon(icon, fg, size = 15.sp)
            Spacer(Modifier.width(4.dp))
        }
        GText(text, fg, size = 12.sp, weight = FontWeight.Medium)
    }
}

@Composable
fun InstanceIcon(inst: Instance, size: Dp) {
    val p = LocalPalette.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, inst.id) { value = withContext(Dispatchers.IO) { inst.icon() } }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(Tokens.roundSmall)).background(p.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon("deployed_code", p.onPrimaryContainer, size = (size.value * 0.5f).sp, filled = true)
    }
}

@Composable
fun Header(title: String, onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = Tokens.spaceNormal), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconCircle("arrow_back", p.tile, p.onSurface, size = 44.dp, onClick = onBack)
            Spacer(Modifier.width(Tokens.spaceNormal))
        }
        GText(
            title, p.onSurface, Modifier.weight(1f),
            size = if (onBack == null) 34.sp else 22.sp,
            weight = FontWeight.Bold,
        )
        trailing()
    }
}

@Composable
fun systemPadding() = WindowInsets.systemBars

// ---------- Instance list ----------

@Composable
fun HomeScreen(instances: List<Instance>, onOpen: (Instance) -> Unit, onRuntime: () -> Unit, onCreate: () -> Unit, onReload: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val parts = remember(instances) { Runtime.check(ctx, null) }
    val job by Jobs.state.collectAsStateWithLifecycle()
    val pad = systemPadding().asPaddingValues()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Tokens.spaceLarge, end = Tokens.spaceLarge,
            top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + Tokens.spaceLarge,
        ),
        verticalArrangement = Arrangement.spacedBy(Tokens.spaceNormal + 4.dp),
    ) {
        item {
            Header("GhostCraft") {
                val ready = parts.count { it.ready }
                Row(Modifier.clip(CircleShape).tap(onClick = onRuntime)) {
                    Chip("ядро $ready/${parts.size}", "memory", accent = ready == parts.size)
                }
                Spacer(Modifier.width(Tokens.spaceSmall))
                IconCircle("add", p.primary, p.onPrimary, size = 44.dp, onClick = onCreate)
                Spacer(Modifier.width(Tokens.spaceSmall))
                IconCircle("settings", p.tile, p.onSurface, size = 44.dp, onClick = onRuntime)
            }
        }
        if (job != null) item(key = "job") { JobCard(onReload) }
        if (instances.isEmpty()) item { EmptyCard() }
        var lastGroup: String? = "\u0000"
        for (inst in instances) {
            if (inst.group != lastGroup && inst.group != null) {
                item(key = "g:${inst.group}") {
                    GText(inst.group, p.primary, Modifier.padding(top = Tokens.spaceSmall), size = 13.sp, weight = FontWeight.Bold)
                }
            }
            lastGroup = inst.group
            item(key = inst.id) { InstanceCard(inst) { onOpen(inst) } }
        }
    }
}

@Composable
private fun InstanceCard(inst: Instance, onClick: () -> Unit) {
    val p = LocalPalette.current
    val mods by produceState(-1, inst.id) {
        value = withContext(Dispatchers.IO) { inst.modsDir.list()?.count { it.endsWith(".jar") } ?: 0 }
    }
    Card(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(Tokens.padLarge), verticalAlignment = Alignment.CenterVertically) {
            InstanceIcon(inst, 58.dp)
            Spacer(Modifier.width(Tokens.spaceNormal))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GText(inst.name, p.onSurface, size = 17.sp, weight = FontWeight.SemiBold)
                GText(inst.subtitle, p.onSurfaceVariant, size = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Java ${inst.javaMajor}", "coffee")
                    if (mods > 0) Chip("$mods модов", "extension")
                    if (inst.warnings.isNotEmpty()) Chip("${inst.warnings.size}", "warning")
                }
            }
            Icon("chevron_right", p.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyCard() {
    val p = LocalPalette.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Tokens.padLarge), verticalArrangement = Arrangement.spacedBy(Tokens.spaceSmall)) {
            Icon("sync", p.primary, size = 32.sp)
            GText("Инстансов пока нет", p.onSurface, size = 18.sp, weight = FontWeight.SemiBold)
            GText(
                "Подключи телефон к ПК и отправь сборку из Prism Launcher:",
                p.onSurfaceVariant, maxLines = 3,
            )
            Code("ghostcraft-sync list\nghostcraft-sync push \"NeoForge-1.21.1\"")
        }
    }
}

@Composable
private fun Code(text: String) {
    val p = LocalPalette.current
    GText(
        text, p.onSurface,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.roundSmall)).background(p.surface).padding(Tokens.padNormal),
        size = 13.sp, maxLines = 6,
    )
}

// ---------- Launch core status ----------

@Composable
fun RuntimeCard(inst: Instance?) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val parts = remember(inst?.id) { Runtime.check(ctx, inst) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Tokens.padLarge), verticalArrangement = Arrangement.spacedBy(Tokens.spaceNormal)) {
            GText("Ядро запуска", p.onSurface, size = 17.sp, weight = FontWeight.SemiBold)
            for (part in parts) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (part.ready) "check_circle" else "radio_button_unchecked",
                        if (part.ready) p.primary else p.outline, filled = part.ready,
                    )
                    Spacer(Modifier.width(Tokens.spaceNormal))
                    Column(Modifier.weight(1f)) {
                        GText(part.title, p.onSurface, weight = FontWeight.Medium)
                        GText(part.detail, p.onSurfaceVariant, size = 12.sp, maxLines = 2)
                    }
                }
            }
            if (!parts.all { it.ready }) GText(
                "Java и LWJGL отправляются с ПК: ghostcraft-sync runtime. GLFW и Mesa под Android — следующие этапы (docs/ROADMAP.md).",
                p.onSurfaceVariant, size = 12.sp, maxLines = 4,
            )
        }
    }
}

// Launcher-wide settings: the game process reads them when it starts
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val pad = systemPadding().asPaddingValues()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Tokens.spaceLarge)
            .padding(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + Tokens.spaceLarge),
        verticalArrangement = Arrangement.spacedBy(Tokens.spaceNormal + 4.dp),
    ) {
        Header("Настройки", onBack)
        GameSettingsCard()
        RuntimeCard(null)
    }
}

object Settings {
    // Fractions of the screen the game renders at; the compositor scales the
    // result up for free, and in copy mode fewer pixels cross back to the CPU
    val scales = listOf(0.5f, 0.67f, 0.75f, 1f)
    const val DEFAULT_SCALE = 0.67f
    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("ghostcraft", android.content.Context.MODE_PRIVATE)
    fun nick(ctx: android.content.Context) = prefs(ctx).getString("nick", null)?.takeIf { it.isNotBlank() } ?: "Player"
    fun setNick(ctx: android.content.Context, v: String) = prefs(ctx).edit().putString("nick", v.trim()).apply()
    fun scale(ctx: android.content.Context) = prefs(ctx).getFloat("render_scale", DEFAULT_SCALE)
    fun setScale(ctx: android.content.Context, v: Float) = prefs(ctx).edit().putFloat("render_scale", v).apply()
}

@Composable
private fun GameSettingsCard() {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var nick by rememberSaveable { mutableStateOf(Settings.nick(ctx)) }
    var scale by remember { mutableStateOf(Settings.scale(ctx)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Tokens.padLarge), verticalArrangement = Arrangement.spacedBy(Tokens.spaceNormal)) {
            GText("Игра", p.onSurface, size = 17.sp, weight = FontWeight.SemiBold)
            GText("Ник для одиночной игры", p.onSurfaceVariant, size = 12.sp)
            val valid = nick.matches(Regex("[A-Za-z0-9_]{3,16}"))
            Row(
                Modifier.fillMaxWidth().clip(CircleShape).background(p.tile).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon("person", p.onSurfaceVariant, size = 20.sp)
                Spacer(Modifier.width(Tokens.spaceSmall))
                BasicTextField(
                    nick, {
                        nick = it.take(16)
                        if (nick.matches(Regex("[A-Za-z0-9_]{3,16}"))) Settings.setNick(ctx, nick)
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = p.onSurface, fontFamily = Fonts.ui, fontSize = 14.sp),
                    cursorBrush = SolidColor(p.primary),
                    modifier = Modifier.weight(1f),
                )
            }
            if (!valid) GText("3–16 символов: латиница, цифры, _", p.error, size = 12.sp)

            GText("Масштаб рендера", p.onSurfaceVariant, size = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spaceSmall)) {
                for (s in Settings.scales) {
                    val selected = s == scale
                    val bg by animateColorAsState(if (selected) p.primary else p.tile, label = "scale")
                    Box(
                        Modifier.weight(1f).clip(CircleShape).background(bg)
                            .tap { scale = s; Settings.setScale(ctx, s) }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        GText("${(s * 100).roundToInt()}%", if (selected) p.onPrimary else p.onSurfaceVariant,
                            size = 13.sp, weight = FontWeight.SemiBold)
                    }
                }
            }
            // the game is fullscreen, cutout included
            val bounds = ctx.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            val w = maxOf(bounds.width(), bounds.height())
            val h = minOf(bounds.width(), bounds.height())
            GText(
                "Игра рисует ${(w * scale).roundToInt()}×${(h * scale).roundToInt()} и растягивается на экран. Меньше — больше FPS и меньше нагрев.",
                p.onSurfaceVariant, size = 12.sp, maxLines = 3,
            )
        }
    }
}

// ---------- Instance page ----------

private enum class Tab(val title: String, val icon: String) {
    Mods("Моды", "extension"), Worlds("Миры", "public"), Packs("Ресурспаки", "palette"), Shaders("Шейдеры", "wb_sunny")
}

@Composable
fun InstanceScreen(inst: Instance, onBack: () -> Unit) {
    val p = LocalPalette.current
    var tab by rememberSaveable { mutableStateOf(Tab.Mods) }
    var showCore by rememberSaveable { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    val log = remember(inst.id) { Launch.logFile(inst) }
    var query by rememberSaveable { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    val mods by produceState<List<Mod>?>(null, inst.id, reload) { value = withContext(Dispatchers.IO) { Mod.list(inst) } }
    val worlds by produceState(emptyList<World>(), inst.id) { value = withContext(Dispatchers.IO) { World.list(inst) } }
    val packs by produceState(emptyList<File>(), inst.id) { value = withContext(Dispatchers.IO) { packs(inst, "resourcepacks") } }
    val shaders by produceState(emptyList<File>(), inst.id) { value = withContext(Dispatchers.IO) { packs(inst, "shaderpacks") } }

    val pad = systemPadding().asPaddingValues()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Tokens.spaceLarge, end = Tokens.spaceLarge,
            top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + Tokens.spaceLarge,
        ),
        verticalArrangement = Arrangement.spacedBy(Tokens.spaceNormal),
    ) {
        item { Header(inst.name, onBack) }
        item {
            Hero(
                inst,
                onPlay = { GameActivity.start(ctx, inst) },
                onCore = { showCore = !showCore },
                onLog = if (log.isFile) ({ showLog = !showLog }) else null,
            )
        }
        if (showCore) item { RuntimeCard(inst) }
        if (showLog) item {
            Card(Modifier.fillMaxWidth().height(420.dp)) { LogView(log) }
        }
        if (inst.warnings.isNotEmpty()) item { Warnings(inst.warnings) }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(Tokens.spaceSmall),
            ) {
                for (t in Tab.entries) {
                    val count = when (t) {
                        Tab.Mods -> mods?.size ?: 0
                        Tab.Worlds -> worlds.size
                        Tab.Packs -> packs.size
                        Tab.Shaders -> shaders.size
                    }
                    TabPill(t.title, t.icon, count, t == tab) { tab = t }
                }
            }
        }
        when (tab) {
            Tab.Mods -> {
                item { Search(query) { query = it } }
                val list = mods
                if (list == null) item { Note("Читаю моды…") }
                else {
                    val shown = list.filter { query.isBlank() || it.name.contains(query, true) || it.file.name.contains(query, true) }
                    if (shown.isEmpty()) item { Note(if (list.isEmpty()) "Модов нет" else "Ничего не найдено") }
                    items(shown, key = { it.file.name }) { mod ->
                        ModRow(mod) {
                            mod.toggled()
                            reload++
                        }
                    }
                }
            }
            Tab.Worlds -> {
                if (worlds.isEmpty()) item { Note("Миров нет. Их можно отправить: ghostcraft-sync push --saves") }
                items(worlds, key = { it.dir.name }) { WorldRow(it) }
            }
            Tab.Packs -> {
                if (packs.isEmpty()) item { Note("Ресурспаков нет") }
                items(packs, key = { it.name }) { FileRow(it, "palette") }
            }
            Tab.Shaders -> {
                if (shaders.isEmpty()) item { Note("Шейдеров нет") }
                items(shaders, key = { it.name }) { FileRow(it, "wb_sunny") }
            }
        }
    }
}

@Composable
private fun Hero(inst: Instance, onPlay: () -> Unit, onCore: () -> Unit, onLog: (() -> Unit)?) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val parts = remember(inst.id) { Runtime.check(ctx, inst) }
    val ready = parts.all { it.ready }
    val startable = Runtime.canStart(parts)
    Card(Modifier.fillMaxWidth(), radius = Tokens.roundLarge) {
        Column(Modifier.padding(Tokens.padLarge), verticalArrangement = Arrangement.spacedBy(Tokens.spaceNormal)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InstanceIcon(inst, 72.dp)
                Spacer(Modifier.width(Tokens.spaceNormal))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GText(inst.subtitle, p.onSurface, size = 15.sp, weight = FontWeight.Medium, maxLines = 2)
                    if (inst.syncedAt > 0) GText(
                        "С ПК: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(inst.syncedAt)),
                        p.onSurfaceVariant, size = 12.sp,
                    )
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Java ${inst.javaMajor}", "coffee")
                Chip("${inst.memoryMax / 1024f} ГБ".replace(".0 ", " "), "memory_alt")
                Chip((if (inst.lwjgl2) "LWJGL " else "LWJGL ") + inst.lwjgl, "deployed_code")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spaceSmall)) {
                Row(Modifier.weight(1f)) { PlayButton(ready, startable, onPlay, onCore) }
                if (onLog != null) IconCircle("terminal", p.tile, p.onSurface, size = 54.dp, onClick = onLog)
            }
        }
    }
}

// Without graphics the game still boots far enough to test Java and the jars
@Composable
private fun PlayButton(ready: Boolean, startable: Boolean, onPlay: () -> Unit, onCore: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(CircleShape)
            .background(if (ready) p.primary else if (startable) p.primaryContainer else p.tile)
            .tap(onClick = if (startable) onPlay else onCore),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (ready) p.onPrimary else if (startable) p.onPrimaryContainer else p.onSurface
        Icon(if (startable) "play_arrow" else "build", fg, filled = true)
        Spacer(Modifier.width(Tokens.spaceSmall))
        GText(
            when {
                ready -> "Играть"
                startable -> "Запуск без OpenGL"
                else -> "Ядро запуска не готово"
            },
            fg, size = 16.sp, weight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Warnings(list: List<String>) {
    val p = LocalPalette.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Tokens.padLarge), verticalArrangement = Arrangement.spacedBy(Tokens.spaceSmall)) {
            for (w in list) Row {
                Icon("warning", p.error, size = 18.sp)
                Spacer(Modifier.width(Tokens.spaceSmall))
                GText(w, p.onSurface, size = 13.sp, maxLines = 3)
            }
        }
    }
}

@Composable
private fun TabPill(title: String, icon: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val bg by animateColorAsState(if (selected) p.primary else p.tile, label = "tab")
    val fg = if (selected) p.onPrimary else p.onSurfaceVariant
    Row(
        Modifier.clip(CircleShape).background(bg).tap(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, fg, size = 17.sp, filled = selected)
        Spacer(Modifier.width(6.dp))
        GText("$title  $count", fg, size = 13.sp, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun Search(query: String, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(p.tile).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon("search", p.onSurfaceVariant, size = 20.sp)
        Spacer(Modifier.width(Tokens.spaceSmall))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) GText("Поиск модов", p.onSurfaceVariant)
            BasicTextField(
                query, onChange, singleLine = true,
                textStyle = TextStyle(color = p.onSurface, fontFamily = Fonts.ui, fontSize = 14.sp),
                cursorBrush = SolidColor(p.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) Icon("close", p.onSurfaceVariant, Modifier.tap { onChange("") }, size = 20.sp)
    }
}

@Composable
private fun ModRow(mod: Mod, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.roundSmall)).background(p.tile)
            .tap(onClick = onToggle).padding(horizontal = Tokens.padLarge, vertical = Tokens.padNormal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            GText(mod.name, if (mod.enabled) p.onSurface else p.outline, weight = FontWeight.Medium)
            GText(listOfNotNull(mod.version, mod.file.name).joinToString(" · "), p.onSurfaceVariant, size = 11.sp)
        }
        Spacer(Modifier.width(Tokens.spaceSmall))
        Switch(mod.enabled)
    }
}

@Composable
fun Switch(on: Boolean) {
    val p = LocalPalette.current
    val track by animateColorAsState(if (on) p.primary else p.surfaceContainerHighest, label = "track")
    val knob by animateDpAsState(if (on) 20.dp else 0.dp, ghostSpring(), label = "knob")
    Box(Modifier.size(48.dp, 28.dp).clip(CircleShape).background(track).padding(4.dp)) {
        Box(Modifier.offset(x = knob).size(20.dp).clip(CircleShape).background(if (on) p.onPrimary else p.outline))
    }
}

@Composable
private fun WorldRow(world: World) {
    val p = LocalPalette.current
    val icon by produceState<android.graphics.Bitmap?>(null, world.dir) {
        value = withContext(Dispatchers.IO) { world.icon?.let { BitmapFactory.decodeFile(it.path) } }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.roundSmall)).background(p.tile).padding(Tokens.padNormal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(p.primaryContainer), contentAlignment = Alignment.Center) {
            val b = icon
            if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon("public", p.onPrimaryContainer)
        }
        Spacer(Modifier.width(Tokens.spaceNormal))
        Column {
            GText(world.name, p.onSurface, weight = FontWeight.Medium)
            GText(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(world.modified)), p.onSurfaceVariant, size = 12.sp)
        }
    }
}

@Composable
private fun FileRow(file: File, icon: String) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.roundSmall)).background(p.tile).padding(Tokens.padLarge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, p.primary, size = 20.sp)
        Spacer(Modifier.width(Tokens.spaceNormal))
        GText(file.name.removeSuffix(".zip"), p.onSurface, Modifier.weight(1f))
    }
}

@Composable
private fun Note(text: String) {
    val p = LocalPalette.current
    GText(text, p.onSurfaceVariant, Modifier.fillMaxWidth().padding(Tokens.padLarge), maxLines = 3)
}
