@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yy.suidu

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch

/** Native Android accessibility/selection semantics, with a consistent iOS-inspired visual language. */
@Composable
fun IosTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    val down by source.collectIsPressedAsState()
    val opacity by animateFloatAsState(if (!enabled) .35f else if (down) .45f else 1f, spring(1f, 1100f), label = "text-button")
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Row(modifier.defaultMinSize(minHeight = 44.dp).graphicsLayer { alpha = opacity }
                .clip(RoundedCornerShape(16.dp)).clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(contentPadding), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

@Composable
fun IosOutlineButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary), border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val opacity by animateFloatAsState(if (!enabled) .35f else if (pressed) .5f else 1f, label = "secondary-press")
    CompositionLocalProvider(LocalContentColor provides colors.contentColor) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Row(modifier.defaultMinSize(minHeight = 48.dp).graphicsLayer { alpha = opacity }.clip(shape)
                .background(if (colors.containerColor == Color.Transparent) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else Color.Transparent)
                .background(colors.containerColor).then(if (border != null) Modifier.border(border, shape) else Modifier)
                .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(contentPadding), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String? = null) {
    val haptics = rememberIosHaptics()
    val interaction = remember { MutableInteractionSource() }
    val down by interaction.collectIsPressedAsState()
    val position by animateFloatAsState(if (checked) 1f else 0f, spring(.86f, 650f), label = "switch-position")
    val squash by animateFloatAsState(if (down) 1f else 0f, spring(.9f, 900f), label = "switch-squash")
    val track by animateColorAsState(if (checked) IosPalette.Green else Color(0xFF787880).copy(alpha = .28f), label = "switch-tint")
    Box(Modifier.size(55.dp, 48.dp).semantics { label?.let { contentDescription = it } }.toggleable(checked, interactionSource = interaction, indication = null,
        role = Role.Switch, onValueChange = { haptics.toggle(it); onCheckedChange(it) }), contentAlignment = Alignment.Center) {
        Box(Modifier.size(51.dp, 31.dp).clip(CircleShape).background(track)) {
            Box(Modifier.offset(x = 2.dp + (20.dp - 4.dp * squash) * position, y = 2.dp)
                .width(27.dp + 4.dp * squash).height(27.dp)
                .shadow(2.dp, CircleShape, spotColor = Color.Black.copy(alpha = .22f)).background(Color.White, CircleShape))
        }
    }
}

@Composable
fun IosAppearancePicker(selected: Int, onSelect: (Int) -> Unit) {
    val haptics = rememberIosHaptics()
    val scene = LocalGlassScene.current
    val position by animateFloatAsState(selected.toFloat(), spring(.9f, 600f), label = "appearance-segment")
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)).padding(3.dp)) {
        val slot = maxWidth / 3
        Box(Modifier.offset(x = slot * position).width(slot).height(38.dp).shadow(2.dp, CircleShape)
            .background(if (scene?.dark == true) Color(0xFF48484A) else Color.White, CircleShape))
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            listOf("随系统", "浅色", "深色").forEachIndexed { index, title ->
                Box(Modifier.weight(1f).height(38.dp).selectable(selected == index,
                    onClick = { if (index != selected) haptics.selection(); onSelect(index) }, role = Role.Tab, interactionSource = remember { MutableInteractionSource() }, indication = null),
                    contentAlignment = Alignment.Center) {
                    Text(title, fontSize = 13.sp, fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

/** Inset iOS-style input with an always-visible label, Android selection and password semantics. */
@Composable
fun IosTextField(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, singleLine: Boolean = false,
    minLines: Int = 1, maxLines: Int = Int.MAX_VALUE,
    textStyle: TextStyle = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
    visualTransformation: VisualTransformation = VisualTransformation.None) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .38f) else Color.Transparent,
        label = "input-focus")
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .045f))
        .border(1.dp, borderColor, shape).padding(horizontal = 15.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, fontSize = 12.sp, lineHeight = 16.sp,
            color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(value = value, onValueChange = onValueChange, enabled = enabled, singleLine = singleLine,
            minLines = minLines, maxLines = if (singleLine) 1 else maxLines,
            textStyle = textStyle.copy(fontFamily = textStyle.fontFamily ?: IosUiFont, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .45f)),
            cursorBrush = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)),
            visualTransformation = visualTransformation,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label })
    }
}

@Composable
fun IosFontSlider(value: Float, onValueChange: (Float) -> Unit) {
    IosValueSlider(value, onValueChange, 17f..30f, steps = 12)
}

@Composable
fun IosValueSlider(value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>,
    steps: Int = 0, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .13f)
    Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps, modifier = modifier,
        thumb = {
            Box(Modifier.size(28.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape))
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val y = size.height / 2
                drawLine(trackColor, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                drawLine(accent, Offset(0f, y), Offset(size.width * ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f), y), size.height, StrokeCap.Round)
            }
        })
}

@Composable
fun LiquidSheet(onDismissRequest: () -> Unit, partiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = !partiallyExpanded)
    val scope = rememberCoroutineScope()
    val haptics = rememberIosHaptics()
    var pull by remember { mutableFloatStateOf(0f) }
    val stretch by animateFloatAsState(pull, spring(.78f, 420f), label = "sheet-resistance")
    val resistance = remember(state) { object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && state.currentValue == SheetValue.Expanded && available.y < 0f)
                pull = (pull - available.y * .12f).coerceAtMost(36f)
            return Offset.Zero
        }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            pull = 0f
            return Velocity.Zero
        }
    } }
    LaunchedEffect(state.currentValue) { if (state.currentValue != SheetValue.Hidden) haptics.selection() }
    ModalBottomSheet(onDismissRequest = onDismissRequest,
        sheetState = state,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        modifier = Modifier.padding(horizontal = 8.dp), containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = .16f), dragHandle = null,
        shape = RoundedCornerShape(34.dp)) {
        // Back leaves the transient layer in one action, regardless of its current detent.
        androidx.activity.compose.BackHandler {
            scope.launch { state.hide(); onDismissRequest() }
        }
        GlassSurface(Modifier.fillMaxWidth().nestedScroll(resistance).graphicsLayer {
            scaleY = 1f + if (size.height > 0f) stretch / size.height else 0f
            transformOrigin = TransformOrigin(.5f, 1f)
        }, radius = 34.dp, sampleContent = true, interactive = false, sheet = true) {
            Column {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 2.dp)
                    .size(34.dp, 5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .22f)))
                content()
            }
        }
    }
}
