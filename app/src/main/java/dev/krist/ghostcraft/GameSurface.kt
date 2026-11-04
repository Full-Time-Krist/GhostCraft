package dev.krist.ghostcraft

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.hypot

// Our GLFW (app/src/main/cpp/glfw). The game's JVM loads the same library, so
// what we post here comes out of its glfwPollEvents.
object GlfwBridge {
    init {
        System.loadLibrary("glfw")
    }

    const val PRESS = 1
    const val RELEASE = 0
    const val REPEAT = 2
    const val CURSOR_NORMAL = 0x34001
    const val CURSOR_DISABLED = 0x34003
    const val MOUSE_LEFT = 0
    const val MOUSE_RIGHT = 1

    @JvmStatic external fun setDisplay(width: Int, height: Int, refresh: Int, xdpi: Float, ydpi: Float)
    @JvmStatic external fun setSurface(surface: android.view.Surface?, width: Int, height: Int)
    @JvmStatic external fun key(key: Int, action: Int, mods: Int)
    @JvmStatic external fun chr(codepoint: Int, mods: Int)
    @JvmStatic external fun button(button: Int, action: Int, mods: Int)
    @JvmStatic external fun move(x: Float, y: Float)
    @JvmStatic external fun delta(dx: Float, dy: Float)
    @JvmStatic external fun scroll(dx: Float, dy: Float)
    @JvmStatic external fun focus(focused: Boolean)
    @JvmStatic external fun requestClose()
    @JvmStatic external fun windowReady(): Boolean
    @JvmStatic external fun cursorMode(): Int
    @JvmStatic external fun setClipboard(utf8: ByteArray)
    @JvmStatic external fun takeClipboard(): ByteArray?
}

// The game's window. Touch acts as a mouse: in menus a finger is the cursor,
// in the world (cursor captured) dragging turns the camera, a tap uses an item
// and holding still breaks blocks. Two fingers scroll, a two-finger tap is a
// right click. A real mouse and keyboard work as on the PC. In the world the
// on-screen controls (Controls.kt) get first pick of every finger.
@SuppressLint("ViewConstructor")
class GameSurface(ctx: Context, private val renderScale: Float) : SurfaceView(ctx), SurfaceHolder.Callback {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val main = Handler(Looper.getMainLooper())

    // Surface buffer vs view size: below 1 the game renders fewer pixels
    private var sx = 1f
    private var sy = 1f

    private var mode = GlfwBridge.CURSOR_NORMAL
    private var mods = 0

    // On-screen controls: offered every finger first while in the world
    var controls: Controls? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        setOnCapturedPointerListener { _, e -> mouse(e, captured = true) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (renderScale < 1f && w > 0 && h > 0)
            holder.setFixedSize((w * renderScale).toInt().coerceAtLeast(1), (h * renderScale).toInt().coerceAtLeast(1))
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        if (width > 0 && height > 0) {
            sx = w.toFloat() / width
            sy = h.toFloat() / height
            controls?.let { it.width = width; it.height = height; it.fbWidth = w; it.fbHeight = h }
        }
        GlfwBridge.setSurface(holder.surface, w, h)
    }

    // Blocks briefly until the game lets go of the surface
    override fun surfaceDestroyed(holder: SurfaceHolder) = GlfwBridge.setSurface(null, 0, 0)

    // The game switched between menus (free cursor) and the world (captured)
    fun setCursorMode(value: Int) {
        if (value == mode) return
        mode = value
        resetTouch()
        val world = value == GlfwBridge.CURSOR_DISABLED
        val mouse = hasMouse()
        if (world && mouse) requestPointerCapture() else releasePointerCapture()
        controls?.setActive(world && !mouse)
        // chat typed and sent: the keyboard goes away with the chat screen
        if (world) context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    private fun hasMouse() = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.let { !it.isVirtual && it.supportsSource(InputDevice.SOURCE_MOUSE) } == true
    }

    // --- touch ---------------------------------------------------------------

    private var finger = -1
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var leftDown = false
    private var twoFingers = false
    private var twoStart = 0L
    private var twoMoved = false
    private var scrollY = 0f

    // In menus the press waits a moment, so a two-finger tap doesn't left-click
    // a slot first; in the world holding still starts breaking
    private val pressLeft = Runnable {
        if (finger != -1 && !twoFingers && !leftDown && (mode != GlfwBridge.CURSOR_DISABLED || !moved)) {
            leftDown = true
            GlfwBridge.button(GlfwBridge.MOUSE_LEFT, GlfwBridge.PRESS, mods)
        }
    }

    private fun releaseLeft() {
        main.removeCallbacks(pressLeft)
        if (leftDown) {
            leftDown = false
            GlfwBridge.button(GlfwBridge.MOUSE_LEFT, GlfwBridge.RELEASE, mods)
        }
    }

    private fun resetTouch() {
        releaseLeft()
        finger = -1
        twoFingers = false
    }

    private fun click(button: Int) {
        GlfwBridge.button(button, GlfwBridge.PRESS, mods)
        GlfwBridge.button(button, GlfwBridge.RELEASE, mods)
    }

    private fun averageY(e: MotionEvent) = (0 until e.pointerCount).sumOf { e.getY(it).toDouble() }.toFloat() / e.pointerCount

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return mouse(e, captured = false)
        val world = mode == GlfwBridge.CURSOR_DISABLED
        controls?.takeIf { world && it.shown }?.let { return worldTouch(e, it) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finger = e.getPointerId(0)
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                downTime = e.eventTime
                moved = false
                twoFingers = false
                if (!world) GlfwBridge.move(e.x * sx, e.y * sy)
                main.postDelayed(pressLeft, if (world) HOLD_MS else PRESS_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!twoFingers && e.pointerCount == 2) {
                twoFingers = true
                twoStart = e.eventTime
                twoMoved = false
                scrollY = averageY(e)
                releaseLeft()
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFingers) {
                    val steps = ((scrollY - averageY(e)) / SCROLL_STEP).toInt()
                    if (steps != 0) {
                        GlfwBridge.scroll(0f, steps.toFloat())
                        scrollY -= steps * SCROLL_STEP
                        twoMoved = true
                    }
                    return true
                }
                val i = e.findPointerIndex(finger)
                if (i < 0) return true
                val x = e.getX(i)
                val y = e.getY(i)
                if (!moved && hypot(x - downX, y - downY) > slop) {
                    moved = true
                    if (world) {
                        // the slop distance shouldn't jerk the camera
                        main.removeCallbacks(pressLeft)
                        lastX = x; lastY = y
                    } else {
                        // dragging in a menu: press now so the drag carries the item
                        main.removeCallbacks(pressLeft)
                        pressLeft.run()
                    }
                }
                if (world) {
                    if (moved) GlfwBridge.delta(x - lastX, y - lastY)
                } else {
                    GlfwBridge.move(x * sx, y * sy)
                }
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> if (twoFingers && e.pointerCount == 2) {
                if (!twoMoved && e.eventTime - twoStart < TAP_MS) click(GlfwBridge.MOUSE_RIGHT)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tap = e.actionMasked == MotionEvent.ACTION_UP && !twoFingers && !moved
                main.removeCallbacks(pressLeft)
                if (world) {
                    if (tap && !leftDown && e.eventTime - downTime < TAP_MS) click(GlfwBridge.MOUSE_RIGHT)
                } else if (tap && !leftDown) {
                    click(GlfwBridge.MOUSE_LEFT)
                }
                resetTouch()
            }
        }
        return true
    }

    // World with on-screen controls: each finger goes to a control or, if none
    // takes it, becomes the camera finger (drag turns, tap uses, hold breaks)
    private fun worldTouch(e: MotionEvent, c: Controls): Boolean {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(i)
                val y = e.getY(i)
                if (c.down(id, x, y)) return true
                if (finger == -1) {
                    finger = id
                    downX = x; downY = y; lastX = x; lastY = y
                    downTime = e.eventTime
                    moved = false
                    twoFingers = false
                    main.postDelayed(pressLeft, HOLD_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> for (k in 0 until e.pointerCount) {
                val pid = e.getPointerId(k)
                val x = e.getX(k)
                val y = e.getY(k)
                if (c.owns(pid)) c.move(pid, x, y)
                else if (pid == finger) {
                    if (!moved && hypot(x - downX, y - downY) > slop) {
                        moved = true
                        main.removeCallbacks(pressLeft)
                        lastX = x; lastY = y
                    }
                    if (moved) GlfwBridge.delta(x - lastX, y - lastY)
                    lastX = x; lastY = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (c.owns(id)) c.up(id)
                else if (id == finger) {
                    main.removeCallbacks(pressLeft)
                    if (!moved && !leftDown && e.eventTime - downTime < TAP_MS) click(GlfwBridge.MOUSE_RIGHT)
                    resetTouch()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                c.releasePointers()
                resetTouch()
            }
        }
        return true
    }

    // --- mouse ---------------------------------------------------------------

    private var mouseButtons = 0

    override fun onGenericMotionEvent(e: MotionEvent): Boolean =
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) mouse(e, captured = false) else super.onGenericMotionEvent(e)

    private fun mouse(e: MotionEvent, captured: Boolean): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_SCROLL ->
                GlfwBridge.scroll(e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.getAxisValue(MotionEvent.AXIS_VSCROLL))
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP ->
                // captured events carry relative motion in x/y
                if (captured) GlfwBridge.delta(e.x, e.y) else GlfwBridge.move(e.x * sx, e.y * sy)
        }
        val state = e.buttonState
        val changed = state xor mouseButtons
        MOUSE_BITS.forEachIndexed { glfw, bit ->
            if (changed and bit != 0) GlfwBridge.button(glfw, if (state and bit != 0) GlfwBridge.PRESS else GlfwBridge.RELEASE, mods)
        }
        mouseButtons = state
        return true
    }

    // --- keyboard ------------------------------------------------------------

    // Hardware keys (routed here by GameActivity) and keys the IME sends
    fun key(e: KeyEvent): Boolean {
        if (e.keyCode in SYSTEM_KEYS) return false
        val key = Keys.glfw(e.keyCode)
        mods = Keys.mods(e.metaState)
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (key != Keys.UNKNOWN)
                    GlfwBridge.key(key, if (e.repeatCount > 0) GlfwBridge.REPEAT else GlfwBridge.PRESS, mods)
                val c = e.getUnicodeChar(e.metaState)
                if (c > 0 && c and KeyCharacterMap.COMBINING_ACCENT == 0) GlfwBridge.chr(c, mods)
            }
            KeyEvent.ACTION_UP -> if (key != Keys.UNKNOWN) GlfwBridge.key(key, GlfwBridge.RELEASE, mods)
            KeyEvent.ACTION_MULTIPLE -> e.characters?.codePoints()?.forEach { GlfwBridge.chr(it, 0) }
        }
        return true
    }

    private fun tapKey(key: Int) {
        GlfwBridge.key(key, GlfwBridge.PRESS, 0)
        GlfwBridge.key(key, GlfwBridge.RELEASE, 0)
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
    }

    fun toggleKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        requestFocus()
        if (rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true)
            imm.hideSoftInputFromWindow(windowToken, 0)
        else
            imm.showSoftInput(this, 0)
    }

    override fun onCheckIsTextEditor() = true

    // Plain characters with no composing: chat and signs get each letter at once
    override fun onCreateInputConnection(out: EditorInfo): InputConnection {
        out.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                text.codePoints().forEach { if (it == '\n'.code) tapKey(Keys.ENTER) else GlfwBridge.chr(it, 0) }
                return true
            }

            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                repeat(before) { tapKey(Keys.BACKSPACE) }
                repeat(after) { tapKey(Keys.DELETE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent) = key(event)

            override fun performEditorAction(action: Int): Boolean {
                tapKey(Keys.ENTER)
                return true
            }
        }
    }

    private companion object {
        const val PRESS_MS = 90L
        const val HOLD_MS = 300L
        const val TAP_MS = 250L
        const val SCROLL_STEP = 60f
        val MOUSE_BITS = intArrayOf(
            MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY,
            MotionEvent.BUTTON_BACK, MotionEvent.BUTTON_FORWARD,
        )
        val SYSTEM_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        )
    }
}

// Android key codes → GLFW keys. Back is Escape: it opens the pause menu and
// closes screens, as Esc does on the PC.
object Keys {
    const val UNKNOWN = -1
    const val ENTER = 257
    const val BACKSPACE = 259
    const val DELETE = 261

    private val map = HashMap<Int, Int>().apply {
        for (i in 0..25) put(KeyEvent.KEYCODE_A + i, 65 + i)
        for (i in 0..9) put(KeyEvent.KEYCODE_0 + i, 48 + i)
        for (i in 0..11) put(KeyEvent.KEYCODE_F1 + i, 290 + i)
        for (i in 0..9) put(KeyEvent.KEYCODE_NUMPAD_0 + i, 320 + i)
        put(KeyEvent.KEYCODE_SPACE, 32)
        put(KeyEvent.KEYCODE_APOSTROPHE, 39)
        put(KeyEvent.KEYCODE_COMMA, 44)
        put(KeyEvent.KEYCODE_MINUS, 45)
        put(KeyEvent.KEYCODE_PERIOD, 46)
        put(KeyEvent.KEYCODE_SLASH, 47)
        put(KeyEvent.KEYCODE_SEMICOLON, 59)
        put(KeyEvent.KEYCODE_EQUALS, 61)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 91)
        put(KeyEvent.KEYCODE_BACKSLASH, 92)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 93)
        put(KeyEvent.KEYCODE_GRAVE, 96)
        put(KeyEvent.KEYCODE_ESCAPE, 256)
        put(KeyEvent.KEYCODE_BACK, 256)
        put(KeyEvent.KEYCODE_ENTER, ENTER)
        put(KeyEvent.KEYCODE_TAB, 258)
        put(KeyEvent.KEYCODE_DEL, BACKSPACE)
        put(KeyEvent.KEYCODE_INSERT, 260)
        put(KeyEvent.KEYCODE_FORWARD_DEL, DELETE)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 262)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 263)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 264)
        put(KeyEvent.KEYCODE_DPAD_UP, 265)
        put(KeyEvent.KEYCODE_PAGE_UP, 266)
        put(KeyEvent.KEYCODE_PAGE_DOWN, 267)
        put(KeyEvent.KEYCODE_MOVE_HOME, 268)
        put(KeyEvent.KEYCODE_MOVE_END, 269)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 280)
        put(KeyEvent.KEYCODE_SCROLL_LOCK, 281)
        put(KeyEvent.KEYCODE_NUM_LOCK, 282)
        put(KeyEvent.KEYCODE_SYSRQ, 283)
        put(KeyEvent.KEYCODE_BREAK, 284)
        put(KeyEvent.KEYCODE_NUMPAD_DOT, 330)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 331)
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 332)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 333)
        put(KeyEvent.KEYCODE_NUMPAD_ADD, 334)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, 335)
        put(KeyEvent.KEYCODE_NUMPAD_EQUALS, 336)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, 340)
        put(KeyEvent.KEYCODE_CTRL_LEFT, 341)
        put(KeyEvent.KEYCODE_ALT_LEFT, 342)
        put(KeyEvent.KEYCODE_META_LEFT, 343)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, 344)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, 345)
        put(KeyEvent.KEYCODE_ALT_RIGHT, 346)
        put(KeyEvent.KEYCODE_META_RIGHT, 347)
        put(KeyEvent.KEYCODE_MENU, 348)
    }

    fun glfw(keyCode: Int) = map[keyCode] ?: UNKNOWN

    fun mods(meta: Int): Int {
        var m = 0
        if (meta and KeyEvent.META_SHIFT_ON != 0) m = m or 0x01
        if (meta and KeyEvent.META_CTRL_ON != 0) m = m or 0x02
        if (meta and KeyEvent.META_ALT_ON != 0) m = m or 0x04
        if (meta and KeyEvent.META_META_ON != 0) m = m or 0x08
        if (meta and KeyEvent.META_CAPS_LOCK_ON != 0) m = m or 0x10
        if (meta and KeyEvent.META_NUM_LOCK_ON != 0) m = m or 0x20
        return m
    }
}
