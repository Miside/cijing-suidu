package com.yy.suidu

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class IosHaptics(private val view: View) {
    fun selection() { view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK) }
    fun toggle(on: Boolean) { view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) {
        if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
    } else HapticFeedbackConstants.CONTEXT_CLICK) }
    fun rating(rating: Int) { view.performHapticFeedback(if (rating == 2) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK) }
}
@Composable fun rememberIosHaptics(): IosHaptics {
    val view = LocalView.current
    return remember(view) { IosHaptics(view) }
}

data class GlassTuning(val rim: Float = 1f, val refraction: Float = 1f, val tint: Float = 1f, val highlight: Float = 1f)
val LocalGlassTuning = staticCompositionLocalOf { GlassTuning() }

@Composable fun IosThinProgress(modifier: Modifier = Modifier) {
    val movement by rememberInfiniteTransition(label = "progress").animateFloat(-.35f, 1.05f,
        infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "progress-position")
    val tint = MaterialTheme.colorScheme.primary
    Canvas(modifier.height(2.dp).semantics { contentDescription = "正在处理" }) {
        drawRect(tint.copy(alpha = .12f))
        val start = (movement * size.width).coerceAtLeast(0f)
        val end = ((movement + .3f) * size.width).coerceAtMost(size.width)
        if (end > start) drawRect(tint, Offset(start, 0f), Size(end - start, size.height))
    }
}

@Composable fun IosTopTitle(title: String, collapse: Float) {
    val opacity by animateFloatAsState(collapse.coerceIn(0f, 1f), tween(100), label = "title-collapse")
    Box(Modifier.fillMaxWidth().statusBarsPadding().height(44.dp)) {
        GlassSurface(Modifier.fillMaxSize().graphicsLayer { alpha = opacity }, radius = 0.dp,
            sampleContent = true, interactive = false, clear = false) {
            Text(title, Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Canvas(Modifier.fillMaxWidth().height(14.dp).align(Alignment.BottomCenter).offset(y = 14.dp).graphicsLayer { alpha = opacity }) {
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .045f), Color.Transparent)))
        }
    }
}

@Composable fun GlassTuningPanel(value: GlassTuning, onChange: (GlassTuning) -> Unit, dismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    LiquidSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("玻璃调节", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("调整立即生效并保存在这台设备。按住预览区可查看触摸高光。", style = MaterialTheme.typography.bodySmall)
            GlassSurface(Modifier.fillMaxWidth().height(60.dp), radius = 26.dp, sampleContent = true) {
                Text("按住这里预览高光", Modifier.align(Alignment.Center), fontSize = 15.sp)
            }
            @Composable fun control(label: String, current: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
                Text(label + " · " + String.format(java.util.Locale.ROOT, "%.2f", current), fontSize = 15.sp)
                IosValueSlider(value = current, onValueChange = change, range = range,
                    modifier = Modifier.semantics { contentDescription = label })
            }
            control("折射宽度", value.rim, .4f..1.8f) { onChange(value.copy(rim = it)) }
            control("折射强度", value.refraction, .2f..1.8f) { onChange(value.copy(refraction = it)) }
            control("底色浓度", value.tint, .2f..1.8f) { onChange(value.copy(tint = it)) }
            control("触摸高光", value.highlight, 0f..2f) { onChange(value.copy(highlight = it)) }
            IosOutlineButton(onClick = { onChange(GlassTuning()) }, modifier = Modifier.fillMaxWidth()) { Text("恢复默认参数") }
            IosTextButton(onClick = { clipboard.setText(AnnotatedString(value.toString())) }, modifier = Modifier.fillMaxWidth()) { Text("复制当前参数") }
            IosTextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}
