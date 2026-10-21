package com.yy.suidu

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import kotlin.math.min
import kotlin.math.pow

/** Clear glass samples the actual backdrop. Text is never passed through this renderer. */
interface ClearLensEffect {
    fun update(size: Size, radius: Float, band: Float, bend: Float, touch: Offset, pressed: Float,
        blurRadius: Float = 0f, padding: Float = 0f, regular: Float = 0f, dark: Boolean = false,
        selection: LensSelection? = null): RenderEffect
}

data class LensSelection(val center: Offset, val size: Size, val lift: Float)

fun createClearLensEffect(): ClearLensEffect? =
    if (Build.VERSION.SDK_INT >= 33) runCatching { ShaderClearLens() }.getOrNull() else null

@RequiresApi(33)
private class ShaderClearLens : ClearLensEffect {
    private val shader = android.graphics.RuntimeShader(CLEAR_LENS_SHADER)
    private var previous: List<Float> = emptyList()
    private var cached: RenderEffect? = null
    override fun update(size: Size, radius: Float, band: Float, bend: Float, touch: Offset, pressed: Float,
        blurRadius: Float, padding: Float, regular: Float, dark: Boolean, selection: LensSelection?): RenderEffect {
        val values = listOf(size.width, size.height, radius, band, bend, touch.x, touch.y, pressed, blurRadius,
            padding, regular, if (dark) 1f else 0f, selection?.center?.x ?: 0f, selection?.center?.y ?: 0f,
            selection?.size?.width ?: 0f, selection?.size?.height ?: 0f, selection?.lift ?: 0f)
        if (values == previous) return cached!!
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("corner", radius)
        shader.setFloatUniform("band", band)
        shader.setFloatUniform("bend", bend)
        shader.setFloatUniform("touchPoint", touch.x, touch.y)
        shader.setFloatUniform("pressed", pressed)
        shader.setFloatUniform("padding", padding)
        shader.setFloatUniform("regular", regular)
        shader.setFloatUniform("dark", if (dark) 1f else 0f)
        shader.setFloatUniform("selection", values[12], values[13], values[14], values[15])
        shader.setFloatUniform("lift", values[16])
        previous = values
        val optical = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
        val combined = if (blurRadius > 0f) android.graphics.RenderEffect.createChainEffect(optical,
            android.graphics.RenderEffect.createBlurEffect(blurRadius, blurRadius, android.graphics.Shader.TileMode.CLAMP)) else optical
        return combined.asComposeRenderEffect().also { cached = it }
    }
}

internal const val CLEAR_LENS_SHADER = """
uniform shader backdrop;
uniform float2 resolution;
uniform float corner;
uniform float band;
uniform float bend;
uniform float2 touchPoint;
uniform float pressed;
uniform float padding;
uniform float regular;
uniform float dark;
uniform float4 selection;
uniform float lift;

float roundedDistance(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - halfSize + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}
float2 roundedNormal(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - halfSize + radius;
    float2 outside = max(q, 0.0);
    float2 n = length(outside) > 0.001 ? normalize(outside) :
        (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
    return n * sign(p);
}
float rayShift(float depth, float width, float thickness) {
    float u = 1.0 - clamp(depth / max(width, 1.0), 0.001, 1.0);
    float h = pow(max(1.0 - pow(u, 4.0), 0.0001), 0.25);
    float slope = min(pow(u / h, 3.0), 16.0);
    float nz = inversesqrt(1.0 + slope * slope);
    float eta = 0.666667;
    float c = eta * nz - sqrt(max(1.0 - eta * eta * (1.0 - nz * nz), 0.0));
    return -c * slope * nz / max(eta - c * nz, 0.01) * thickness;
}
half4 sampleScene(float2 uv) {
    // Overscan preserves the environment outside the silhouette instead of stretching its last pixel.
    return backdrop.eval(clamp(uv + padding, float2(0.75), resolution + 2.0 * padding - 0.75));
}
half4 main(float2 coord) {
    float2 xy = coord - padding;
    float2 p = xy - resolution * 0.5;
    float d = roundedDistance(p, resolution * 0.5, corner);
    float depth = max(-d, 0.0);
    float edge = 1.0 - smoothstep(0.0, band, depth);
    float2 normal = roundedNormal(p, resolution * 0.5, corner);
    float2 uv = xy - normal * rayShift(depth, band, bend * (1.0 + pressed * 0.12));
    float selected = 0.0;
    float selectionRim = 0.0;
    if (selection.z > 1.0) {
        float2 sp = xy - selection.xy;
        float sr = selection.w * 0.5;
        float sd = roundedDistance(sp, selection.zw * 0.5, sr);
        selected = 1.0 - smoothstep(-0.75, 0.75, sd);
        float2 sn = roundedNormal(sp, selection.zw * 0.5, sr);
        // A raised region in the same material: no recursively captured second glass layer.
        uv -= sn * rayShift(max(-sd, 0.0), min(band, sr * 0.8), bend * (0.18 + lift * 0.48)) * selected;
        selectionRim = exp(-abs(sd + 0.8) * 0.9) * (0.15 + lift * 0.28);
    }
    float2 fingerDelta = xy - touchPoint;
    float fingerRadius = max(min(resolution.x, resolution.y) * 0.75, 1.0);
    float bulge = exp(-dot(fingerDelta, fingerDelta) / (fingerRadius * fingerRadius));
    uv -= fingerDelta * bulge * pressed * 0.075;
    half4 color = sampleScene(uv);
    float fringe = min(bend * 0.018, 0.8) * edge * edge;
    color.r = sampleScene(uv + normal * fringe).r;
    color.b = sampleScene(uv - normal * fringe).b;
    float luma = dot(float3(color.rgb), float3(0.2126, 0.7152, 0.0722));
    half3 material = mix(half3(0.985, 0.989, 1.0), half3(0.065, 0.073, 0.09), half(dark));
    float adaptiveTint = mix(0.24 + (1.0 - luma) * 0.2, 0.35 + luma * 0.15, dark) * regular;
    color.rgb = mix(color.rgb, material * color.a, half(adaptiveTint));
    color.rgb = mix(color.rgb, half3(color.a), half(selected * mix(0.18, 0.10, dark)));
    float2 lightDirection = normalize(float2(-0.65, -0.76) + (touchPoint / resolution - 0.5) * pressed * 0.9);
    float bright = pow(max(dot(normal, lightDirection), 0.0), 5.0);
    float reflected = pow(max(dot(normal, -lightDirection), 0.0), 7.0);
    float rim = exp(-abs(d + 1.0) * 1.15);
    float shoulder = exp(-depth / max(band * 0.16, 1.0));
    half3 environment = sampleScene(xy + normal * min(padding, band)).rgb;
    color.rgb = mix(color.rgb, environment, half(shoulder * 0.12));
    // Dark inner meniscus, followed by a concentrated rim and wider, softer shoulder.
    color.rgb *= half(1.0 - exp(-abs(depth - 2.7) * 0.6) * (0.035 + 0.04 * regular));
    float light = bright * shoulder * 0.28 + (bright * 0.48 + reflected * 0.32) * rim + selectionRim;
    // Maintain premultiplied alpha and preserve saturation in the clear center.
    color.rgb = mix(color.rgb, half3(color.a), half(clamp(light + bulge * pressed * 0.075, 0.0, 0.75)));
    return color;
}
"""

/** API 31/32: cache the curved perimeter and sample it in narrow inward-shifting bands. */
class CompatibilityLens {
    private var cachedSize = Size.Zero
    private var cachedRadius = -1f
    private var cachedBand = -1f
    private var rings = emptyList<Path>()
    fun draw(scope: DrawScope, layer: GraphicsLayer, radius: Float, band: Float, bend: Float) = with(scope) {
    val r = min(radius, min(size.width, size.height) / 2f)
    val b = min(band, min(size.width, size.height) / 3f)
    if (size != cachedSize || r != cachedRadius || b != cachedBand) {
        fun rounded(inset: Float) = Path().apply {
            addRoundRect(RoundRect(Rect(inset, inset, size.width - inset, size.height - inset), (r - inset).coerceAtLeast(0f), (r - inset).coerceAtLeast(0f)))
        }
        rings = List(8) { i -> Path.combine(PathOperation.Difference, rounded(b * i / 8), rounded(b * (i + 1) / 8)) }
        cachedSize = size; cachedRadius = r; cachedBand = b
    }
    drawLayer(layer)
    // One draw per curved ring, rather than separate repeated draws for all four sides.
    rings.forEachIndexed { i, ring ->
        val t = (i + .5f) / rings.size
        val u = 1f - t
        val height = (1f - u.pow(4f)).pow(.25f)
        val slope = (u / height).pow(3f)
        val nz = 1f / kotlin.math.sqrt(1f + slope * slope)
        val coefficient = 2f / 3f * nz - kotlin.math.sqrt(1f - 4f / 9f * (1f - nz * nz))
        val shift = (-coefficient * slope * nz / (2f / 3f - coefficient * nz) * bend).coerceAtMost(size.minDimension * .2f)
        clipPath(ring) {
            scale(size.width / (size.width - 2 * shift), size.height / (size.height - 2 * shift), center) { drawLayer(layer) }
        }
    }
    }
}
