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
import androidx.compose.ui.BiasAlignment
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
import androidx.compose.ui.text.style.TextAlign
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
    var anchor by mutableStateOf(Anchor.RIGHT)
    var square by mutableStateOf(false)
    /** Tiles per row as chosen in settings; 0 means all in one row. */
    var perRow by mutableIntStateOf(1)
    var edgeGap by mutableIntStateOf(24)
    /** Shown instead of the lights, e.g. when setup is incomplete. */
    var message by mutableStateOf<String?>(null)
    val shown = MutableTransitionState(false)

    /** Number of tiles actually next to each other, given how many lights there are. */
    val columns: Int
        get() = (if (perRow == 0) lights.size else minOf(perRow, lights.size)).coerceAtLeast(1)
}

@Composable
fun Label(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = align),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun LightBarOverlay(ui: OverlayUiState) {
    val anchor = ui.anchor
    val slide = spring<IntOffset>(dampingRatio = 0.82f, stiffness = 520f)
    // The panel slides in from, and back out to, the nearest edge: top or bottom for the six
    // positions along those edges, the side for the two side centres.
    val fromSide = anchor.row == 1
    val towardsStart = if (fromSide) anchor.col == 0 else anchor.row == 0
    val offset = { size: Int -> if (towardsStart) -size / 2 else size / 2 }
    Box(
        modifier = Modifier.fillMaxSize().padding(ui.edgeGap.dp),
        contentAlignment = BiasAlignment(anchor.col - 1f, anchor.row - 1f),
    ) {
        AnimatedVisibility(
            visibleState = ui.shown,
            enter = fadeIn(tween(160)) +
                if (fromSide) slideInHorizontally(slide, offset) else slideInVertically(slide, offset),
            exit = fadeOut(tween(160)) +
                if (fromSide) slideOutHorizontally(tween(200), offset) else slideOutVertically(tween(200), offset),
        ) {
            Panel(ui)
        }
    }
}

@Composable
private fun Panel(ui: OverlayUiState) {
    val shape = RoundedCornerShape(28.dp)
    val square = ui.square
    val columns = ui.columns
    Column(
        modifier = Modifier
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

        // Tiles keep their natural size unless a full row would not fit on screen.
        val available = LocalConfiguration.current.screenWidthDp.dp -
            (ui.edgeGap.dp + PanelPadding) * 2 - TileGap * (columns - 1)
        val tileWidth: Dp = (available / columns).coerceAtMost(if (square) 132.dp else 276.dp)
        Column(
            verticalArrangement = Arrangement.spacedBy(TileGap),
            // A shorter last row lines up with the side of the screen the panel is anchored to.
            horizontalAlignment = when (ui.anchor.col) {
                0 -> Alignment.Start
                1 -> Alignment.CenterHorizontally
                else -> Alignment.End
            },
        ) {
            ui.lights.withIndex().chunked(columns).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TileGap),
                    // A tile that expands in adjust mode grows away from the screen edge.
                    verticalAlignment = if (ui.anchor.row == 0) Alignment.Top else Alignment.Bottom,
                ) {
                    row.forEach { (i, light) ->
                        key(light.id) {
                            LightTile(light, i == ui.selected, ui.controlling, ui.param, card = square, Modifier.width(tileWidth))
                        }
                    }
                }
            }
        }

        val canWarm = ui.lights.getOrNull(ui.selected)?.supportsTemp == true
        // A single column of square cards is too narrow for one line, so the hint is stacked there.
        val narrow = square && columns == 1
        val gap = if (narrow) "\n" else "   ·   "
        val hint = when {
            !ui.controlling -> "OK  Adjust${gap}Hold OK  On/Off"
            canWarm -> "◀ ▶  Adjust$gap▲ ▼  Warmth${gap}OK  Done"
            else -> "◀ ▶  Adjust${gap}OK  Done${gap}Hold OK  On/Off"
        }
        Crossfade(hint, animationSpec = tween(180), label = "hint") {
            Label(
                it, 11.sp, Color.White.copy(alpha = 0.55f), Modifier.padding(top = 10.dp, bottom = 2.dp),
                maxLines = 3, align = TextAlign.Center,
            )
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
