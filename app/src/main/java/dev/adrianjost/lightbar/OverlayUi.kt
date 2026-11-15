package dev.adrianjost.lightbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0E1216)

private val PanelPadding = 12.dp
private val TileGap = 8.dp

/** Everything the overlay draws. Mutated by [LightBarService], observed by Compose. */
class OverlayUiState {
    val lights = mutableStateListOf<Light>()
    var selected by mutableIntStateOf(0)
    var controlling by mutableStateOf(false)
    var param by mutableStateOf(Param.BRIGHTNESS)
    var layout by mutableStateOf(BarLayout.SIDEBAR)
    var align by mutableStateOf(BarAlign.CENTER)
    var edgeGap by mutableIntStateOf(24)
    /** Shown instead of the lights, e.g. when setup is incomplete. */
    var message by mutableStateOf<String?>(null)
    val shown = MutableTransitionState(false)
}

@Composable
fun Label(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun LightBarOverlay(ui: OverlayUiState) {
    val sidebar = ui.layout != BarLayout.BOTTOM
    val left = ui.layout == BarLayout.LEFT_SIDEBAR
    val slide = spring<IntOffset>(dampingRatio = 0.82f, stiffness = 520f)
    Box(
        modifier = Modifier.fillMaxSize().padding(ui.edgeGap.dp),
        contentAlignment = when (ui.align) {
            BarAlign.START -> if (left) Alignment.TopStart else if (sidebar) Alignment.TopEnd else Alignment.BottomStart
            BarAlign.CENTER -> if (left) Alignment.CenterStart else if (sidebar) Alignment.CenterEnd else Alignment.BottomCenter
            BarAlign.END -> if (left) Alignment.BottomStart else Alignment.BottomEnd
        },
    ) {
        AnimatedVisibility(
            visibleState = ui.shown,
            enter = fadeIn(tween(160)) +
                if (sidebar) slideInHorizontally(slide) { if (left) -it / 2 else it / 2 } else slideInVertically(slide) { it / 2 },
            exit = fadeOut(tween(160)) +
                if (sidebar) slideOutHorizontally(tween(200)) { if (left) -it / 2 else it / 2 } else slideOutVertically(tween(200)) { it / 2 },
        ) {
            Panel(ui, sidebar)
        }
    }
}

@Composable
private fun Panel(ui: OverlayUiState, sidebar: Boolean) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = Modifier
            .then(if (sidebar) Modifier.width(300.dp) else Modifier)
            .clip(shape)
            .background(Color(0xDB101418))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .padding(PanelPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val message = ui.message
        if (message != null) {
            Label(message, 14.sp, Color.White, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            return@Column
        }

        if (sidebar) {
            Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
                ui.lights.forEachIndexed { i, light ->
                    key(light.id) {
                        LightTile(light, i == ui.selected, ui.controlling, ui.param, card = false, Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            val count = ui.lights.size.coerceAtLeast(1)
            val available = LocalConfiguration.current.screenWidthDp.dp -
                (ui.edgeGap.dp + PanelPadding) * 2 - TileGap * (count - 1)
            // Compact, roughly square cards instead of wide rows.
            val tileWidth: Dp = (available / count).coerceAtMost(132.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(TileGap), verticalAlignment = Alignment.Bottom) {
                ui.lights.forEachIndexed { i, light ->
                    key(light.id) {
                        LightTile(light, i == ui.selected, ui.controlling, ui.param, card = true, Modifier.width(tileWidth))
                    }
                }
            }
        }

        val canWarm = ui.lights.getOrNull(ui.selected)?.supportsTemp == true
        val hint = when {
            !ui.controlling -> "OK  Adjust   ·   Hold OK  On/Off"
            canWarm -> "◀ ▶  Adjust   ·   ▲ ▼  Warmth   ·   OK  Done"
            else -> "◀ ▶  Adjust   ·   OK  Done   ·   Hold OK  On/Off"
        }
        Crossfade(hint, animationSpec = tween(180), label = "hint") {
            Label(it, 11.sp, Color.White.copy(alpha = 0.55f), Modifier.padding(top = 10.dp, bottom = 2.dp))
        }
    }
}

@Composable
private fun LightTile(
    light: Light,
    selected: Boolean,
    controlling: Boolean,
    param: Param,
    card: Boolean,
    modifier: Modifier,
) {
    val active = selected && controlling
    // The selected light is bright, white and slightly larger; the others step back.
    val alpha by animateFloatAsState(
        when {
            selected -> 1f
            controlling -> 0.35f
            else -> 0.6f
        },
        tween(160), label = "alpha",
    )
    val scale by animateFloatAsState(
        if (selected) 1.04f else 1f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "scale",
    )
    Column(
        modifier.graphicsLayer {
            this.alpha = alpha
            scaleX = scale
            scaleY = scale
        }
    ) {
        BrightnessRow(light, selected, card, dimmed = active && light.supportsTemp && param == Param.WARMTH)
        AnimatedVisibility(
            visible = active && light.supportsTemp,
            enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 600f)) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
        ) {
            WarmthRow(light, compact = card, dimmed = param != Param.WARMTH)
        }
    }
}

/** The tile itself is the slider: the filled part of the pill is the brightness. */
@Composable
private fun BrightnessRow(light: Light, selected: Boolean, card: Boolean, dimmed: Boolean) {
    val fraction by animateFloatAsState(light.fraction, spring(dampingRatio = 1f, stiffness = 700f), label = "fill")
    val fill by animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.30f), tween(80), label = "fillColor")
    val track by animateColorAsState(
        if (selected) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.10f), tween(80), label = "track",
    )
    val rowAlpha by animateFloatAsState(if (dimmed) 0.45f else 1f, tween(150), label = "rowAlpha")
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .graphicsLayer { alpha = rowAlpha }
            .fillMaxWidth()
            .height(if (card) 104.dp else 56.dp)
            .clip(shape)
            .background(track)
            .drawBehind { drawRect(fill, size = Size(size.width * fraction, size.height)) }
    ) {
        // Text is drawn twice and clipped at the fill edge: white on the track, and on the fill
        // dark for the selected (white) tile or white for the translucent unselected ones.
        TileContent(light, Color.White, card, Modifier.drawWithContent {
            clipRect(left = size.width * fraction) { this@drawWithContent.drawContent() }
        })
        TileContent(light, if (selected) Ink else Color.White, card, Modifier.drawWithContent {
            clipRect(right = size.width * fraction) { this@drawWithContent.drawContent() }
        })
    }
}

@Composable
private fun TileContent(light: Light, color: Color, card: Boolean, modifier: Modifier) {
    if (card) {
        // Card: icon and value on top, name along the bottom.
        Column(modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_bulb),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(color),
                )
                Spacer(Modifier.weight(1f))
                Label(light.valueText, 14.sp, color)
            }
            Spacer(Modifier.weight(1f))
            Label(light.name, 15.sp, color, weight = FontWeight.Medium)
        }
        return
    }
    Row(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_bulb),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(color),
        )
        Spacer(Modifier.width(10.dp))
        Label(light.name, 15.sp, color, Modifier.weight(1f), FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Label(light.valueText, 14.sp, color)
    }
}

@Composable
private fun WarmthRow(light: Light, compact: Boolean, dimmed: Boolean) {
    val fraction by animateFloatAsState(light.tempFraction, spring(dampingRatio = 1f, stiffness = 700f), label = "temp")
    val rowAlpha by animateFloatAsState(if (dimmed) 0.45f else 1f, tween(150), label = "rowAlpha")
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .padding(top = 6.dp)
            .graphicsLayer { alpha = rowAlpha }
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Color(0xFFFFA64D), Color(0xFFFFEBD2), Color(0xFFCFE2FF))))
            .drawWithContent {
                drawContent()
                val inset = 12.dp.toPx()
                val x = inset + (size.width - inset * 2) * fraction
                drawRoundRect(
                    color = Ink,
                    topLeft = Offset(x - 3.dp.toPx(), 8.dp.toPx()),
                    size = Size(6.dp.toPx(), size.height - 16.dp.toPx()),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact) {
                Spacer(Modifier.weight(1f))
            } else {
                Label("Warmth", 13.sp, Ink.copy(alpha = 0.75f), Modifier.weight(1f), FontWeight.Medium)
            }
            Label("${light.kelvin} K", 13.sp, Ink.copy(alpha = 0.75f))
        }
    }
}
