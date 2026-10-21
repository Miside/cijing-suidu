package com.yy.suidu

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ClearGlassTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun screenshot(): Bitmap {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        return instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
    }
    private fun difference(a: Bitmap, b: Bitmap, rect: Rect): Double {
        var total = 0L
        var count = 0
        for (y in rect.top.roundToInt() until rect.bottom.roundToInt() step 2) {
            for (x in rect.left.roundToInt() until rect.right.roundToInt() step 2) {
                val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                total += abs(AndroidColor.red(p) - AndroidColor.red(q)) + abs(AndroidColor.green(p) - AndroidColor.green(q)) + abs(AndroidColor.blue(p) - AndroidColor.blue(q))
                count += 3
            }
        }
        check(count > 0)
        return total.toDouble() / count
    }

    @Test fun clearGlassRefractsPerimeterAndTracksChangingBackdrop() {
        val visible = mutableStateOf(false)
        val shift = mutableFloatStateOf(0f)
        var bounds = Rect.Zero
        val receivedDown = java.util.concurrent.atomic.AtomicBoolean(false)
        ActivityScenario.launch(MobileActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val background = rememberGraphicsLayer()
                val content = rememberGraphicsLayer()
                val scene = remember { GlassScene(background, content, false, true) }
                MaterialTheme {
                    CompositionLocalProvider(LocalGlassScene provides scene) {
                        Box(Modifier.fillMaxSize()) {
                            Canvas(Modifier.fillMaxSize().drawWithContent {
                                background.record { this@drawWithContent.drawContent() }; drawLayer(background)
                            }) {
                                val width = 14.dp.toPx()
                                val colors = listOf(Color(0xFF276FC1), Color(0xFFE8B37B), Color(0xFF654AB0), Color(0xFF78B9AC))
                                for (i in -2..(size.width / width).toInt() + 2) {
                                    for (j in 0..(size.height / width).toInt()) {
                                        drawRect(colors[Math.floorMod(i + j, colors.size)], Offset(i * width + shift.floatValue, j * width), Size(width, width))
                                    }
                                }
                            }
                            Box(Modifier.align(Alignment.Center).size(280.dp, 140.dp).onGloballyPositioned { bounds = it.boundsInWindow() }
                                .pointerInput(Unit) { awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.changes.any { it.pressed && !it.previousPressed }) receivedDown.set(true)
                                    }
                                } }) {
                                if (visible.value) GlassSurface(Modifier.fillMaxSize(), radius = 36.dp) {}
                            }
                        }
                    }
                }
            } }
            val focusDeadline = SystemClock.uptimeMillis() + 5000
            var focused = false
            while (!focused && SystemClock.uptimeMillis() < focusDeadline) {
                scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
                if (!focused) SystemClock.sleep(50)
            }
            assertTrue("Probe window must own input focus", focused)
            val plain = screenshot()
            scenario.onActivity { visible.value = true }
            val glass = screenshot()
            // Avoid rounded corners. The clear center only adds a small tint; the perimeter changes the sampled coordinates.
            val center = Rect(bounds.left + bounds.width * .2f, bounds.top + bounds.height * .4f, bounds.right - bounds.width * .2f, bounds.bottom - bounds.height * .4f)
            val rim = Rect(center.left, bounds.top + bounds.height * .025f, center.right, bounds.top + bounds.height * .09f)
            val centerDelta = difference(plain, glass, center)
            val rimDelta = difference(plain, glass, rim)
            assertTrue("Expected edge refraction: rim=$rimDelta, center=$centerDelta", rimDelta > centerDelta + 8)
            scenario.onActivity { shift.floatValue = bounds.width / 20f }
            val changed = screenshot()
            val backdropDelta = difference(glass, changed, center)
            assertTrue("Glass must sample a live backdrop", backdropDelta > 20)
            val touchTime = SystemClock.uptimeMillis()
            fun touch(action: Int) {
                val event = android.view.MotionEvent.obtain(touchTime, SystemClock.uptimeMillis(), action,
                    bounds.center.x + bounds.width * .22f, bounds.center.y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            val pressedFrame: Bitmap
            // Software emulators can miss several frames just after cold boot. Wait for a
            // rendered, settled state rather than assuming a fixed 500 ms wall-clock delay.
            fun settledFrame(label: String, predicate: (Bitmap) -> Boolean): Bitmap {
                val deadline = SystemClock.uptimeMillis() + 6000
                var previous = screenshot()
                while (SystemClock.uptimeMillis() < deadline) {
                    val next = screenshot()
                    val settled = difference(previous, next, center) < .25
                    previous.recycle()
                    if (settled && predicate(next)) return next
                    previous = next
                }
                previous.recycle()
                error("Glass did not reach a stable $label frame within 6 seconds")
            }
            try {
                touch(android.view.MotionEvent.ACTION_DOWN)
                val inputDeadline = SystemClock.uptimeMillis() + 2000
                while (!receivedDown.get() && SystemClock.uptimeMillis() < inputDeadline) SystemClock.sleep(20)
                assertTrue("Injected touch must reach the glass probe before checking its rendering", receivedDown.get())
                pressedFrame = settledFrame("pressed") { difference(changed, it, center) > 1 }
            }
            finally { touch(android.view.MotionEvent.ACTION_UP) }
            val releasedFrame = settledFrame("released") { difference(changed, it, center) < 1 }
            val touchDelta = difference(changed, pressedFrame, center)
            val returnDelta = difference(changed, releasedFrame, center)
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            java.io.File(context.getExternalFilesDir(null), "glass-optics-probe.json").writeText(
                org.json.JSONObject().put("api", android.os.Build.VERSION.SDK_INT).put("center_delta", centerDelta)
                    .put("rim_delta", rimDelta).put("backdrop_change_delta", backdropDelta)
                    .put("touch_delta", touchDelta).put("release_delta", returnDelta).toString())
            java.io.File(context.getExternalFilesDir(null), "glass-optics-pressed.png").outputStream().use { pressedFrame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            java.io.File(context.getExternalFilesDir(null), "glass-optics-probe.png").outputStream().use { changed.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("Glass must react visibly to a held touch: $touchDelta", touchDelta > 1)
            assertTrue("Released glass must settle back without accumulated drift: $returnDelta", returnDelta < 1)
            plain.recycle(); glass.recycle(); changed.recycle(); pressedFrame.recycle(); releasedFrame.recycle()
        }
    }

    @SdkSuppress(minSdkVersion = 33)
    @Test fun runtimeLensShaderCompilesAndUpdatesOnSupportedAndroid() {
        val lens = createClearLensEffect()
        assertNotNull("AGSL lens compilation failed", lens)
        val first = lens!!.update(Size(400f, 180f), 50f, 30f, 16f, Offset(100f, 80f), 0f)
        assertSame(first, lens.update(Size(400f, 180f), 50f, 30f, 16f, Offset(100f, 80f), 0f))
        assertNotSame(first, lens.update(Size(400f, 180f), 50f, 30f, 16f, Offset(140f, 80f), 1f))
        assertNotNull(lens.update(Size(400f, 180f), 50f, 30f, 16f, Offset(140f, 80f), 1f,
            blurRadius = 8f, padding = 32f, regular = 1f, dark = true,
            selection = LensSelection(Offset(100f, 90f), Size(90f, 100f), .75f)))
    }
}
