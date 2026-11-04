package dev.krist.ghostcraft

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt

// On-screen controls for the world (cursor captured). The touch logic lives
// here and GameSurface hands it every finger first; whatever no control takes
// stays the camera. The overlay only draws (and handles touches while the
// layout is being edited).

enum class Ctl(val icon: String, val title: String, val toggle: Boolean = false) {
    JOYSTICK("joystick", "Джойстик"),
    JUMP("keyboard_double_arrow_up", "Прыжок"),
    SNEAK("keyboard_double_arrow_down", "Красться", toggle = true),
    SPRINT("sprint", "Бег", toggle = true),
    ATTACK("swords", "Удар"),
    USE("back_hand", "Использовать"),
    INVENTORY("backpack", "Инвентарь"),
    DROP("move_item", "Выбросить"),
    CHAT("chat", "Чат"),
    PERSPECTIVE("cameraswitch", "Вид"),
    PAUSE("pause", "Пауза"),
}

// Centre as a fraction of the screen, size in dp
data class Placement(val x: Float, val y: Float, val size: Float)

// A key or a mouse button, as Minecraft names them in options.txt
data class Bind(val key: Int = -1, val mouse: Int = -1)

// The player's keybinds and GUI scale from the instance's options.txt, so the
// buttons follow whatever was rebound in Prism
class GameOptions(private val file: File) {
    private var stamp = -1L
    private val binds = HashMap<String, Bind>()
    var guiScale = 0
        private set
    var forceUnicode = false
        private set

    fun bind(name: String): Bind = binds[name] ?: DEFAULTS[name] ?: Bind()

    fun reload() {
        val m = file.lastModified()
        if (m == stamp) return
        stamp = m
        binds.clear()
        runCatching {
            file.forEachLine { line ->
                val i = line.indexOf(':')
                if (i < 0) return@forEachLine
                val k = line.substring(0, i)
                val v = line.substring(i + 1)
                when {
                    k == "guiScale" -> guiScale = v.toIntOrNull() ?: 0
                    k == "forceUnicodeFont" -> forceUnicode = v == "true"
                    k.startsWith("key_") -> parse(v)?.let { binds[k.removePrefix("key_")] = it }
                }
            }
        }
    }

    // Minecraft's own rule (Window.calculateScale)
    fun scaleFor(w: Int, h: Int): Int {
        var i = 1
        while (i != guiScale && i < w && i < h && w / (i + 1) >= 320 && h / (i + 1) >= 240) i++
        if (forceUnicode && i % 2 != 0) i++
        return i
    }

    companion object {
        private val DEFAULTS = mapOf(
            "key.forward" to Bind(87), "key.left" to Bind(65), "key.back" to Bind(83), "key.right" to Bind(68),
            "key.jump" to Bind(32), "key.sneak" to Bind(340), "key.sprint" to Bind(341),
            "key.inventory" to Bind(69), "key.drop" to Bind(81), "key.chat" to Bind(84),
            "key.togglePerspective" to Bind(294),
            "key.attack" to Bind(mouse = 0), "key.use" to Bind(mouse = 1),
        ) + (1..9).associate { "key.hotbar.$it" to Bind(48 + it) }

        private val NAMED = mapOf(
            "space" to 32, "apostrophe" to 39, "comma" to 44, "minus" to 45, "period" to 46, "slash" to 47,
            "semicolon" to 59, "equal" to 61, "left.bracket" to 91, "backslash" to 92, "right.bracket" to 93,
            "grave.accent" to 96, "escape" to 256, "enter" to 257, "tab" to 258, "backspace" to 259,
            "insert" to 260, "delete" to 261, "right" to 262, "left" to 263, "down" to 264, "up" to 265,
            "page.up" to 266, "page.down" to 267, "home" to 268, "end" to 269, "caps.lock" to 280,
            "left.shift" to 340, "left.control" to 341, "left.alt" to 342, "left.win" to 343,
            "right.shift" to 344, "right.control" to 345, "right.alt" to 346, "right.win" to 347, "menu" to 348,
        )

        fun parse(v: String): Bind? {
            if (v.startsWith("key.mouse.")) return when (val b = v.removePrefix("key.mouse.")) {
                "left" -> Bind(mouse = 0)
                "right" -> Bind(mouse = 1)
                "middle" -> Bind(mouse = 2)
                else -> b.toIntOrNull()?.let { Bind(mouse = it - 1) }
            }
            if (!v.startsWith("key.keyboard.")) return null
            val n = v.removePrefix("key.keyboard.")
            NAMED[n]?.let { return Bind(it) }
            if (n.length == 1) {
                val c = n[0]
                if (c in 'a'..'z') return Bind(c.code - 'a'.code + 65)
                if (c in '0'..'9') return Bind(c.code)
            }
            if (n.startsWith("f")) n.drop(1).toIntOrNull()?.takeIf { it in 1..25 }?.let { return Bind(289 + it) }
            if (n.startsWith("keypad.")) n.removePrefix("keypad.").toIntOrNull()?.let { return Bind(320 + it) }
            return null
        }
    }
}

// A button the player made in the editor: any key or mouse button
data class CustomButton(val id: String, val label: String, val bind: Bind, val toggle: Boolean)

// Keys offered when making a button, in GLFW codes
object KeyChoices {
    val all: List<Pair<String, Bind>> = buildList {
        add("ЛКМ" to Bind(mouse = 0)); add("ПКМ" to Bind(mouse = 1)); add("СКМ" to Bind(mouse = 2))
        add("Space" to Bind(32)); add("Shift" to Bind(340)); add("Ctrl" to Bind(341)); add("Alt" to Bind(342))
        add("Tab" to Bind(258)); add("Esc" to Bind(256)); add("Enter" to Bind(257)); add("⌫" to Bind(259))
        add("↑" to Bind(265)); add("↓" to Bind(264)); add("←" to Bind(263)); add("→" to Bind(262))
        for (c in 'A'..'Z') add(c.toString() to Bind(c.code))
        for (c in '0'..'9') add(c.toString() to Bind(c.code))
        for (i in 1..12) add("F$i" to Bind(289 + i))
        add("-" to Bind(45)); add("=" to Bind(61)); add("[" to Bind(91)); add("]" to Bind(93))
        add(";" to Bind(59)); add("'" to Bind(39)); add("," to Bind(44)); add("." to Bind(46)); add("/" to Bind(47)); add("`" to Bind(96))
    }

    fun name(b: Bind) = all.firstOrNull { it.second == b }?.first ?: if (b.mouse >= 0) "M${b.mouse + 1}" else "#${b.key}"
}

class Controls(private val ctx: Context, private val options: GameOptions) {
    // Every control by id: Ctl names for the built-in ones, "c…" for custom
    val layout = mutableStateMapOf<String, Placement>()
    val hidden = mutableStateMapOf<Ctl, Boolean>()
    val custom = mutableStateMapOf<String, CustomButton>()
    var opacity by mutableFloatStateOf(0.75f)

    // what the overlay shows
    var shown by mutableStateOf(false)
        private set
    var editing by mutableStateOf(false)
    var selected by mutableStateOf<String?>(null)
    val held = mutableStateMapOf<String, Boolean>()   // pressed now, or toggled on
    var stickCenter by mutableStateOf<Offset?>(null)
    var stickKnob by mutableStateOf(Offset.Zero)

    // the screen in view pixels and the game's framebuffer, for the hotbar
    var width = 1
    var height = 1
    var fbWidth = 1
    var fbHeight = 1
    private val density = ctx.resources.displayMetrics.density

    var onChat: () -> Unit = {}

    init {
        load()
    }

    // ids of the controls on screen, built-in first
    val visible: List<String>
        get() = Ctl.entries.filter { hidden[it] != true }.map { it.name } + custom.keys.sorted()

    fun builtin(id: String): Ctl? = Ctl.entries.firstOrNull { it.name == id }
    private fun isToggle(id: String) = builtin(id)?.toggle ?: (custom[id]?.toggle == true)

    // --- layout ----------------------------------------------------------------

    private val prefs get() = ctx.getSharedPreferences("controls", Context.MODE_PRIVATE)

    private fun load() {
        DEFAULT_LAYOUT.forEach { (c, p) -> layout[c.name] = p }
        val json = prefs.getString("layout", null) ?: return
        runCatching {
            val o = JSONObject(json)
            opacity = o.optDouble("opacity", opacity.toDouble()).toFloat()
            o.optJSONArray("custom")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    custom[c.getString("id")] = CustomButton(
                        c.getString("id"), c.optString("label"),
                        Bind(c.optInt("key", -1), c.optInt("mouse", -1)), c.optBoolean("toggle"),
                    )
                }
            }
            o.optJSONArray("hidden")?.let { arr ->
                for (i in 0 until arr.length()) builtin(arr.getString(i))?.let { hidden[it] = true }
            }
            val items = o.getJSONObject("items")
            for (id in items.keys()) {
                if (builtin(id) == null && id !in custom) continue
                val a = items.getJSONArray(id)
                layout[id] = Placement(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
            }
        }
    }

    fun save() {
        val items = JSONObject()
        layout.forEach { (id, p) -> items.put(id, JSONArray(listOf(p.x, p.y, p.size))) }
        val cs = JSONArray()
        custom.values.forEach {
            cs.put(JSONObject().put("id", it.id).put("label", it.label).put("key", it.bind.key)
                .put("mouse", it.bind.mouse).put("toggle", it.toggle))
        }
        val o = JSONObject().put("opacity", opacity).put("items", items).put("custom", cs)
            .put("hidden", JSONArray(hidden.filterValues { it }.keys.map { it.name }))
        prefs.edit().putString("layout", o.toString()).apply()
    }

    // Built-in buttons back where they were; your own buttons stay
    fun reset() {
        DEFAULT_LAYOUT.forEach { (c, p) -> layout[c.name] = p }
        hidden.clear()
        opacity = 0.75f
    }

    fun addCustom(label: String, bind: Bind, toggle: Boolean): String {
        val id = "c" + System.currentTimeMillis().toString(36)
        custom[id] = CustomButton(id, label, bind, toggle)
        layout[id] = Placement(0.5f, 0.42f, 56f)
        selected = id
        return id
    }

    fun updateCustom(id: String, label: String, bind: Bind, toggle: Boolean) {
        custom[id] = CustomButton(id, label, bind, toggle)
    }

    // Own buttons go for good; built-in ones hide until a reset
    fun remove(id: String) {
        val b = builtin(id)
        if (b != null) hidden[b] = true else {
            custom.remove(id)
            layout.remove(id)
        }
        held.remove(id)
        if (selected == id) selected = null
    }

    // The editor pauses the game first: the world keeps going otherwise
    fun startEditing() {
        if (shown) {
            press(Bind(256), true)
            press(Bind(256), false)
        }
        editing = true
    }

    fun centerOf(id: String): Offset = (layout[id] ?: Placement(0.5f, 0.5f, 56f)).let { Offset(it.x * width, it.y * height) }
    fun radiusOf(id: String): Float = (layout[id]?.size ?: 56f) * density / 2

    // --- game state --------------------------------------------------------------

    // In the world without a mouse the controls show; leaving it lets every
    // key go (a held Shift would turn inventory clicks into shift-clicks), but
    // toggles come back on return
    fun setActive(world: Boolean) {
        if (world == shown) return
        shown = world
        if (world) {
            options.reload()
            for (id in visible) if (isToggle(id) && held[id] == true) send(id, true)
        } else {
            releasePointers()
            for (id in visible) if (isToggle(id) && held[id] == true) send(id, false)
        }
    }

    // --- touch -------------------------------------------------------------------

    private val owners = HashMap<Int, String>()          // pointer → control id
    private var stickPointer = -1
    private val moveKeys = HashSet<String>()
    private val hotbarPointers = HashSet<Int>()

    fun owns(pointer: Int) = owners.containsKey(pointer) || pointer == stickPointer || pointer in hotbarPointers

    fun down(pointer: Int, x: Float, y: Float): Boolean {
        if (!shown || editing) return false
        // buttons first (the ones drawn on top win), then the hotbar, then the stick's area
        val hit = visible.filter { it != Ctl.JOYSTICK.name }.lastOrNull {
            hypot(x - centerOf(it).x, y - centerOf(it).y) <= radiusOf(it) * 1.15f
        }
        if (hit != null) {
            owners[pointer] = hit
            if (isToggle(hit)) {
                val on = held[hit] != true
                held[hit] = on
                send(hit, on)
            } else {
                held[hit] = true
                send(hit, true)
                if (hit == Ctl.CHAT.name) onChat()
            }
            return true
        }
        hotbarSlot(x, y)?.let { slot ->
            hotbarPointers += pointer
            val b = options.bind("key.hotbar.${slot + 1}")
            press(b, true)
            press(b, false)
            return true
        }
        if (hidden[Ctl.JOYSTICK] != true && stickPointer == -1 && inStickArea(x, y)) {
            stickPointer = pointer
            stickCenter = Offset(x, y)
            stickKnob = Offset(x, y)
            return true
        }
        return false
    }

    fun move(pointer: Int, x: Float, y: Float) {
        if (pointer != stickPointer) return
        val r = radiusOf(Ctl.JOYSTICK.name)
        var c = stickCenter ?: return
        var d = Offset(x - c.x, y - c.y)
        val len = d.getDistance()
        // the base follows a finger that runs far past the rim
        if (len > r * 1.5f) {
            c += d * ((len - r * 1.5f) / len)
            stickCenter = c
            d = Offset(x - c.x, y - c.y)
        }
        val dist = d.getDistance()
        stickKnob = if (dist > r) c + d * (r / dist) else Offset(x, y)
        val nx = d.x / r
        val ny = d.y / r
        val want = HashSet<String>()
        if (ny < -DEAD) want += "key.forward"
        if (ny > DEAD) want += "key.back"
        if (nx < -DEAD) want += "key.left"
        if (nx > DEAD) want += "key.right"
        // pushing forward past the rim runs, like a double tap on W
        if (ny < -1.25f && kotlin.math.abs(nx) < 0.6f) want += "key.sprint"
        setMoveKeys(want)
    }

    fun up(pointer: Int) {
        if (pointer == stickPointer) {
            stickPointer = -1
            stickCenter = null
            setMoveKeys(emptySet())
        }
        hotbarPointers -= pointer
        val id = owners.remove(pointer) ?: return
        if (!isToggle(id)) {
            held[id] = false
            send(id, false)
        }
    }

    fun releasePointers() {
        for (p in owners.keys.toList() + stickPointer + hotbarPointers.toList()) up(p)
        hotbarPointers.clear()
    }

    private fun inStickArea(x: Float, y: Float): Boolean {
        val c = centerOf(Ctl.JOYSTICK.name)
        val r = radiusOf(Ctl.JOYSTICK.name) * 2.2f
        return x in c.x - r..c.x + r && y in c.y - r..c.y + r
    }

    private fun setMoveKeys(want: Set<String>) {
        for (k in moveKeys - want) press(options.bind(k), false)
        // sprint is pressed after forward, or the game ignores it
        for (k in (want - moveKeys).sortedBy { it == "key.sprint" }) press(options.bind(k), true)
        moveKeys.clear()
        moveKeys += want
    }

    // Hotbar in GUI pixels: 182×22 at the bottom centre, 20 per slot
    private fun hotbarSlot(x: Float, y: Float): Int? {
        val s = options.scaleFor(fbWidth, fbHeight)
        val gw = kotlin.math.ceil(fbWidth.toDouble() / s).toInt()
        val gh = kotlin.math.ceil(fbHeight.toDouble() / s).toInt()
        // view pixels → GUI pixels (the framebuffer may be scaled down)
        val gx = x * fbWidth / width / s
        val gy = y * fbHeight / height / s
        val left = gw / 2 - 91
        if (gy < gh - 23 || gx < left || gx >= left + 182) return null
        return ((gx - left - 1) / 20).toInt().coerceIn(0, 8)
    }

    private fun send(id: String, down: Boolean) {
        custom[id]?.let { press(it.bind, down); return }
        val name = when (builtin(id)) {
            Ctl.JUMP -> "key.jump"
            Ctl.SNEAK -> "key.sneak"
            Ctl.SPRINT -> "key.sprint"
            Ctl.ATTACK -> "key.attack"
            Ctl.USE -> "key.use"
            Ctl.INVENTORY -> "key.inventory"
            Ctl.DROP -> "key.drop"
            Ctl.CHAT -> "key.chat"
            Ctl.PERSPECTIVE -> "key.togglePerspective"
            Ctl.PAUSE -> null
            Ctl.JOYSTICK, null -> return
        }
        press(if (name == null) Bind(256) else options.bind(name), down)
    }

    private fun press(b: Bind, down: Boolean) {
        val action = if (down) GlfwBridge.PRESS else GlfwBridge.RELEASE
        if (b.key >= 0) GlfwBridge.key(b.key, action, 0)
        else if (b.mouse >= 0) GlfwBridge.button(b.mouse, action, 0)
    }

    companion object {
        const val DEAD = 0.35f

        // 20:9 landscape; the left thumb has the stick, the right one the actions
        val DEFAULT_LAYOUT = mapOf(
            Ctl.JOYSTICK to Placement(0.13f, 0.68f, 150f),
            Ctl.JUMP to Placement(0.905f, 0.70f, 78f),
            Ctl.SNEAK to Placement(0.905f, 0.90f, 56f),
            Ctl.SPRINT to Placement(0.815f, 0.90f, 56f),
            Ctl.ATTACK to Placement(0.815f, 0.52f, 64f),
            Ctl.USE to Placement(0.905f, 0.44f, 64f),
            Ctl.INVENTORY to Placement(0.705f, 0.93f, 48f),
            Ctl.DROP to Placement(0.295f, 0.93f, 48f),
            Ctl.CHAT to Placement(0.84f, 0.08f, 44f),
            Ctl.PERSPECTIVE to Placement(0.89f, 0.08f, 44f),
            Ctl.PAUSE to Placement(0.94f, 0.08f, 44f),
        )
    }
}

// --- drawing -------------------------------------------------------------------

@Composable
fun ControlsOverlay(controls: Controls, onDone: () -> Unit) {
    if (!controls.shown && !controls.editing) return
    val p = LocalPalette.current
    val density = LocalDensity.current
    var dialog by remember { mutableStateOf<String?>(null) }   // "" = new button
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val editing = controls.editing
        if (editing) Box(
            Modifier.fillMaxSize().background(p.surface.copy(alpha = 0.45f))
                .pointerInput(Unit) { detectTapGestures { controls.selected = null } },
        )
        Box(Modifier.fillMaxSize().alpha(if (editing) 1f else controls.opacity)) {
            for (id in controls.visible) {
                val pl = controls.layout[id] ?: continue
                val c = controls.builtin(id)
                val own = controls.custom[id]
                val sizePx = with(density) { pl.size.dp.toPx() }
                val on = controls.held[id] == true
                var cx = pl.x * w
                var cy = pl.y * h
                val stick = c == Ctl.JOYSTICK
                if (stick && !editing) controls.stickCenter?.let { cx = it.x; cy = it.y }
                val drag = if (editing) Modifier.pointerInput(id) {
                    detectDragGestures(onDragStart = { controls.selected = id }) { change, amount ->
                        change.consume()
                        val cur = controls.layout[id] ?: return@detectDragGestures
                        controls.layout[id] = cur.copy(
                            x = (cur.x + amount.x / w).coerceIn(0.02f, 0.98f),
                            y = (cur.y + amount.y / h).coerceIn(0.03f, 0.97f),
                        )
                    }
                }.pointerInput("tap$id") {
                    detectTapGestures(onTap = {
                        // a second tap on your own button edits it
                        if (controls.selected == id && own != null) dialog = id else controls.selected = id
                    })
                } else Modifier
                Box(
                    Modifier
                        .offset { IntOffset((cx - sizePx / 2).roundToInt(), (cy - sizePx / 2).roundToInt()) }
                        .size(pl.size.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                stick -> p.tile.copy(alpha = 0.55f)
                                on -> p.primary
                                own != null -> p.secondaryContainer.copy(alpha = 0.9f)
                                else -> p.tile.copy(alpha = 0.85f)
                            },
                        )
                        .then(
                            if (editing && controls.selected == id) Modifier.border(3.dp, p.primary, CircleShape)
                            else Modifier.border(1.5.dp, p.rim.copy(alpha = 0.25f), CircleShape),
                        )
                        .then(drag),
                    contentAlignment = Alignment.Center,
                ) {
                    val fg = if (on) p.onPrimary else p.onSurface
                    when {
                        own != null -> GText(
                            own.label.ifEmpty { KeyChoices.name(own.bind) }, fg,
                            size = (pl.size * if (own.label.length > 3) 0.22f else 0.32f).sp, weight = FontWeight.Bold,
                        )
                        c != null && (!stick || editing) -> Icon(c.icon, fg, size = (pl.size * 0.42f).sp, filled = on)
                    }
                }
                if (stick && !editing) {
                    val knob = sizePx * 0.42f
                    val k = controls.stickCenter?.let { controls.stickKnob } ?: Offset(cx, cy)
                    Box(
                        Modifier
                            .offset { IntOffset((k.x - knob / 2).roundToInt(), (k.y - knob / 2).roundToInt()) }
                            .size(with(density) { knob.toDp() })
                            .clip(CircleShape)
                            .background(if (controls.stickCenter != null) p.primary else p.onSurfaceVariant.copy(alpha = 0.7f)),
                    )
                }
            }
        }
        if (editing) {
            EditorBar(controls, onAdd = { dialog = "" }, onEdit = { dialog = it }, onDone = onDone)
            dialog?.let { target ->
                ButtonDialog(controls.custom[target], onDismiss = { dialog = null }) { label, bind, toggle ->
                    if (target.isEmpty()) controls.addCustom(label, bind, toggle)
                    else controls.updateCustom(target, label, bind, toggle)
                    dialog = null
                }
            }
        }
    }
}

@Composable
private fun EditorBar(controls: Controls, onAdd: () -> Unit, onEdit: (String) -> Unit, onDone: () -> Unit) {
    val p = LocalPalette.current
    val pad = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding() + Tokens.spaceSmall), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.clip(CircleShape).background(p.surfaceContainer).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.spaceSmall),
        ) {
            val sel = controls.selected
            val own = sel?.let { controls.custom[it] }
            val title = when {
                sel == null -> "Выбери кнопку"
                own != null -> own.label.takeIf { it.isNotEmpty() && it != KeyChoices.name(own.bind) }
                    ?.let { "$it · ${KeyChoices.name(own.bind)}" } ?: KeyChoices.name(own.bind)
                else -> controls.builtin(sel)?.title ?: sel
            }
            GText(title, p.onSurface, Modifier.padding(horizontal = 6.dp), weight = FontWeight.SemiBold)
            if (sel != null) {
                IconCircle("remove", p.tile, p.onSurface, size = 38.dp) { resize(controls, sel, -8f) }
                IconCircle("add", p.tile, p.onSurface, size = 38.dp) { resize(controls, sel, 8f) }
                if (own != null) IconCircle("edit", p.tile, p.onSurface, size = 38.dp) { onEdit(sel) }
                IconCircle(if (own != null) "delete" else "visibility_off", p.tile, p.error, size = 38.dp) { controls.remove(sel) }
            }
            Spacer(Modifier.width(6.dp))
            Icon("opacity", p.onSurfaceVariant, size = 18.sp)
            IconCircle("remove", p.tile, p.onSurface, size = 38.dp) {
                controls.opacity = (controls.opacity - 0.1f).coerceAtLeast(0.2f)
            }
            GText("${(controls.opacity * 100).roundToInt()}%", p.onSurfaceVariant, size = 13.sp)
            IconCircle("add", p.tile, p.onSurface, size = 38.dp) {
                controls.opacity = (controls.opacity + 0.1f).coerceAtMost(1f)
            }
            Spacer(Modifier.width(6.dp))
            IconCircle("add_circle", p.primaryContainer, p.onPrimaryContainer, size = 38.dp, onClick = onAdd)
            IconCircle("restart_alt", p.tile, p.onSurface, size = 38.dp) { controls.reset() }
            IconCircle("check", p.primary, p.onPrimary, size = 38.dp) {
                controls.selected = null
                controls.save()
                onDone()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
// Making or changing your own button: what it presses, its label, hold or toggle
@Composable
private fun ButtonDialog(current: CustomButton?, onDismiss: () -> Unit, onSave: (String, Bind, Boolean) -> Unit) {
    val p = LocalPalette.current
    var bind by remember { mutableStateOf(current?.bind ?: Bind(70)) }
    var label by remember { mutableStateOf(current?.label ?: "") }
    var toggle by remember { mutableStateOf(current?.toggle ?: false) }
    Box(
        Modifier.fillMaxSize().background(p.surface.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.72f).fillMaxHeight(0.86f).clip(RoundedCornerShape(Tokens.roundNormal))
                .background(p.surfaceContainer).pointerInput(Unit) { detectTapGestures { } }
                .padding(Tokens.padLarge),
            verticalArrangement = Arrangement.spacedBy(Tokens.spaceNormal),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GText(if (current == null) "Новая кнопка" else "Кнопка", p.onSurface, Modifier.weight(1f), size = 18.sp, weight = FontWeight.Bold)
                // the label field: short text drawn on the button
                Row(
                    Modifier.width(220.dp).clip(CircleShape).background(p.tile).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon("label", p.onSurfaceVariant, size = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (label.isEmpty()) GText("Подпись: ${KeyChoices.name(bind)}", p.onSurfaceVariant, size = 13.sp)
                        BasicTextField(
                            label, { label = it.take(8) }, singleLine = true,
                            textStyle = TextStyle(color = p.onSurface, fontFamily = Fonts.ui, fontSize = 14.sp),
                            cursorBrush = SolidColor(p.primary), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            GText("Что нажимает", p.onSurfaceVariant, size = 12.sp)
            FlowRow(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((name, b) in KeyChoices.all) {
                    val on = b == bind
                    Box(
                        Modifier.size(width = if (name.length > 2) 64.dp else 46.dp, height = 40.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) p.primary else p.tile).tap { bind = b },
                        contentAlignment = Alignment.Center,
                    ) { GText(name, if (on) p.onPrimary else p.onSurface, size = 13.sp, weight = FontWeight.SemiBold) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.spaceSmall)) {
                Row(Modifier.clip(CircleShape).tap { toggle = !toggle }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(toggle)
                    Spacer(Modifier.width(10.dp))
                    GText(if (toggle) "Переключатель: нажал — держится" else "Удерживать, пока палец на кнопке", p.onSurface)
                }
                Spacer(Modifier.weight(1f))
                IconCircle("close", p.tile, p.onSurface, size = 44.dp, onClick = onDismiss)
                IconCircle("check", p.primary, p.onPrimary, size = 44.dp) { onSave(label.trim(), bind, toggle) }
            }
        }
    }
}

private fun resize(controls: Controls, id: String, by: Float) {
    val cur = controls.layout[id] ?: return
    val min = if (id == Ctl.JOYSTICK.name) 100f else 36f
    controls.layout[id] = cur.copy(size = (cur.size + by).coerceIn(min, 220f))
}
