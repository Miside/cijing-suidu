package com.yy.suidu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Only the backdrop is blurred. Content stays in the unfiltered foreground layer. */
class GlassScene(val backdrop: GraphicsLayer, val content: GraphicsLayer, val dark: Boolean, val enabled: Boolean) {
    var contentOrigin by mutableStateOf(Offset.Zero)
}
val LocalGlassScene = staticCompositionLocalOf<GlassScene?> { null }

@Composable
fun LiquidBackground(scene: GlassScene, mood: State<Float>) {
    Canvas(Modifier.fillMaxSize().drawWithContent {
        scene.backdrop.record { this@drawWithContent.drawContent() }
        drawLayer(scene.backdrop)
    }) {
        val w = size.width
        val h = size.height
        val drift = mood.value * .025f
        drawRect(Brush.linearGradient(if (scene.dark) listOf(Color(0xFF0D1221), Color(0xFF172137), Color(0xFF221D35))
            else listOf(Color(0xFFF7F8FF), Color(0xFFEAF1FD), Color(0xFFF7EBF3)), end = Offset(w, h)))
        fun cloud(color: Color, center: Offset, radius: Float) {
            drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center, radius), radius, center)
        }
        cloud(if (scene.dark) Color(0xFF315DE0).copy(alpha = .46f) else Color(0xFF87AEFF).copy(alpha = .60f), Offset(w * (.96f - drift), h * .12f), w * .96f)
        cloud(if (scene.dark) Color(0xFF854ADB).copy(alpha = .32f) else Color(0xFFC4A7F4).copy(alpha = .45f), Offset(w * (-.1f + drift), h * .43f), w * .90f)
        cloud(if (scene.dark) Color(0xFFA15B72).copy(alpha = .22f) else Color(0xFFF5BCB6).copy(alpha = .48f), Offset(w * (.98f - drift), h * .77f), w * .85f)
        cloud(if (scene.dark) Color(0xFF398784).copy(alpha = .22f) else Color(0xFFAAE5DF).copy(alpha = .40f), Offset(w * .13f, h * (1f - drift)), w * .8f)
        // Broad, curved color folds supply real detail for the floating lens to bend.
        val fold = Path().apply {
            moveTo(-w * .35f, h * (.88f - drift))
            cubicTo(w * .18f, h * .69f, w * .82f, h * 1.12f, w * 1.28f, h * .73f)
            lineTo(w * 1.3f, h * 1.15f); lineTo(-w * .35f, h * 1.15f); close()
        }
        drawPath(fold, Brush.linearGradient(listOf(
            Color(0xFF789BE9).copy(alpha = if (scene.dark) .30f else .42f),
            Color(0xFFBB9ADD).copy(alpha = if (scene.dark) .24f else .32f),
            Color(0xFFF0BDCD).copy(alpha = if (scene.dark) .16f else .30f)),
            start = Offset(0f, h * .8f), end = Offset(w, h)))
        val foldEdge = Path().apply {
            moveTo(-w * .35f, h * (.88f - drift))
            cubicTo(w * .18f, h * .69f, w * .82f, h * 1.12f, w * 1.28f, h * .73f)
        }
        drawPath(foldEdge, Color.White.copy(alpha = if (scene.dark) .10f else .32f), style = Stroke(1.2.dp.toPx()))
    }
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    radius: Dp = 26.dp,
    strong: Boolean = false,
    sampleContent: Boolean = false,
    clear: Boolean = true,
    opticalStrength: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val scene = LocalGlassScene.current
    val dark = scene?.dark == true
    val shape = RoundedCornerShape(radius)
    val refracted = rememberGraphicsLayer()
    val blurRadius = with(LocalDensity.current) { (if (clear) { if (sampleContent) 5.dp else .65.dp } else 3.dp).toPx() }
    val blur = remember(blurRadius) { BlurEffect(blurRadius, blurRadius, TileMode.Clamp) }
    val lens = remember(clear) { if (clear) createClearLensEffect() else null }
    val compatibilityLens = remember { CompatibilityLens() }
    SideEffect { if (lens == null) refracted.renderEffect = blur }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var touch by remember { mutableStateOf(Offset(-1f, -1f)) }
    var down by remember { mutableStateOf(false) }
    val pressure by animateFloatAsState(if (down && clear && scene?.enabled == true) 1f else 0f,
        spring(dampingRatio = .74f, stiffness = 430f), label = "glass-light")
    val tint = if (dark) Color(0xFF121B2A) else Color.White
    val fillAlpha = if (scene?.enabled == false) .97f else if (clear) { if (sampleContent) .16f else if (dark) .07f else .035f } else if (strong) .32f else .14f
    val rim = listOf(Color.White.copy(alpha = if (dark) .7f else .96f), Color.White.copy(alpha = .015f),
        Color.White.copy(alpha = .08f), Color.White.copy(alpha = if (dark) .40f else .64f))
    Box(modifier.graphicsLayer {
        if (clear && scene?.enabled == true) {
            scaleX = 1f + pressure * .012f
            scaleY = 1f + pressure * .035f
            translationX = (touch.x - size.width / 2f).coerceIn(-size.width / 2f, size.width / 2f) * pressure * .025f
        }
    }.shadow(if (clear) 10.dp else 3.dp, shape, ambientColor = Color.Black.copy(alpha = .10f), spotColor = Color(0xFF0A2342).copy(alpha = .16f))
        .clip(shape).onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(clear, scene?.enabled) {
            if (clear && scene?.enabled == true) awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touch = first.position
                down = true
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull { it.id == first.id }?.let { touch = it.position }
                    } while (event.changes.any { it.pressed })
                } finally { down = false }
            }
        }
        .drawWithContent {
            if (scene != null && scene.enabled) {
                val r = radius.toPx().coerceAtMost(size.minDimension / 2f)
                val band = 18.dp.toPx().coerceAtMost(size.minDimension * .42f)
                val bend = 23.dp.toPx() * opticalStrength * (1f + pressure * .15f)
                val lightPoint = if (touch.x < 0) center else touch
                if (lens != null) refracted.renderEffect = lens.update(size, r, band, bend, lightPoint, pressure, blurRadius)
                refracted.record {
                    translate(-origin.x, -origin.y) {
                        drawLayer(scene.backdrop)
                        if (sampleContent) translate(scene.contentOrigin.x, scene.contentOrigin.y) { drawLayer(scene.content) }
                    }
                }
                if (clear && lens == null) compatibilityLens.draw(this, refracted, r, band, bend) else drawLayer(refracted)
            }
            drawRect(tint.copy(alpha = fillAlpha))
            if (clear) {
                drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) .05f else .07f), Color.Transparent, Color.White.copy(alpha = .018f)), end = Offset(size.width, size.height)))
                if (pressure > .001f) {
                    val point = if (touch.x < 0) center else touch
                    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = (pressure * .22f).coerceIn(0f, .4f)), Color.Transparent), point, size.maxDimension * .75f))
                }
            }
            this@drawWithContent.drawContent()
        }.border(if (clear) .75.dp else .4.dp, Brush.linearGradient(if (clear) rim else listOf(Color.White.copy(alpha = .35f), Color.White.copy(alpha = .08f))), shape), content = content)
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, strong: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    // Content panels use a thin material; the clear optical layer belongs to controls.
    GlassSurface(modifier, strong = strong, clear = false) { Column(content = content) }
}

@Composable
fun GlassAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, arrow: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 1.025f else 1f, spring(dampingRatio = .70f, stiffness = 400f), label = "glass-press")
    GlassSurface(modifier.defaultMinSize(minHeight = 56.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .45f }, radius = 28.dp) {
        Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2)
                if (arrow) Text("↗", color = MaterialTheme.colorScheme.primary.copy(alpha = .82f), fontSize = 19.sp)
            }
        }
    }
}

@Composable
fun GlassNavigation(tab: Int, onTab: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp)) {
        GlassSurface(Modifier.fillMaxWidth(), radius = 40.dp, sampleContent = true) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(6.dp)) {
                val slot = maxWidth / 5
                val slotPixels = with(LocalDensity.current) { slot.toPx() }
                var dragged by remember { mutableStateOf<Float?>(null) }
                val onSelect by rememberUpdatedState(onTab)
                val target = dragged ?: tab.toFloat()
                val position by animateFloatAsState(target, spring(dampingRatio = .76f, stiffness = if (dragged != null) 900f else 380f), label = "liquid-tab")
                val lifting by animateFloatAsState(if (dragged != null) 1f else 0f, spring(.78f, 440f), label = "liquid-lift")
                val stretch = abs(target - position).coerceAtMost(1.2f)
                val width = slot - 6.dp + slot * (stretch * .30f + lifting * .08f)
                val height = 58.dp + 5.dp * lifting - 4.dp * stretch
                // One thin, elastic overlay within the glass. No second stacked glass surface.
                Box(Modifier.offset(x = slot * (position + .5f) - width / 2, y = (64.dp - height) / 2)
                    .width(width).height(height)
                    .shadow(2.dp, RoundedCornerShape(50), spotColor = Color.Black.copy(alpha = .08f))
                    .clip(RoundedCornerShape(50))
                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = .30f + lifting * .14f), MaterialTheme.colorScheme.primary.copy(alpha = .055f), Color.White.copy(alpha = .12f))))
                    .border(.75.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .92f), Color.Transparent, Color.White.copy(alpha = .42f))), RoundedCornerShape(50)))
                Row(Modifier.fillMaxWidth().selectableGroup().pointerInput(slotPixels) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = (it.x / slotPixels - .5f).coerceIn(0f, 4f) },
                        onDragCancel = { dragged = null },
                        onDragEnd = { val selected = (dragged ?: tab.toFloat()).roundToInt().coerceIn(0, 4); dragged = null; onSelect(selected) },
                    ) { change, amount ->
                        change.consume()
                        dragged = ((dragged ?: tab.toFloat()) + amount / slotPixels).coerceIn(0f, 4f)
                    }
                }) {
                    listOf("书库", "导入", "词库", "复习", "设置").forEachIndexed { index, title ->
                        val tint by animateColorAsState(if (tab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, tween(180), label = "nav-tint")
                        Column(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(23.dp))
                            .selectable(selected = tab == index, onClick = { onTab(index) }, role = Role.Tab,
                                interactionSource = remember { MutableInteractionSource() }, indication = null),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            NavGlyph(index, tint, Modifier.size(23.dp))
                            Spacer(Modifier.height(5.dp))
                            Text(title, color = tint, fontSize = 11.sp, fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NavGlyph(index: Int, tint: Color, modifier: Modifier = Modifier) {
    if (index == 2) { Box(modifier, contentAlignment = Alignment.Center) { Text("Aa", color = tint, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false) }; return }
    val diskColor = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        val u = size.width / 24f
        val stroke = Stroke(1.65f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), stroke.width, StrokeCap.Round)
        when (index) {
            0 -> {
                val path = Path().apply { moveTo(12*u, 6*u); cubicTo(8*u, 3*u, 5*u, 3*u, 2*u, 4*u); lineTo(2*u, 19*u); cubicTo(6*u, 18*u, 9*u, 18*u, 12*u, 21*u); cubicTo(15*u, 18*u, 18*u, 18*u, 22*u, 19*u); lineTo(22*u, 4*u); cubicTo(18*u, 3*u, 15*u, 3*u, 12*u, 6*u); close() }
                drawPath(path, tint, style = stroke); line(12f, 6f, 12f, 21f)
            }
            1 -> {
                drawRoundRect(tint, Offset(3*u, 4*u), Size(18*u, 16*u), CornerRadius(3*u), style = stroke)
                line(12f, 8f, 12f, 16f); line(8f, 12f, 16f, 12f)
            }
            3 -> {
                drawCircle(tint, 8.5f*u, Offset(12*u, 12*u), style = stroke)
                line(12f, 7f, 12f, 12f); line(12f, 12f, 16f, 14f)
            }
            else -> {
                line(5f, 3f, 5f, 21f); line(12f, 3f, 12f, 21f); line(19f, 3f, 19f, 21f)
                listOf(5f to 8f, 12f to 16f, 19f to 9f).forEach { (x,y) -> drawCircle(diskColor, 2.6f*u, Offset(x*u, y*u)); drawCircle(tint, 2.6f*u, Offset(x*u, y*u), style = stroke) }
            }
        }
    }
}

@Composable
fun GlassHeader(reader: Boolean, back: () -> Unit) {
    val backTint = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (reader) GlassSurface(Modifier.size(43.dp), radius = 22.dp) {
            Box(Modifier.fillMaxSize().semantics { contentDescription = "返回" }.clickable(role = Role.Button, onClick = back), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(22.dp)) {
                    val u = size.width / 24f
                    val path = Path().apply { moveTo(15*u, 4*u); lineTo(7*u, 12*u); lineTo(15*u, 20*u) }
                    drawPath(path, backTint, style = Stroke(2f*u, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        else GlassSurface(Modifier.size(43.dp), radius = 15.dp) {
            NavGlyph(0, MaterialTheme.colorScheme.primary, Modifier.size(24.dp).align(Alignment.Center))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (reader) "精读" else "词境·随读", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .4.sp)
            Text(if (reader) "在语境中理解每一个词" else "读自己的文章，积累自己的词库", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!reader) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))
            Text("本地", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
