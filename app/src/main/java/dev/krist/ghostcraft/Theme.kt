package dev.krist.ghostcraft

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.json.JSONObject

// Material You scheme in the same names as PanelStyle.m3*
@Immutable
class Palette(private val map: Map<String, Color>, val isLight: Boolean, val sekai: Boolean = true) {
    private fun c(name: String, fallback: Color) = map[name] ?: fallback

    val surface = c("surface", Color(0xFF130D0E))
    val surfaceContainerLow = c("surfaceContainerLow", Color(0xFF191114))
    val surfaceContainer = c("surfaceContainer", Color(0xFF20171A))
    val surfaceContainerHigh = c("surfaceContainerHigh", Color(0xFF271D20))
    val surfaceContainerHighest = c("surfaceContainerHighest", Color(0xFF2F2226))
    val onSurface = c("onSurface", Color(0xFFF6E0E5))
    val onSurfaceVariant = c("onSurfaceVariant", Color(0xFFBAA6AB))
    val outline = c("outline", Color(0xFF827175))
    val outlineVariant = c("outlineVariant", Color(0xFF534448))
    val primary = c("primary", Color(0xFFF2B6C9))
    val onPrimary = c("onPrimary", Color(0xFF5E3342))
    val primaryContainer = c("primaryContainer", Color(0xFF724555))
    val onPrimaryContainer = c("onPrimaryContainer", Color(0xFFFFDAE4))
    val secondary = c("secondary", Color(0xFFE2BDC7))
    val secondaryContainer = c("secondaryContainer", Color(0xFF4E343C))
    val onSecondaryContainer = c("onSecondaryContainer", Color(0xFFDBB6C0))
    val tertiary = c("tertiary", Color(0xFFFFE7FD))
    val tertiaryContainer = c("tertiaryContainer", Color(0xFFFDD2FD))
    val error = c("error", Color(0xFFF97386))
    val onError = c("onError", Color(0xFF490013))

    // Sekai skin, as in PanelStyle: the darkest tone of the accent outlines
    // everything, with a light rim between the outline and the fill
    val ink: Color = map["_ink"] ?: if (isLight) lerp(onPrimaryContainer, Color.Black, 0.3f)
    else lerp(Color(0xFF060508), primary, 0.1f)
    val rim = Color.White.copy(alpha = if (isLight) 0.9f else 0.3f)
    // Seed of the scheme, for the system Material You palette
    val seed: Color? = map["primaryPaletteKeyColor"] ?: map["primary_paletteKeyColor"]
    // Sekai: opaque stickers; glass: translucent like phone widgets (PanelStyle)
    val card get() = if (sekai) surfaceContainerLow else surfaceContainerHighest.copy(alpha = 0.62f)
    val tile get() = if (sekai) surfaceContainerHigh else surfaceContainerHigh.copy(alpha = 0.55f)

    companion object {
        fun parse(json: String?, sekai: Boolean = true): Palette {
            val obj = runCatching { JSONObject(json ?: "{}") }.getOrDefault(JSONObject())
            // scheme.json nests the colours and drops the '#'; panel_palette.py does neither
            val src = obj.optJSONObject("colours") ?: obj
            val map = buildMap {
                for (key in src.keys()) {
                    val v = src.opt(key) as? String ?: continue
                    val hex = v.removePrefix("#")
                    if (hex.length != 6 && hex.length != 8) continue
                    val n = hex.toLongOrNull(16) ?: continue
                    put(key, Color(if (hex.length == 6) 0xFF000000 or n else n))
                }
            }
            val mode = obj.optString("_mode", obj.optString("mode", "dark"))
            return Palette(map, mode == "light", sekai)
        }
    }
}

val LocalPalette = staticCompositionLocalOf { Palette(emptyMap(), false) }

@OptIn(ExperimentalTextApi::class)
object Fonts {
    private fun gsans(weight: Int) = Font(
        R.font.gsans, FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("ROND", 100f)),
    )

    // Google Sans Flex with fully rounded terminals, Ghost's UI font
    val ui = FontFamily(listOf(300, 400, 500, 600, 700, 800).map(::gsans))

    val clock = FontFamily(Font(R.font.rubik, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))))

    private fun symbol(fill: Float) = Font(
        R.font.symbols, FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.Setting("FILL", fill), FontVariation.weight(500)),
    )

    val icons = FontFamily(symbol(0f))
    val iconsFilled = FontFamily(symbol(1f))
}

object Tokens {
    // Caelestia's defaults with the user's scales (rounding 1.25, spacing/padding 1.1)
    val roundSmall = 15.dp
    val roundNormal = 21.dp
    val roundLarge = 31.dp
    val spaceSmall = 8.dp
    val spaceNormal = 13.dp
    val spaceLarge = 22.dp
    val padSmall = 6.dp
    val padNormal = 11.dp
    val padLarge = 17.dp

    val outlineWidth = 2.dp
    val rimWidth = 1.2.dp
    val shadowOffset = 3.dp
}

// Sticker.qml: thick ink outline with a light rim, over a hard offset shadow,
// drawn just outside the element so its own fill and content stay as they are
fun Modifier.sticker(palette: Palette, radius: Dp, shadow: Boolean = true): Modifier = if (!palette.sekai) this else drawBehind {
    val rim = Tokens.rimWidth.toPx()
    val stroke = Tokens.outlineWidth.toPx() + rim
    val r = radius.toPx()
    fun rr(color: Color, grow: Float, offset: Float = 0f) = drawRoundRect(
        color = color,
        topLeft = Offset(-grow + offset, -grow + offset),
        size = Size(size.width + grow * 2, size.height + grow * 2),
        cornerRadius = CornerRadius(r + grow),
    )
    if (shadow) rr(palette.ink.copy(alpha = 0.35f), stroke, Tokens.shadowOffset.toPx())
    rr(palette.ink, stroke)
    rr(palette.rim, rim)
}
