package com.yy.suidu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
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
        drawRect(if (scene.dark) Color(0xFF000000) else Color(0xFFF2F2F7))
        fun cloud(color: Color, center: Offset, radius: Float) {
            drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center, radius), radius, center)
        }
        // Peripheral light only. Reading surfaces and system grouped backgrounds stay neutral.
        cloud(Color(0xFF90B7E7).copy(alpha = if (scene.dark) .055f else .085f), Offset(w * (.95f - drift), h * .03f), w * .80f)
        cloud(Color(0xFFAAC8D3).copy(alpha = if (scene.dark) .075f else .11f), Offset(w * .05f, h * .96f), w * .75f)
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
    interactive: Boolean = true,
    sheet: Boolean = false,
    lensSelection: (() -> LensSelection)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val scene = LocalGlassScene.current
    val dark = scene?.dark == true
    val shape = RoundedCornerShape(radius)
    val refracted = rememberGraphicsLayer()
    val blurRadius = with(LocalDensity.current) { (if (sheet) 18.dp else if (clear) { if (sampleContent) 4.dp else .45.dp } else 3.dp).toPx() }
    val blur = remember(blurRadius) { BlurEffect(blurRadius, blurRadius, TileMode.Clamp) }
    val lens = remember(clear) { if (clear) createClearLensEffect() else null }
    val compatibilityLens = remember { CompatibilityLens() }
    SideEffect { if (lens == null) refracted.renderEffect = blur }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var touch by remember { mutableStateOf(Offset(-1f, -1f)) }
    var down by remember { mutableStateOf(false) }
    val pressure by animateFloatAsState(if (down && clear && interactive && scene?.enabled == true) 1f else 0f,
        spring(dampingRatio = .78f, stiffness = if (down) 850f else 420f), label = "glass-light")
    val tint = if (dark) Color(0xFF1C1C1E) else Color.White
    val fillAlpha = if (scene?.enabled == false) .97f else if (clear) {
        // The clear variant must retain its transparent center; whitening it obscures the lens.
        // Navigation and sheets use their own readable, adaptive regular-material treatment.
        if (lens != null) { if (sheet) .82f else if (sampleContent) .18f else .025f } else if (sampleContent) .42f else .06f
    } else if (strong) { if (dark) .55f else .68f } else if (dark) .32f else .44f
    val rim = listOf(Color.White.copy(alpha = if (dark) .7f else .96f), Color.White.copy(alpha = .015f),
        Color.White.copy(alpha = .08f), Color.White.copy(alpha = if (dark) .40f else .64f))
    Box(modifier.onGloballyPositioned { origin = it.positionInRoot() }.graphicsLayer {
        if (clear && scene?.enabled == true) {
            scaleX = 1f + pressure * .018f
            scaleY = 1f + pressure * .025f
            translationX = (touch.x - size.width / 2f).coerceIn(-size.width / 2f, size.width / 2f) * pressure * .025f
        }
    }.shadow(if (sheet) 24.dp else if (clear) 12.dp else 1.dp, shape, ambientColor = Color.Black.copy(alpha = .08f), spotColor = Color.Black.copy(alpha = .16f))
        .clip(shape)
        .pointerInput(clear, interactive, scene?.enabled) {
            if (clear && interactive && scene?.enabled == true) {
                // Observe the complete stream without waiting for gesture recognition or consuming it.
                // Press illumination must also work on the first event after this layer is attached.
                try { awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val active = event.changes.firstOrNull { it.pressed }
                        (active ?: event.changes.firstOrNull())?.let { touch = it.position }
                        down = active != null
                    }
                } } finally { down = false }
            }
        }
        .drawWithContent {
            if (scene != null && scene.enabled) {
                val r = radius.toPx().coerceAtMost(size.minDimension / 2f)
                val band = (if (sheet) 24.dp else 16.dp).toPx().coerceAtMost(size.minDimension * .42f)
                val bend = (if (sheet) 30.dp else 22.dp).toPx() * opticalStrength
                val lightPoint = if (touch.x < 0) center else touch
                val pad = if (lens != null) ceil(bend + blurRadius * 2f) else 0f
                if (lens != null) refracted.renderEffect = lens.update(size, r, band, bend, lightPoint, pressure.coerceIn(0f, 1f),
                    blurRadius, pad, if (sampleContent || sheet) 1f else 0f, dark, lensSelection?.invoke())
                refracted.record(size = IntSize(ceil(size.width + pad * 2).toInt(), ceil(size.height + pad * 2).toInt())) {
                    translate(pad - origin.x, pad - origin.y) {
                        drawLayer(scene.backdrop)
                        if (sampleContent) translate(scene.contentOrigin.x, scene.contentOrigin.y) { drawLayer(scene.content) }
                    }
                }
                if (clear && lens == null) compatibilityLens.draw(this, refracted, r, band, bend)
                else translate(-pad, -pad) { drawLayer(refracted) }
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
        }.then(if (clear && lens != null && scene?.enabled == true) Modifier else
            Modifier.border(if (clear) .6.dp else .4.dp, Brush.linearGradient(if (clear) rim else listOf(Color.White.copy(alpha = .4f), Color.White.copy(alpha = .10f))), shape)), content = content)
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, strong: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    // Content panels use a thin material; the clear optical layer belongs to controls.
    IosPanel(modifier, content = content)
}

@Composable
fun GlassAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, arrow: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, spring(.86f, 550f), label = "primary-press")
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.defaultMinSize(minHeight = 52.dp).graphicsLayer {
        scaleX = scale; scaleY = scale; alpha = if (!enabled) .35f else if (pressed) .82f else 1f
    }.clip(RoundedCornerShape(26.dp))
        .background(Brush.verticalGradient(listOf(accent.copy(red = (accent.red + .06f).coerceAtMost(1f), green = (accent.green + .04f).coerceAtMost(1f)), accent)))
        .border(.5.dp, Color.White.copy(alpha = .22f), RoundedCornerShape(26.dp))
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 15.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun GlassNavigation(tab: Int, compact: Boolean = false, onTab: (Int) -> Unit) {
    val inset by animateDpAsState(if (compact) 34.dp else 12.dp, spring(.9f, 500f), label = "dock-inset")
    val itemHeight by animateDpAsState(if (compact) 50.dp else 58.dp, spring(.9f, 500f), label = "dock-height")
    Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
    BoxWithConstraints(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(horizontal = inset, vertical = 8.dp)) {
                val slot = (maxWidth - 8.dp) / 5
                val density = LocalDensity.current
                val slotPixels = with(LocalDensity.current) { slot.toPx() }
                var dragged by remember { mutableStateOf<Float?>(null) }
                val onSelect by rememberUpdatedState(onTab)
                val target = dragged ?: tab.toFloat()
                val position by animateFloatAsState(target, spring(dampingRatio = .80f, stiffness = if (dragged != null) 1150f else 480f), label = "liquid-tab")
                val lifting by animateFloatAsState(if (dragged != null) 1f else 0f, spring(.8f, 520f), label = "liquid-lift")
                val stretch = abs(target - position).coerceAtMost(1.2f)
                val width = slot - 2.dp + slot * (stretch * .22f + lifting * .045f)
                val height = itemHeight - 2.dp + 3.dp * lifting - 3.dp * stretch
        GlassSurface(Modifier.fillMaxWidth(), radius = 36.dp, sampleContent = true, interactive = false,
            lensSelection = { with(density) { LensSelection(Offset(4.dp.toPx() + slotPixels * (position + .5f), (itemHeight / 2 + 4.dp).toPx()), Size(width.toPx(), height.toPx()), lifting) } }) {
            Box(Modifier.fillMaxWidth().padding(4.dp)) {
                // The shader renders selection as a raised region of this single optical plane.
                // A restrained tint keeps selection readable on every rendering path.
                Box(Modifier.offset(x = slot * (position + .5f) - width / 2, y = (itemHeight - height) / 2)
                    .width(width).height(height)
                    .shadow(2.dp, RoundedCornerShape(50), spotColor = Color.Black.copy(alpha = .08f))
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .065f + lifting * .025f))
                    .border(.6.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .76f), Color.Transparent, Color.White.copy(alpha = .28f))), RoundedCornerShape(50)))
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
                        val active = target.roundToInt() == index
                        val tint by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), tween(150), label = "nav-tint")
                        val iconScale by animateFloatAsState(if (active && dragged != null) 1.12f else 1f, spring(.78f, 520f), label = "tab-symbol")
                        Column(Modifier.weight(1f).height(itemHeight).clip(RoundedCornerShape(25.dp))
                            .semantics { contentDescription = "导航$title" }
                            .selectable(selected = tab == index, onClick = { onTab(index) }, role = Role.Tab,
                                interactionSource = remember { MutableInteractionSource() }, indication = null),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            NavGlyph(index, tint, Modifier.size(24.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale })
                            Spacer(Modifier.height(3.dp))
                            Text(title, color = tint, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
                        }
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
            5 -> {
                val speaker = Path().apply { moveTo(3*u, 9*u); lineTo(7*u, 9*u); lineTo(12*u, 5*u); lineTo(12*u, 19*u); lineTo(7*u, 15*u); lineTo(3*u, 15*u); close() }
                drawPath(speaker, tint, style = stroke)
                drawArc(tint, -48f, 96f, false, Offset(10*u, 6*u), Size(10*u, 12*u), style = stroke)
                drawArc(tint, -48f, 96f, false, Offset(9*u, 3*u), Size(15*u, 18*u), style = stroke)
            }
            else -> {
                val gear = Path()
                for (i in 0 until 64) {
                    val angle = i * kotlin.math.PI / 32.0
                    val radius = (if (i % 8 in 2..5) 10f else 8.2f) * u
                    val x = 12*u + kotlin.math.cos(angle).toFloat()*radius
                    val y = 12*u + kotlin.math.sin(angle).toFloat()*radius
                    if (i == 0) gear.moveTo(x,y) else gear.lineTo(x,y)
                }
                gear.close()
                drawPath(gear, tint, style = stroke)
                drawCircle(tint, 3.2f*u, Offset(12*u,12*u), style = stroke)
            }
        }
    }
}

@Composable
fun GlassHeader(reader: Boolean, back: () -> Unit) {
    val backTint = MaterialTheme.colorScheme.onSurface
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
            Text(if (reader) "精读" else "词境·随读", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(if (reader) "在语境中理解每一个词" else "读自己的文章，积累自己的词库", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!reader) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))
            Text("本地", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
