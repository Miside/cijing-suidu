package com.yy.suidu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Semantic system colors; the content plane remains neutral beneath the optical controls. */
object IosPalette {
    val Blue = Color(0xFF007AFF)
    val Green = Color(0xFF34C759)
    val Orange = Color(0xFFFF9500)
    val Indigo = Color(0xFF5856D6)
    val Teal = Color(0xFF30B0C7)
}

@Composable
fun IosChevron(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)) {
    Canvas(modifier.size(12.dp, 18.dp)) {
        val p = Path().apply {
            moveTo(size.width * .32f, size.height * .26f)
            lineTo(size.width * .68f, size.height * .5f)
            lineTo(size.width * .32f, size.height * .74f)
        }
        drawPath(p, tint, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun IosEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun IosBadge(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text, Modifier.clip(CircleShape).background(color.copy(alpha = .09f)).padding(horizontal = 10.dp, vertical = 5.dp),
        fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = color)
}

/** Original, local vector cover. It never substitutes invented metadata for an actual article. */
@Composable
fun IosBookCover(title: String, modifier: Modifier = Modifier, small: Boolean = false) {
    val palette = listOf(
        listOf(Color(0xFFBFE5DE), Color(0xFF3F8586), Color(0xFF164B61)),
        listOf(Color(0xFFF4D8C5), Color(0xFFCA9F8C), Color(0xFF5C637D)),
        listOf(Color(0xFFD4DBEF), Color(0xFF8C9FC9), Color(0xFF4C638E)),
    )[Math.floorMod(title.hashCode(), 3)]
    Box(modifier.shadow(if (small) 3.dp else 12.dp, RoundedCornerShape(5.dp, 12.dp, 12.dp, 5.dp),
        ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .15f))
        .clip(RoundedCornerShape(5.dp, 12.dp, 12.dp, 5.dp)).background(palette[0])) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(palette[0], palette[1])))
            drawCircle(Color(0xFFF9F3E6).copy(alpha = .85f), size.width * .20f, Offset(size.width * .74f, size.height * .34f))
            repeat(3) { i ->
                val y = size.height * (.52f + i * .12f)
                val path = Path().apply {
                    moveTo(0f, y)
                    cubicTo(size.width * .40f, y - size.height * .20f, size.width * .54f, y + size.height * .19f, size.width, y - size.height * .08f)
                    lineTo(size.width, size.height); lineTo(0f, size.height); close()
                }
                drawPath(path, palette[2].copy(alpha = .25f + i * .20f))
            }
            drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .15f), Color.White.copy(alpha = .20f), Color.Transparent)), size = Size(size.width * .09f, size.height))
            drawLine(Color.White.copy(alpha = .4f), Offset(size.width * .06f, 0f), Offset(size.width * .06f, size.height), .5.dp.toPx())
        }
        if (!small) Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 10.dp, top = 17.dp, bottom = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("READ\nIN CONTEXT", fontSize = 14.sp, lineHeight = 17.sp, letterSpacing = 1.1.sp,
                fontWeight = FontWeight.Bold, color = palette[2])
            Text("词境 · 随读", color = Color.White.copy(alpha = .9f), fontSize = 9.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
fun IosImportJourney() {
    IosPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("选择图片", "识别正文", "开始精读").forEachIndexed { index, label ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = if (index == 0) .12f else .05f)), contentAlignment = Alignment.Center) {
                        Text("${index + 1}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (index < 2) IosChevron(Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

@Composable
fun IosSettingsHeading(title: String, symbol: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        IosSymbolTile(symbol, color, Modifier)
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
