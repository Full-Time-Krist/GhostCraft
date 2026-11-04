package dev.krist.ghostcraft

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// New instance: any game version with any loader, or a Modrinth modpack.
// Landscape: the list on the left, the choice on the right.

private enum class CreateTab(val title: String, val icon: String) {
    Version("Версия", "deployed_code"), Modrinth("Modrinth", "travel_explore")
}

@Composable
fun CreateScreen(onBack: () -> Unit) {
    val pad = systemPadding().asPaddingValues()
    var tab by rememberSaveable { mutableStateOf(CreateTab.Version) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = Tokens.spaceLarge)
            .padding(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + Tokens.spaceNormal),
    ) {
        Header("Новый инстанс", onBack) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spaceSmall)) {
                for (t in CreateTab.entries) Pill(t.title, t.icon, t == tab) { tab = t }
            }
        }
        when (tab) {
            CreateTab.Version -> VersionTab(onBack)
            CreateTab.Modrinth -> ModrinthTab(onBack)
        }
    }
}

@Composable
private fun Pill(title: String, icon: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    val bg by animateColorAsState(if (selected) p.primary else p.tile, label = "pill")
    val fg = when {
        selected -> p.onPrimary
        enabled -> p.onSurfaceVariant
        else -> p.outline
    }
    Row(
        Modifier.clip(CircleShape).background(bg).then(if (enabled) Modifier.tap(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, fg, size = 17.sp, filled = selected)
        Spacer(Modifier.width(6.dp))
        GText(title, fg, size = 13.sp, weight = FontWeight.SemiBold)
    }
}

// Equal-width choices in one pill, like Ghost's segmented toggles
@Composable
private fun Segmented(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(p.tile).padding(4.dp)) {
        titles.forEachIndexed { i, t ->
            val on = i == selected
            val bg by animateColorAsState(if (on) p.primary else p.tile, label = "seg")
            Box(
                Modifier.weight(1f).clip(CircleShape).background(bg).tap { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { GText(t, if (on) p.onPrimary else p.onSurfaceVariant, size = 13.sp, weight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun Field(value: String, hint: String, icon: String, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(p.tile).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, p.onSurfaceVariant, size = 20.sp)
        Spacer(Modifier.width(Tokens.spaceSmall))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) GText(hint, p.onSurfaceVariant)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyle(color = p.onSurface, fontFamily = Fonts.ui, fontSize = 14.sp),
                cursorBrush = SolidColor(p.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) Icon("close", p.onSurfaceVariant, Modifier.tap { onChange("") }, size = 20.sp)
    }
}

@Composable
private fun Row2(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Tokens.spaceNormal + 4.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight()) { left() }
        Box(Modifier.weight(1f).fillMaxHeight()) { right() }
    }
}

@Composable
private fun ListRow(selected: Boolean, enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val bg by animateColorAsState(if (selected) p.primaryContainer else p.tile, label = "row")
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.roundSmall)).background(bg)
            .then(if (enabled) Modifier.tap(onClick = onClick) else Modifier)
            .padding(horizontal = Tokens.padLarge, vertical = Tokens.padNormal),
    ) { content() }
}

@Composable
private fun Loading(text: String) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().padding(Tokens.padLarge), contentAlignment = Alignment.Center) {
        GText(text, p.onSurfaceVariant)
    }
}

// --- any version ---------------------------------------------------------------------

@Composable
private fun VersionTab(onDone: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var snapshots by rememberSaveable { mutableStateOf(false) }
    var mc by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf(LoaderKind.VANILLA) }
    var loaderVersion by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var nameEdited by rememberSaveable { mutableStateOf(false) }

    val versions by produceState<Result<List<MetaVersion>>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { Meta.index(ctx, "net.minecraft") } }
    }
    val loaders by produceState<List<MetaVersion>?>(null, mc, kind) {
        value = null
        val v = mc ?: return@produceState
        value = withContext(Dispatchers.IO) { runCatching { Compat.loaderVersions(ctx, kind, v) }.getOrDefault(emptyList()) }
    }
    val problem by produceState<String?>(null, mc) {
        value = null
        val v = versions?.getOrNull()?.firstOrNull { it.version == mc } ?: return@produceState
        value = withContext(Dispatchers.IO) { Compat.problem(ctx, v) }
    }
    LaunchedEffect(loaders) {
        val l = loaders ?: return@LaunchedEffect
        if (loaderVersion == null || l.none { it.version == loaderVersion })
            loaderVersion = (l.firstOrNull { it.recommended } ?: l.firstOrNull())?.version
    }
    LaunchedEffect(mc, kind) {
        if (!nameEdited && mc != null) name = if (kind == LoaderKind.VANILLA) "Minecraft $mc" else "${kind.title} $mc"
    }

    Row2(
        left = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spaceSmall)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GText("Minecraft", p.onSurface, Modifier.weight(1f), size = 17.sp, weight = FontWeight.SemiBold)
                    Pill("Снапшоты", "science", snapshots) { snapshots = !snapshots }
                }
                val list = versions
                when {
                    list == null -> Loading("Загружаю список версий…")
                    list.isFailure -> Loading("Нет сети: ${list.exceptionOrNull()?.message}")
                    else -> {
                        val shown = list.getOrThrow().filter { snapshots || it.type == "release" }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(shown, key = { it.version }) { v ->
                                val no = Compat.quickProblem(v)
                                ListRow(v.version == mc, enabled = no == null, onClick = { mc = v.version }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        GText(v.version, if (no != null) p.outline else p.onSurface, Modifier.weight(1f), weight = FontWeight.Medium)
                                        GText(
                                            no?.let { "нужна $it" }?.replace("нужна LWJGL 2", "LWJGL 2 — пока нет") ?: v.releaseTime.take(10),
                                            if (no != null) p.outline else p.onSurfaceVariant, size = 12.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        right = {
            Card(Modifier.fillMaxSize()) {
                Column(Modifier.padding(Tokens.padLarge), verticalArrangement = Arrangement.spacedBy(Tokens.spaceNormal)) {
                    Field(name, "Название", "badge") { name = it; nameEdited = true }
                    Segmented(LoaderKind.entries.map { it.title }, LoaderKind.entries.indexOf(kind)) { kind = LoaderKind.entries[it] }
                    if (kind != LoaderKind.VANILLA) {
                        val l = loaders
                        Box(Modifier.weight(1f)) {
                            when {
                                mc == null -> Loading("Выбери версию слева")
                                l == null -> Loading("Ищу версии ${kind.title}…")
                                l.isEmpty() -> Loading("${kind.title} для $mc нет")
                                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(l, key = { it.version }) { v ->
                                        ListRow(v.version == loaderVersion, onClick = { loaderVersion = v.version }) {
                                            Row {
                                                GText(v.version, p.onSurface, Modifier.weight(1f))
                                                if (v.recommended) GText("рекомендуемая", p.primary, size = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else Spacer(Modifier.weight(1f))
                    problem?.let { GText(it, p.error, size = 13.sp, maxLines = 2) }
                    if (kind == LoaderKind.FORGE || kind == LoaderKind.NEOFORGE) GText(
                        "Первый запуск ${kind.title} дольше: установщик собирает игру на телефоне",
                        p.onSurfaceVariant, size = 12.sp, maxLines = 2,
                    )
                    val ready = mc != null && problem == null && name.isNotBlank() &&
                        (kind == LoaderKind.VANILLA || loaderVersion != null)
                    BigButton("Создать", "add", ready) {
                        val comps = Recipes.components(mc!!, kind, loaderVersion)
                        val title = name.trim()
                        Jobs.start(title) { Recipes.install(ctx, this, comps, title) }
                        onDone()
                    }
                }
            }
        },
    )
}

@Composable
fun BigButton(text: String, icon: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(CircleShape)
            .background(if (enabled) p.primary else p.tile)
            .then(if (enabled) Modifier.tap(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (enabled) p.onPrimary else p.outline
        Icon(icon, fg, filled = true)
        Spacer(Modifier.width(Tokens.spaceSmall))
        GText(text, fg, size = 16.sp, weight = FontWeight.SemiBold)
    }
}

// --- Modrinth ------------------------------------------------------------------------

@Composable
private fun ModrinthTab(onDone: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Modrinth.Pack?>(null) }
    var chosen by remember { mutableStateOf<Modrinth.PackVersion?>(null) }

    val results by produceState<Result<List<Modrinth.Pack>>?>(null, query) {
        delay(350)   // typing
        value = withContext(Dispatchers.IO) { runCatching { Modrinth.search(query) } }
    }
    val versions by produceState<Result<List<Modrinth.PackVersion>>?>(null, selected?.id) {
        value = null
        val id = selected?.id ?: return@produceState
        value = withContext(Dispatchers.IO) { runCatching { Modrinth.versions(id) } }
    }
    // game versions that can't start here (Java 25, LWJGL 2), from Prism's index
    val blocked by produceState(emptyMap<String, String>()) {
        value = withContext(Dispatchers.IO) {
            runCatching { Meta.index(ctx, "net.minecraft") }.getOrDefault(emptyList())
                .mapNotNull { v -> Compat.quickProblem(v)?.let { v.version to it } }.toMap()
        }
    }
    fun problemOf(v: Modrinth.PackVersion) = v.gameVersions.firstNotNullOfOrNull { blocked[it] }
    LaunchedEffect(versions, blocked) { chosen = versions?.getOrNull()?.firstOrNull { problemOf(it) == null } }

    Row2(
        left = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spaceSmall)) {
                Field(query, "Поиск сборок на Modrinth", "search") { query = it }
                val r = results
                when {
                    r == null -> Loading("Ищу…")
                    r.isFailure -> Loading("Нет сети: ${r.exceptionOrNull()?.message}")
                    r.getOrThrow().isEmpty() -> Loading("Ничего не нашлось")
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), state = rememberLazyListState()) {
                        items(r.getOrThrow(), key = { it.id }) { pack ->
                            ListRow(pack.id == selected?.id, onClick = { selected = pack }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RemoteIcon(pack.iconUrl, 44)
                                    Spacer(Modifier.width(Tokens.spaceNormal))
                                    Column(Modifier.weight(1f)) {
                                        GText(pack.title, p.onSurface, weight = FontWeight.SemiBold)
                                        GText(pack.description, p.onSurfaceVariant, size = 12.sp)
                                        GText(
                                            listOf(pack.loaders.joinToString(", ") { it.replaceFirstChar(Char::uppercase) }, "${count(pack.downloads)} загрузок")
                                                .filter { it.isNotEmpty() }.joinToString(" · "),
                                            p.outline, size = 11.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        right = {
            Card(Modifier.fillMaxSize()) {
                val pack = selected
                if (pack == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        GText("Выбери сборку слева", p.onSurfaceVariant)
                    }
                    return@Card
                }
                Column(Modifier.padding(Tokens.padLarge), verticalArrangement = Arrangement.spacedBy(Tokens.spaceNormal)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RemoteIcon(pack.iconUrl, 56)
                        Spacer(Modifier.width(Tokens.spaceNormal))
                        Column(Modifier.weight(1f)) {
                            GText(pack.title, p.onSurface, size = 18.sp, weight = FontWeight.Bold)
                            GText(pack.description, p.onSurfaceVariant, size = 12.sp)
                        }
                    }
                    val vs = versions
                    Box(Modifier.weight(1f)) {
                        when {
                            vs == null -> Loading("Версии сборки…")
                            vs.isFailure -> Loading("Нет сети: ${vs.exceptionOrNull()?.message}")
                            else -> {
                                val listState = rememberLazyListState()
                                val all = vs.getOrThrow()
                                LaunchedEffect(chosen?.id) {
                                    val i = all.indexOfFirst { it.id == chosen?.id }
                                    if (i >= 0) listState.animateScrollToItem(i)
                                }
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), state = listState) {
                                items(all, key = { it.id }) { v ->
                                    val no = problemOf(v)
                                    ListRow(v.id == chosen?.id, enabled = no == null, onClick = { chosen = v }) {
                                        Column {
                                            GText(v.name.ifEmpty { v.number }, if (no == null) p.onSurface else p.outline, weight = FontWeight.Medium)
                                            GText(
                                                "Minecraft ${v.gameVersions.joinToString()} · ${v.loaders.joinToString { it.replaceFirstChar(Char::uppercase) }} · " +
                                                    (no?.let { "нужна $it" } ?: v.published.take(10)),
                                                p.onSurfaceVariant, size = 12.sp,
                                            )
                                        }
                                    }
                                }
                                }
                            }
                        }
                    }
                    GText(
                        "Большие сборки тяжелы для телефона: ориентир — до 150–200 модов",
                        p.onSurfaceVariant, size = 12.sp,
                    )
                    BigButton(chosen?.let { "Установить ${it.number}" } ?: "Нет версии для телефона", "download", chosen != null) {
                        val v = chosen!!
                        Jobs.start(pack.title) { Modrinth.install(ctx, this, pack, v) }
                        onDone()
                    }
                }
            }
        },
    )
}

private fun count(n: Long) = when {
    n >= 1_000_000 -> "%.1f млн".format(n / 1e6)
    n >= 1_000 -> "${n / 1000} тыс."
    else -> "$n"
}

private val icons = LruCache<String, Bitmap>(64)

@Composable
private fun RemoteIcon(url: String?, size: Int) {
    val p = LocalPalette.current
    val bmp by produceState(url?.let(icons::get), url) {
        if (url == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { Net.open(url).inputStream.use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }?.also { icons.put(url, it) }
    }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(Tokens.roundSmall)).background(p.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon("deployed_code", p.onSurfaceVariant, size = (size * 0.45f).sp)
    }
}

// --- progress on the home screen ---------------------------------------------------------

@Composable
fun JobCard(onFinished: () -> Unit) {
    val p = LocalPalette.current
    val state by Jobs.state.collectAsStateWithLifecycle()
    val s = state ?: return
    LaunchedEffect(s.finished) { if (s.finished) onFinished() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Tokens.padLarge), verticalArrangement = Arrangement.spacedBy(Tokens.spaceSmall)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        s.error != null -> "error"
                        s.finished -> "check_circle"
                        else -> "downloading"
                    },
                    if (s.error != null) p.error else p.primary, filled = true,
                )
                Spacer(Modifier.width(Tokens.spaceSmall))
                Column(Modifier.weight(1f)) {
                    GText(s.title, p.onSurface, weight = FontWeight.SemiBold)
                    GText(
                        s.error ?: (s.step + if (s.total > 0) "  ${s.done}/${s.total}" else ""),
                        if (s.error != null) p.error else p.onSurfaceVariant, size = 12.sp, maxLines = 3,
                    )
                }
                if (!s.running) IconCircle("close", p.tile, p.onSurface, size = 36.dp) { Jobs.dismiss() }
                else IconCircle("stop", p.tile, p.onSurface, size = 36.dp) { Jobs.cancel() }
            }
            if (s.running) {
                val frac = if (s.total > 0) s.done.toFloat() / s.total else 0f
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(p.surfaceContainerHighest)) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(CircleShape).background(p.primary))
                }
            }
        }
    }
}
