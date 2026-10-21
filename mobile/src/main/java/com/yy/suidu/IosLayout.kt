package com.yy.suidu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun IosPageTitle(title: String, subtitle: String, accessory: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 34.sp, lineHeight = 41.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            accessory?.invoke()
        }
        Text(subtitle, Modifier.padding(top = 6.dp), fontSize = 13.sp, lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun IosSectionLabel(title: String, trailing: String = "") {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp)
        if (trailing.isNotBlank()) Text(trailing, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Reading surfaces remain quiet; only the floating control plane uses optical glass. */
@Composable
fun IosPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(24.dp))
        .background(MaterialTheme.colorScheme.surface), content = content)
}

@Composable
fun IosRowDivider() {
    HorizontalDivider(Modifier.padding(start = 18.dp), thickness = .5.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .09f))
}

@Composable
fun IosPressable(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .978f else 1f,
        spring(if (pressed) 1f else .76f, if (pressed) 1000f else 420f), label = "content-press")
    Column(modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(24.dp)).clickable(source, indication = null, role = Role.Button, onClick = onClick), content = content)
}

@Composable
fun IosSymbolTile(index: Int, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(30.dp).clip(RoundedCornerShape(7.dp))
        .background(Brush.verticalGradient(listOf(color, color.copy(red = color.red * .86f, green = color.green * .86f, blue = color.blue * .86f)))), contentAlignment = Alignment.Center) {
        NavGlyph(index, Color.White, Modifier.size(19.dp))
    }
}

@Composable
fun IosMetric(value: String, title: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.8).sp)
        Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Vector paper illustration, decorative and excluded from touch/selection semantics. */
@Composable
fun ReadingIllustration(modifier: Modifier = Modifier, complete: Boolean = false) {
    val blue = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val u = size.minDimension / 160f
        val origin = Offset(size.width / 2, size.height / 2)
        drawCircle(Brush.radialGradient(listOf(blue.copy(alpha = .12f), Color.Transparent), origin, 80*u), 80*u, origin)
        rotate(-13f, origin) {
            drawRoundRect(blue.copy(alpha = .13f), origin - Offset(40*u, 50*u), Size(80*u, 102*u), CornerRadius(12*u))
        }
        rotate(7f, origin) {
            drawRoundRect(Color.Black.copy(alpha = .04f), origin - Offset(36*u, 53*u), Size(82*u, 106*u), CornerRadius(12*u))
            drawRoundRect(Color.White, origin - Offset(40*u, 58*u), Size(80*u, 106*u), CornerRadius(12*u))
            drawRoundRect(blue.copy(alpha = .08f), origin - Offset(40*u, 58*u), Size(80*u, 106*u), CornerRadius(12*u), style = Stroke(u))
            for (i in 0..4) {
                drawRoundRect(blue.copy(alpha = if(i == 2) .45f else .14f), origin + Offset(-25*u, (-35 + i*14)*u),
                    Size((if(i == 4) 30 else 50)*u, 4*u), CornerRadius(2*u))
            }
        }
        if (complete) {
            val p = origin + Offset(37*u, 34*u)
            drawCircle(Color(0xFF34C759), 21*u, p)
            drawLine(Color.White, p + Offset(-9*u, 0f), p + Offset(-2*u, 7*u), 3*u)
            drawLine(Color.White, p + Offset(-2*u, 7*u), p + Offset(11*u, -8*u), 3*u)
        }
    }
}

@Composable
fun IosConfirmDialog(title: String, message: String, confirm: String, cancel: String, enabled: Boolean,
    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(Modifier.widthIn(max = 310.dp).fillMaxWidth(), radius = 28.dp, sheet = true, sampleContent = true, interactive = false) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, Modifier.padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 10.dp),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(message, Modifier.padding(start = 22.dp, end = 22.dp, bottom = 22.dp), fontSize = 13.sp, lineHeight = 20.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f), thickness = .5.dp)
                IosTextButton(onClick = onConfirm, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(confirm, color = MaterialTheme.colorScheme.error, fontSize = 17.sp)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f), thickness = .5.dp)
                IosTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(cancel, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Search uses the same compact inset treatment as the system search field. */
@Composable
fun IosSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .055f))
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(19.dp)) {
            val u = size.width / 20f
            drawCircle(tint, 6*u, Offset(8*u, 8*u), style = Stroke(1.8f*u))
            drawLine(tint, Offset(12.5f*u, 12.5f*u), Offset(18*u, 18*u), 1.8f*u)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp),
            cursorBrush = Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 13.dp)
                .semantics { contentDescription = "搜索英文词元" },
            decorationBox = { field ->
                Box { if (value.isEmpty()) Text("搜索英文词元", color = tint, fontSize = 17.sp); field() }
            })
        if (value.isNotEmpty()) IosTextButton(onClick = { onValueChange("") },
            modifier = Modifier.size(44.dp).semantics { contentDescription = "清除搜索" },
            contentPadding = PaddingValues(0.dp)) { Text("×", fontSize = 24.sp, color = tint) }
    }
}

/** Featured content is always sourced from the user's most recent saved article. */
@Composable
fun IosReadingFeature(title: String, words: Int, onClick: () -> Unit) {
    IosPressable(Modifier.fillMaxWidth(), onClick) {
        IosPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    IosEyebrow("最近加入 · 随时开读")
                    Text(title, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-.6).sp, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("$words 词 · 在语境中积累", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("开始阅读", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Spacer(Modifier.width(6.dp)); IosChevron(tint = Color.White)
                    }
                }
                IosBookCover(title, Modifier.width(112.dp).height(156.dp))
            }
        }
    }
}
