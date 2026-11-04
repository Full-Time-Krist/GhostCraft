package dev.krist.ghostcraft

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Material Symbols Rounded by ligature name, like MaterialIcon in Ghost
@Composable
fun Icon(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: TextUnit = 22.sp,
    filled: Boolean = false,
) = Text(
    text = name,
    modifier = modifier,
    color = color,
    style = TextStyle(
        fontFamily = if (filled) Fonts.iconsFilled else Fonts.icons,
        fontSize = size,
        lineHeight = size,
    ),
)

@Composable
fun GText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    shadow: Shadow? = null,
) = Text(
    text = text,
    modifier = modifier,
    color = color,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
    style = TextStyle(
        fontFamily = Fonts.ui,
        fontWeight = weight,
        fontSize = size,
        letterSpacing = letterSpacing,
        shadow = shadow,
    ),
)

// Ghost's StateLayer has no ripple on the panel, just the press scale; keep
// clicks quiet here too
fun Modifier.tap(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier =
    this.then(
        if (onLongClick == null) Modifier.clickable(
            interactionSource = null,
            indication = null,
            onClick = onClick,
        )
        else Modifier.combinedClickableQuiet(onClick, onLongClick),
    )

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableQuiet(onClick: () -> Unit, onLongClick: () -> Unit) =
    this.then(
        Modifier.combinedClickable(
            interactionSource = null,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    )

// Round icon button in a filled circle (toggle buttons, media controls)
@Composable
fun IconCircle(
    icon: String,
    container: Color,
    content: Color,
    size: Dp = 40.dp,
    iconSize: TextUnit = 20.sp,
    filled: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) = Box(
    modifier
        .size(size)
        .clip(CircleShape)
        .background(container)
        .then(if (onClick != null) Modifier.tap(onClick = onClick) else Modifier),
    contentAlignment = Alignment.Center,
) {
    Icon(icon, content, size = iconSize, filled = filled)
}

fun pillShape(radius: Dp) = RoundedCornerShape(radius)

// Ghost's expressive spatial spring (Anim.ExpressiveDefaultSpatial)
fun <T> ghostSpring() = spring<T>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
fun <T> ghostFastSpring() = spring<T>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
