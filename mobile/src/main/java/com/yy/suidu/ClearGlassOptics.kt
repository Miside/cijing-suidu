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
    fun update(size: Size, radius: Float, band: Float, bend: Float, touch: Offset, pressed: Float, blurRadius: Float = 0f): RenderEffect
}

fun createClearLensEffect(): ClearLensEffect? =
    if (Build.VERSION.SDK_INT >= 33) runCatching { ShaderClearLens() }.getOrNull() else null

@RequiresApi(33)
private class ShaderClearLens : ClearLensEffect {
    private val shader = android.graphics.RuntimeShader(CLEAR_LENS_SHADER)
    private var previous: List<Float> = emptyList()
    private var cached: RenderEffect? = null
    override fun update(size: Size, radius: Float, band: Float, bend: Float, touch: Offset, pressed: Float, blurRadius: Float): RenderEffect {
        val values = listOf(size.width, size.height, radius, band, bend, touch.x, touch.y, pressed, blurRadius)
        if (values == previous) return cached!!
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("corner", radius)
        shader.setFloatUniform("band", band)
        shader.setFloatUniform("bend", bend)
        shader.setFloatUniform("touchPoint", touch.x, touch.y)
        shader.setFloatUniform("pressed", pressed)
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

float roundedDistance(float2 p) {
    float2 q = abs(p) - resolution * 0.5 + corner;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - corner;
}
half4 main(float2 xy) {
    float2 p = xy - resolution * 0.5;
    float d = roundedDistance(p);
    float depth = max(-d, 0.0);
    // A fourth-root convex shoulder joins the flat center smoothly.
    // Project a refracted ray through a slab, rather than just scaling pixels at the rim.
    float t = clamp(depth / max(band, 1.0), 0.001, 1.0);
    float u = 1.0 - t;
    float height = pow(max(1.0 - pow(u, 4.0), 0.0001), 0.25);
    float slope = min(pow(u / height, 3.0), 16.0);
    float edge = 1.0 - smoothstep(0.0, band, depth);
    float2 grad = float2(
        roundedDistance(p + float2(1.0, 0.0)) - roundedDistance(p - float2(1.0, 0.0)),
        roundedDistance(p + float2(0.0, 1.0)) - roundedDistance(p - float2(0.0, 1.0))
    );
    float2 normal = grad / max(length(grad), 0.001);
    float3 surfaceNormal = normalize(float3(normal * slope, 1.0));
    float eta = 1.0 / 1.5;
    float k = 1.0 - eta * eta * (1.0 - surfaceNormal.z * surfaceNormal.z);
    float3 ray = float3(0.0, 0.0, -eta) + (eta * surfaceNormal.z - sqrt(max(k, 0.0))) * surfaceNormal;
    float2 uv = xy + ray.xy / max(-ray.z, 0.01) * bend * (1.0 + pressed * 0.16);
    float2 fingerDelta = xy - touchPoint;
    float fingerRadius = max(min(resolution.x, resolution.y) * 0.75, 1.0);
    float bulge = exp(-dot(fingerDelta, fingerDelta) / (fingerRadius * fingerRadius));
    uv -= fingerDelta * bulge * pressed * 0.075;
    uv = clamp(uv, float2(0.75), resolution - 0.75);
    half4 color = backdrop.eval(uv);
    float fringe = min(bend * 0.035, 1.4) * edge * edge;
    color.r = backdrop.eval(clamp(uv + normal * fringe, float2(0.75), resolution - 0.75)).r;
    color.b = backdrop.eval(clamp(uv - normal * fringe, float2(0.75), resolution - 0.75)).b;
    float2 lightDirection = normalize(float2(-0.65, -0.76) + (touchPoint / resolution - 0.5) * pressed * 0.9);
    float bright = pow(max(dot(normal, lightDirection), 0.0), 7.0);
    float reflected = pow(max(dot(normal, -lightDirection), 0.0), 9.0);
    float rim = exp(-abs(d + 1.15) * 1.25);
    float shoulder = exp(-depth / max(band * 0.23, 1.0));
    float light = bright * shoulder * 0.50 + reflected * rim * 0.35;
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
