package com.yy.suidu

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileUiTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private lateinit var store: LocalStore
    @Before fun prepare() {
        automation.setServiceInfo(automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS })
        context.deleteDatabase("suidu-local.db"); store = LocalStore(context)
        context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("suidu-ui", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() {
        store.close(); context.deleteDatabase("suidu-local.db")
        context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("suidu-ui", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun capture(name: String) {
        SystemClock.sleep(400)
        val bitmap = automation.takeScreenshot() ?: error("Screenshot unavailable")
        java.io.File(context.getExternalFilesDir(null), "glass-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private fun seed(): Pair<String, Occurrence> {
        val id = store.createDraft(emptyList())
        store.saveOcr(id, OcrResult("界面测试文章", "Banks work.", emptyList()))
        store.saveBatch(id, LanguageCore.batches("Banks work.").first(), JSONObject("""{"w":[[0,"bank","NOUN","银行"],[1,"work","VERB","工作"]],"t":"银行运作。","p":[]}"""))
        store.saveDefinitions(mapOf("bank" to listOf("n. 银行", "n. 河岸"), "work" to listOf("v. 工作")))
        store.publish(id)
        return id to store.occurrences(articleId = id).first()
    }
    /** Reviewable visuals from the real app, with disposable local content and zero API requests. */
    @Test fun liquidIosVisualWalkthroughAndScrollDock() {
        val title = "在日常里，发现更大的世界"
        val text = "Small habits shape a better day.\n\nReading opens a window to the world. A quiet moment can become a new beginning."
        val dictionary = mapOf(
            "small" to Triple("small", "ADJ", "微小的"), "habits" to Triple("habit", "NOUN", "习惯"),
            "shape" to Triple("shape", "VERB", "塑造"), "a" to Triple("a", "DET", "一个"),
            "better" to Triple("better", "ADJ", "更好的"), "day" to Triple("day", "NOUN", "一天"),
            "reading" to Triple("reading", "NOUN", "阅读"), "opens" to Triple("open", "VERB", "打开"),
            "window" to Triple("window", "NOUN", "窗口"), "to" to Triple("to", "ADP", "通向"),
            "the" to Triple("the", "DET", "这"), "world" to Triple("world", "NOUN", "世界"),
            "quiet" to Triple("quiet", "ADJ", "安静的"), "moment" to Triple("moment", "NOUN", "时刻"),
            "can" to Triple("can", "AUX", "可以"), "become" to Triple("become", "VERB", "成为"),
            "new" to Triple("new", "ADJ", "新的"), "beginning" to Triple("beginning", "NOUN", "开始"))
        val id = store.createDraft(emptyList())
        store.saveOcr(id, OcrResult(title, text, emptyList()))
        for (batch in LanguageCore.batches(text)) {
            val base = batch.paragraph.tokens.first().id
            val rows = org.json.JSONArray()
            batch.tokens.forEach { token ->
                val (lemma, pos, meaning) = dictionary.getValue(token.surface.lowercase())
                rows.put(org.json.JSONArray().put(token.id - base).put(lemma).put(pos).put(meaning))
            }
            store.saveBatch(id, batch, JSONObject().put("w", rows).put("t", if (batch.paragraph.id == 0)
                "微小的习惯，塑造更好的一天。" else "阅读打开了一扇通向世界的窗。安静的片刻，也能成为一个新的开始。").put("p", org.json.JSONArray()))
        }
        store.saveDefinitions(dictionary.values.associate { it.first to listOf(it.third) })
        store.publish(id)
        val word = store.occurrences(articleId = id).first { it.lemma == "habit" }
        store.rate(word, 0, 0L)
        for (dark in listOf(false, true)) {
            context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).edit().putInt("dark", if (dark) 2 else 1).commit()
            val mode = if (dark) "dark" else "light"
            ActivityScenario.launch(MobileActivity::class.java).use {
                node(title); capture("showcase-library-$mode")
                if (!dark) {
                    val expanded = Rect(); node("导航书库").getBoundsInScreen(expanded)
                    val frame = stableScreenshot()
                    swipeDown(frame.width * .5f, frame.height * .72f, frame.height * .30f)
                    SystemClock.sleep(750)
                    val compact = Rect(); node("导航书库").getBoundsInScreen(compact)
                    assertTrue("Dock must shrink on content scroll", compact.height() < expanded.height())
                    capture("showcase-dock-compact")
                    swipeDown(frame.width * .5f, frame.height * .30f, frame.height * .76f)
                    frame.recycle()
                    SystemClock.sleep(650)
                    val restored = Rect(); node("导航书库").getBoundsInScreen(restored)
                    assertTrue("Dock must expand when scrolling back", restored.height() > compact.height())
                }
                click(title); node("正文段落 1"); capture("showcase-reader-$mode")
                val sentence = Rect(); node("Small habits shape a better day.").getBoundsInScreen(sentence)
                tap(sentence.left + 50f, sentence.top + 35f)
                node("本文释义"); capture("showcase-lookup-$mode")
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                SystemClock.sleep(500)
                node("正文段落 1")
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                node("我的书库")
                click("导入"); node("新建文章 · 选择图片"); capture("showcase-import-$mode")
                click("词库"); node("搜索英文词元"); capture("showcase-vocabulary-$mode")
                click("复习"); node("显示本文释义"); SystemClock.sleep(3200); capture("showcase-review-$mode")
                click("显示本文释义"); node("习惯（n. 名词）"); capture("showcase-answer-$mode")
                click("设置"); node("尚未填写 API Key"); capture("showcase-settings-$mode")
                val frame = stableScreenshot()
                swipeDown(frame.width * .5f, frame.height * .78f, frame.height * .26f)
                frame.recycle(); node("液态玻璃"); capture("showcase-appearance-$mode")
            }
        }
        assertEquals(listOf(0L, 0L, 0L, 0L), store.usageTotals())
    }
    private fun find(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.text?.toString() == text || root.contentDescription?.toString() == text) return root
        for (i in 0 until root.childCount) find(root.getChild(i), text)?.let { return it }
        return null
    }
    private fun editable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isEditable) return root
        for (i in 0 until root.childCount) editable(root.getChild(i))?.let { return it }
        return null
    }
    private fun node(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 8000
        do {
            // Animated answers can render correctly while UiAutomation keeps the old node tree.
            if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
            else automation.setServiceInfo(automation.serviceInfo)
            find(automation.rootInActiveWindow, text)?.let { return it }
            automation.windows.forEach { window -> find(window.root,text)?.let { return it } }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        automation.takeScreenshot()?.let { image ->
            java.io.File(context.getExternalFilesDir(null), "test-ui-failure.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
        val tree=StringBuilder()
        fun dump(root: AccessibilityNodeInfo?, depth: Int) {
            if(root==null) return
            tree.append(" ".repeat(depth)).append(root.className).append(" text=").append(root.text).append(" description=").append(root.contentDescription).append('\n')
            for(i in 0 until root.childCount) dump(root.getChild(i),depth+1)
        }
        dump(automation.rootInActiveWindow,0)
        automation.windows.forEach { dump(it.root,0) }
        java.io.File(context.getExternalFilesDir(null),"test-ui-failure-tree.txt").writeText(tree.toString())
        error("UI text not found: $text")
    }
    private fun click(text: String) {
        val deadline=SystemClock.uptimeMillis()+3000
        do {
            var n: AccessibilityNodeInfo? = node(text)
            while (n != null && !n.isClickable) n = n.parent
            if (n?.isEnabled == true && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                return
            }
            // Navigation can replace a semantics node during its transition. Re-read the current tree.
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis()<deadline)
        capture("click-failure")
        error("UI cannot click: $text")
    }
    private fun tap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val e = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(e, true)
            e.recycle()
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(80)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    private fun swipeDown(x: Float, from: Float, to: Float) {
        val start = SystemClock.uptimeMillis()
        for (i in 0..20) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, from + (to - from) * i / 20f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
            SystemClock.sleep(20)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    private fun stableScreenshot(): android.graphics.Bitmap {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(850)
        return automation.takeScreenshot() ?: error("Screenshot unavailable")
    }
    private fun assertSameReaderPixels(before: android.graphics.Bitmap, after: android.graphics.Bitmap, bounds: Rect, label: String) {
        var difference = 0L
        var samples = 0
        for (y in bounds.top until bounds.bottom step 2) for (x in bounds.left until bounds.right step 2) {
            val a = before.getPixel(x,y); val b = after.getPixel(x,y)
            difference += kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
                kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
            samples += 3
        }
        val mean = difference.toDouble() / samples
        val report = java.io.File(context.getExternalFilesDir(null), "blur-check-results.json")
        val results = if (report.exists()) org.json.JSONArray(report.readText()) else org.json.JSONArray()
        results.put(JSONObject().put("dismissal", label).put("mean_rgb_difference", mean).put("threshold", 3.0))
        report.writeText(results.toString())
        java.io.File(context.getExternalFilesDir(null), "blur-$label-after.png").outputStream().use {
            after.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        assertTrue("Reader must become sharp again after $label; RGB difference=$mean", mean < 3.0)
    }
    private fun openWord() {
        val bounds = Rect()
        node("Banks work.").getBoundsInScreen(bounds)
        tap(bounds.left + 50f, bounds.top + 35f)
        assertNotNull(node("本文释义"))
        SystemClock.sleep(650)
    }
    private fun expandLookup() {
        val frame = stableScreenshot()
        val bounds = Rect(); node("Banks").getBoundsInScreen(bounds)
        swipeDown(frame.width * .5f, bounds.top + 12f, frame.height * .13f)
        frame.recycle(); SystemClock.sleep(550)
    }
    @Test fun wordSheetStartsAtMediumAndExpandsWithoutChangingReader() {
        seed()
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("界面测试文章"); openWord()
            val medium = Rect(); node("Banks").getBoundsInScreen(medium)
            val frame = stableScreenshot()
            assertTrue("Lookup should open in the lower half", medium.top > frame.height * .42f)
            capture("lookup-medium")
            expandLookup()
            val expanded = Rect(); node("Banks").getBoundsInScreen(expanded)
            assertTrue("Lookup must expand when dragged up", expanded.top < medium.top - frame.height * .2f)
            capture("lookup-expanded")
            assertNotNull(node("查看这个词的所有出现位置"))
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            assertNotNull(node("Banks work.")); frame.recycle()
        }
    }
    @Test fun predictiveReaderBackCancelsThenCommitsWithoutLosingArticle() {
        seed()
        ActivityScenario.launch(MobileActivity::class.java).use { scenario ->
            click("界面测试文章"); node("正文段落 1"); SystemClock.sleep(650)
            val before = Rect(); node("Banks work.").getBoundsInScreen(before)
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 300f, 0f, 0)) }
            SystemClock.sleep(100)
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(300f, 300f, .48f, 0)) }
            SystemClock.sleep(160)
            val during = Rect(); node("Banks work.").getBoundsInScreen(during)
            assertTrue("Reader must follow back progress", during.left > before.left + 100)
            capture("back-progress")
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
            SystemClock.sleep(800)
            val restored = Rect(); node("Banks work.").getBoundsInScreen(restored)
            assertEquals(before.left, restored.left); assertEquals(before.top, restored.top)
            capture("back-cancelled")
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 300f, 0f, 0)) }
            SystemClock.sleep(100)
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(500f, 300f, .8f, 0)) }
            SystemClock.sleep(100)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            SystemClock.sleep(500); assertNotNull(node("我的书库"))
            assertEquals(1, store.articles().size)
            capture("back-completed")
        }
    }
    @Test fun manualReviewTranslationAndPreferencesPersistWithoutNetwork() {
        val (_, word) = seed(); store.rate(word, 0, 0)
        context.getSharedPreferences("suidu-ui", android.content.Context.MODE_PRIVATE).edit().putBoolean("autoTranslate", false).commit()
        ActivityScenario.launch(MobileActivity::class.java).use { scenario ->
            click("复习"); click("显示本文释义")
            assertNotNull(node("翻译此句")); assertNotNull(node("自动翻译已关闭，点击后再准备译文。"))
            click("翻译此句"); assertNotNull(node("银行运作。"))
            assertEquals(listOf(0L,0L,0L,0L), store.usageTotals())
            scenario.recreate(); click("设置")
            val frame = stableScreenshot()
            repeat(2) { swipeDown(frame.width * .5f, frame.height * .78f, frame.height * .25f) }
            assertFalse(node("自动翻译开关").isChecked)
            click("段落编号开关")
            assertTrue(context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).getBoolean("paragraphNumbers", false))
            repeat(2) { swipeDown(frame.width * .5f, frame.height * .78f, frame.height * .25f) }
            repeat(5) { click("版本号") }
            node("玻璃调节"); capture("glass-tuning")
            assertNotNull(node("折射强度"))
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            frame.recycle()
        }
    }
    @Test fun wordSheetsRestoreSharpReaderForEveryDismissal() {
        seed()
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("界面测试文章")
            assertNotNull(node("2 词次 · 点词查看本文释义"))
            val bounds = Rect()
            node("Banks work.").getBoundsInScreen(bounds)
            val before = stableScreenshot()
            java.io.File(context.getExternalFilesDir(null), "blur-reader-initial.png").outputStream().use {
                before.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            for (mode in listOf("scrim","swipe","back","rating")) {
                openWord()
                when (mode) {
                    "scrim" -> tap(before.width * .8f, before.height * .12f)
                    "swipe" -> {
                        val word = Rect(); node("Banks").getBoundsInScreen(word)
                        swipeDown(before.width * .5f, word.top + 20f, before.height - 150f)
                    }
                    "back" -> assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                    else -> click("记住了")
                }
                assertNotNull(node("2 词次 · 点词查看本文释义"))
                val after = stableScreenshot()
                assertNull("Sheet should be dismissed after $mode", find(automation.rootInActiveWindow,"本文释义"))
                assertSameReaderPixels(before,after,bounds,mode)
                after.recycle()
            }
            assertEquals(2,store.vocabulary().sumOf { it.count })
            before.recycle()
        }
    }
    @Test fun lookupToHistoryAndHistoryToArticleRestoreSharpReader() {
        seed()
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("界面测试文章")
            assertNotNull(node("2 词次 · 点词查看本文释义"))
            val bounds = Rect(); node("Banks work.").getBoundsInScreen(bounds)
            val before = stableScreenshot()
            openWord(); expandLookup()
            click("查看这个词的所有出现位置")
            assertNotNull(node("bank"))
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            assertNotNull(node("2 词次 · 点词查看本文释义"))
            val afterHistory = stableScreenshot()
            assertSameReaderPixels(before,afterHistory,bounds,"history-back")
            afterHistory.recycle()
            openWord(); expandLookup()
            click("查看这个词的所有出现位置")
            click("打开原文 · 界面测试文章")
            assertNotNull(node("2 词次 · 点词查看本文释义"))
            val afterOpen = stableScreenshot()
            assertSameReaderPixels(before,afterOpen,bounds,"history-open-article")
            afterOpen.recycle(); before.recycle()
        }
    }
    @Test fun emptyLibraryAndSettingsUseActualZeroData() {
        ActivityScenario.launch(MobileActivity::class.java).use {
            assertNotNull(node("你的书库还没有文章"))
            capture("home-light")
            click("设置")
            assertNotNull(node("尚未填写 API Key"))
            assertNotNull(node("保存密钥"))
            capture("settings-light")
        }
    }
    @Test fun readerTapShowsRealMeaningAndDoesNotChangeFrequency() {
        val (_, word) = seed()
        ActivityScenario.launch(MobileActivity::class.java).use {
            assertNotNull(node("界面测试文章"))
            capture("library-populated")
            click("界面测试文章")
            // The library preview has the same text. Wait for the reader to finish its DB load.
            assertNotNull(node("2 词次 · 点词查看本文释义"))
            capture("reader-light")
            val bounds = Rect()
            node("Banks work.").getBoundsInScreen(bounds)
            tap(bounds.left + 50f, bounds.top + 35f)
            assertNotNull(node("本文释义"))
            assertNotNull(node("银行（n. 名词）"))
            assertNotNull(node("• n. 河岸"))
            capture("lookup-light")
            val ratingBounds = Rect()
            node("记住了").getBoundsInScreen(ratingBounds)
            tap(ratingBounds.exactCenterX(), ratingBounds.exactCenterY())
            val deadline = SystemClock.uptimeMillis() + 3000
            while (store.reviewCount() == 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertEquals(1, store.reviewCount())
            assertEquals(2, store.vocabulary().sumOf { it.count })
            assertEquals(1, store.vocabulary().first { it.lemma == word.lemma }.clicks)
        }
    }
    @Test fun reviewShowsStoredOriginalContextAndSchedulesAnswer() {
        val (_, word) = seed()
        store.rate(word, 0, 0L)
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("复习")
            assertNotNull(node("Banks work."))
            click("显示本文释义")
            assertNotNull(node("银行（n. 名词）"))
            assertNotNull(node("银行运作。"))
            capture("review-light")
            click("记住了")
            assertNotNull(node("当前没有到期的复习"))
            assertTrue(store.due().isEmpty())
            assertEquals(1, store.reviewCount())
        }
    }
    @Test fun glassDarkAndReducedTransparencyRemainNavigable() {
        context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).edit().putInt("dark", 2).putBoolean("glass", true).commit()
        ActivityScenario.launch(MobileActivity::class.java).use {
            assertNotNull(node("你的书库还没有文章"))
            capture("home-dark")
            val recording = automation.executeShellCommand("screenrecord --time-limit 8 --bit-rate 3000000 /sdcard/Android/data/com.yy.suidu/files/glass-navigation.mp4")
            val recordingStarted = SystemClock.uptimeMillis()
            SystemClock.sleep(350)
            click("导入")
            assertNotNull(node("新建文章 · 选择图片"))
            capture("import-dark")
            click("词库")
            assertNotNull(node("搜索英文词元"))
            capture("vocabulary-dark")
            click("复习")
            assertNotNull(node("当前没有到期的复习"))
            capture("review-dark")
            click("书库")
            assertNotNull(node("你的书库还没有文章"))
            val remainder = 8500 - (SystemClock.uptimeMillis() - recordingStarted)
            if (remainder > 0) SystemClock.sleep(remainder)
            recording.close()
        }
        context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).edit().putBoolean("glass", false).commit()
        ActivityScenario.launch(MobileActivity::class.java).use {
            assertNotNull(node("你的书库还没有文章"))
            capture("home-solid-dark")
            click("设置")
            assertNotNull(node("尚未填写 API Key"))
        }
    }
    @Test fun switchingPagesPreservesVocabularySearch() {
        seed()
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("词库")
            assertNotNull(node("搜索英文词元"))
            val input = editable(automation.rootInActiveWindow) ?: error("Missing search input")
            val arguments = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "bank") }
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
            assertNotNull(node("bank"))
            click("复习")
            assertNotNull(node("当前没有到期的复习"))
            click("词库")
            assertNotNull(node("搜索英文词元"))
            SystemClock.sleep(400)
            assertEquals("bank", editable(automation.rootInActiveWindow)?.text?.toString())
            assertNull(find(automation.rootInActiveWindow, "work"))
            capture("vocabulary-search")
        }
    }
    @Test fun iosAppearanceControlsPersistThemeTransparencyAndFont() {
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("设置")
            val frame = stableScreenshot()
            swipeDown(frame.width * .5f, frame.height * .78f, frame.height * .26f)
            frame.recycle()
            click("深色")
            val prefs = context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
            assertEquals(2, prefs.getInt("dark", 0))
            fun matching(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
                if (root == null) return null
                if (predicate(root)) return root
                for (i in 0 until root.childCount) matching(root.getChild(i), predicate)?.let { return it }
                return null
            }
            // Refresh semantics before inspecting the custom controls, just like the regular node helper.
            node("液态玻璃")
            val toggle = matching(automation.rootInActiveWindow) { it.isCheckable } ?: error("Accessible switch missing")
            assertTrue(toggle.isChecked)
            assertTrue(toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertFalse(prefs.getBoolean("glass", true))
            node("液态玻璃")
            val slider = matching(automation.rootInActiveWindow) { it.rangeInfo != null } ?: error("Accessible slider missing")
            assertTrue(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 27f) }))
            assertNotNull(node("正文字号 27 sp"))
            assertEquals(27f, prefs.getFloat("font", 0f), .01f)
            click("浅色")
            assertEquals(1, prefs.getInt("dark", 0))
            node("液态玻璃")
            val restoredToggle = matching(automation.rootInActiveWindow) { it.isCheckable } ?: error("Switch missing")
            assertTrue(restoredToggle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(prefs.getBoolean("glass", false))
            capture("ios-appearance-controls")
        }
    }
    @Test fun draggingGlassNavigationSelectsAndReturnsWithoutBreakingClicks() {
        ActivityScenario.launch(MobileActivity::class.java).use {
            assertNotNull(node("你的书库还没有文章"))
            fun drag(from: String, to: String) {
                val startBounds = Rect(); node(from).getBoundsInScreen(startBounds)
                val endBounds = Rect(); node(to).getBoundsInScreen(endBounds)
                val time = SystemClock.uptimeMillis()
                for (i in 0..24) {
                    val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 24 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                    val x = startBounds.exactCenterX() + (endBounds.exactCenterX() - startBounds.exactCenterX()) * i / 24f
                    val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, startBounds.exactCenterY(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue(automation.injectInputEvent(event, true)); event.recycle()
                    SystemClock.sleep(22)
                }
            }
            val recording = automation.executeShellCommand("screenrecord --time-limit 6 --bit-rate 5000000 /sdcard/Android/data/com.yy.suidu/files/glass-drag-navigation.mp4")
            val started = SystemClock.uptimeMillis(); SystemClock.sleep(300)
            drag("书库", "设置")
            assertNotNull(node("尚未填写 API Key"))
            capture("drag-settings")
            drag("设置", "导入")
            assertNotNull(node("新建文章 · 选择图片"))
            capture("drag-import")
            click("书库")
            assertNotNull(node("你的书库还没有文章"))
            val remaining = 6500 - (SystemClock.uptimeMillis() - started)
            if (remaining > 0) SystemClock.sleep(remaining)
            recording.close()
        }
    }
    private fun longPressText(text: String) {
        val bounds=Rect(); node(text).getBoundsInScreen(bounds)
        val time=SystemClock.uptimeMillis()
        val down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,bounds.left+50f,bounds.top+35f,0)
        down.source=InputDevice.SOURCE_TOUCHSCREEN; assertTrue(automation.injectInputEvent(down,true)); down.recycle()
        SystemClock.sleep(900)
        val up=MotionEvent.obtain(time,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,bounds.left+50f,bounds.top+35f,0)
        up.source=InputDevice.SOURCE_TOUCHSCREEN; assertTrue(automation.injectInputEvent(up,true)); up.recycle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    @Test fun longPressTranslationReusesLocalMeaningAndRestoresReader() {
        seed()
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("界面测试文章"); assertNotNull(node("正文段落 1"))
            longPressText("Banks work.")
            capture("selection-menu")
            click("翻译")
            assertNotNull(node("选段翻译"))
            assertNotNull(node("银行（n. 名词）"))
            assertNotNull(node("已保存的本文释义"))
            capture("selection-word-translation")
            click("返回文章")
            assertNotNull(node("2 词次 · 点词查看本文释义"))
            capture("selection-return-reader")
            assertEquals(2,store.vocabulary().sumOf { it.count })
            assertEquals(listOf(0L,0L,0L,0L),store.usageTotals())
        }
    }
    @Test fun nativeSelectAllTranslationReusesParagraphAndDoesNotCallApi() {
        seed()
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("界面测试文章"); assertNotNull(node("正文段落 1"))
            longPressText("Banks work.")
            // Android's standard selection menu provides native adjustable handles and select all.
            click(context.getString(android.R.string.selectAll))
            click("翻译")
            assertNotNull(node("银行运作。"))
            assertNotNull(node("已保存的段落译文"))
            capture("selection-paragraph-translation")
            assertEquals(listOf(0L,0L,0L,0L),store.usageTotals())
        }
    }
    @Test fun arbitrarySelectedRangeReusesPersistentCacheWithoutAKey() {
        val (id,_)=seed()
        val selection=TextSelection(id,0,0,10,"Banks work.")
        store.saveSelectedTranslation(selection.cacheKey,"银行开展工作")
        ActivityScenario.launch(MobileActivity::class.java).use { scenario ->
            click("界面测试文章"); assertNotNull(node("正文段落 1"))
            scenario.onActivity { activity -> androidx.lifecycle.ViewModelProvider(activity)[MobileModel::class.java].translateSelection(selection) }
            assertNotNull(node("银行开展工作"))
            assertNotNull(node("本地选段翻译缓存"))
            capture("selection-cached-translation")
            click("返回文章")
            assertEquals(2,store.vocabulary().sumOf { it.count })
            assertEquals(listOf(0L,0L,0L,0L),store.usageTotals())
        }
    }
    @Test fun continuousParagraphsKeepSelectionAndOptionalTranslations() {
        val text="Banks work.\n\nPeople save money in banks."
        val id=store.createDraft(emptyList())
        store.saveOcr(id,OcrResult("分段阅读测试",text,emptyList()))
        val dictionary=mapOf("banks" to Triple("bank","NOUN","银行"),"work" to Triple("work","VERB","工作"),
            "people" to Triple("people","NOUN","人们"),"save" to Triple("save","VERB","储存"),
            "money" to Triple("money","NOUN","钱"),"in" to Triple("in","ADP","在…里面"))
        for(batch in LanguageCore.batches(text)) {
            val base=batch.paragraph.tokens.first().id
            val rows=org.json.JSONArray()
            batch.tokens.forEach { token -> val (lemma,pos,meaning)=dictionary.getValue(token.surface.lowercase());
                rows.put(org.json.JSONArray().put(token.id-base).put(lemma).put(pos).put(meaning)) }
            store.saveBatch(id,batch,JSONObject().put("w",rows).put("t",if(batch.number==0) "银行运作。" else "人们把钱存进银行。").put("p",org.json.JSONArray()))
        }
        store.saveDefinitions(dictionary.values.associate { it.first to listOf(it.third) }); store.publish(id)
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("分段阅读测试")
            assertNotNull(node("正文段落 1")); assertNotNull(node("正文段落 2"))
            assertNotNull(node("People save money in banks."))
            capture("reader-paragraph-frames")
            click("显示段落译文")
            assertNotNull(node("银行运作。")); assertNotNull(node("人们把钱存进银行。"))
            capture("reader-paragraph-translations")
            assertEquals(7,store.vocabulary().sumOf { it.count })
        }
    }
    @Test fun deletingArticleRequiresConfirmationAndRemovesOnlyThatArticle() {
        seed()
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("文章操作 · 界面测试文章"); click("删除文章"); assertNotNull(node("删除这篇文章？"))
            click("保留文章"); assertEquals(1,store.articles().size)
            click("文章操作 · 界面测试文章"); click("删除文章"); click("确认删除文章")
            assertNotNull(node("你的书库还没有文章"))
            assertEquals(0,store.reviewCount()); assertTrue(store.vocabulary().isEmpty())
            capture("deleted-article")
        }
    }
    @Test fun removingReviewWordKeepsReadableArticle() {
        val (_,word)=seed(); store.rate(word,0,0)
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("复习")
            val frame = stableScreenshot(); swipeDown(frame.width * .5f, frame.height * .75f, frame.height * .35f); frame.recycle()
            click("将 bank 移出复习队列")
            assertNotNull(node("当前没有到期的复习"))
            assertEquals(0,store.reviewCount()); assertEquals(2,store.vocabulary().sumOf { it.count })
            click("书库"); click("界面测试文章"); assertNotNull(node("正文段落 1"))
        }
    }
    @Test fun reviewUsesCachedShortSentenceTranslationInsteadOfFullParagraph() {
        val text="Banks work. Banks work daily."
        val id=store.createDraft(emptyList()); store.saveOcr(id,OcrResult("短句译文测试",text,emptyList()))
        val batch=LanguageCore.batches(text).first(); val rows=org.json.JSONArray()
        batch.tokens.forEach { token -> rows.put(org.json.JSONArray().put(token.id)
            .put(if(token.surface=="Banks") "bank" else token.surface.lowercase())
            .put(if(token.surface=="Banks") "NOUN" else "VERB").put(if(token.surface=="Banks") "银行" else "运作")) }
        store.saveBatch(id,batch,JSONObject().put("w",rows).put("t","整段的长译文不应当显示在这张短句卡上。").put("p",org.json.JSONArray()))
        store.saveDefinitions(mapOf("bank" to listOf("银行"),"work" to listOf("运作"),"daily" to listOf("每天"))); store.publish(id)
        val word=store.occurrences(articleId=id).first(); store.rate(word,0,0)
        val card=store.due().first(); val selection=ReadingTools.reviewSelection(card,word)
        store.saveSelectedTranslation(selection.cacheKey,"短句：银行运作。")
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("复习"); assertNotNull(node("Banks work.")); click("显示本文释义")
            assertNotNull(node("短句：银行运作。"))
            assertNull(find(automation.rootInActiveWindow,"整段的长译文不应当显示在这张短句卡上。"))
            assertEquals(listOf(0L,0L,0L,0L),store.usageTotals()); capture("review-short-sentence-translation")
        }
    }
    @Test fun cachedWordQuestionUsesOriginalContextWithoutNetwork() {
        val (id,word)=seed()
        val question="为什么是这个词义？"
        val key=LanguageCore.hash("suidu-word-qa-v1\u0000"+AiRequests.wordQuestion(store.article(id)!!,word,question))
        store.saveSelectedTranslation(key,"这里的 Banks 指提供储蓄服务的银行，因此是名词。")
        ActivityScenario.launch(MobileActivity::class.java).use { scenario ->
            click("界面测试文章"); assertNotNull(node("正文段落 1")); openWord(); expandLookup()
            // Scroll the word sheet to its dedicated question panel.
            val image=stableScreenshot(); val w=image.width; val h=image.height; image.recycle()
            swipeDown(w*.5f,h*.84f,h*.32f)
            assertNotNull(node("问 DeepSeek"))
            click(question)
            assertNotNull(node("这里的 Banks 指提供储蓄服务的银行，因此是名词。"))
            assertNotNull(node("本机已保存的回答")); capture("word-question-cached")
            assertEquals(listOf(0L,0L,0L,0L),store.usageTotals())
        }
    }
    @Test fun tappingMiddleOfLongParagraphPreservesItsScrollPosition() {
        val text=(1..65).joinToString(" ") { "Banks work daily." }
        val id=store.createDraft(emptyList()); store.saveOcr(id,OcrResult("长段位置测试",text,emptyList()))
        LanguageCore.batches(text).forEach { batch ->
            val rows=org.json.JSONArray(); val base=batch.paragraph.tokens.first().id
            batch.tokens.forEach { token -> rows.put(org.json.JSONArray().put(token.id-base)
                .put(if(token.surface=="Banks") "bank" else token.surface.lowercase())
                .put(if(token.surface=="Banks") "NOUN" else "VERB").put("测试释义")) }
            store.saveBatch(id,batch,JSONObject().put("w",rows).put("t",if(batch.number==0) "银行每天运作。" else "").put("p",org.json.JSONArray()))
        }
        store.saveDefinitions(mapOf("bank" to listOf("银行"),"work" to listOf("运作"),"daily" to listOf("每天"))); store.publish(id)
        ActivityScenario.launch(MobileActivity::class.java).use { scenario ->
            click("长段位置测试"); assertNotNull(node("正文段落 1"))
            val image=stableScreenshot(); val w=image.width; val h=image.height; image.recycle()
            repeat(3) { swipeDown(w*.5f,h*.8f,h*.3f) }
            fun findText(view: android.view.View): ReadingTextView? {
                if(view is ReadingTextView) return view
                if(view is android.view.ViewGroup) for(i in 0 until view.childCount) findText(view.getChildAt(i))?.let { return it }
                return null
            }
            var before=0; var x=0f; var y=0f
            scenario.onActivity { activity ->
                val view=findText(activity.window.decorView) ?: error("Missing native reader")
                val location=IntArray(2); view.getLocationOnScreen(location); before=location[1]
                assertTrue("Must be scrolled inside long paragraph",before < -200)
                val line=view.layout.getLineForVertical(h/2-location[1])
                x=location[0]+view.layout.getPrimaryHorizontal(view.layout.getLineStart(line)+2)
                y=location[1]+view.layout.getLineBaseline(line)-view.textSize*.4f
            }
            capture("long-paragraph-before"); tap(x,y); assertNotNull(node("本文释义"))
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)); SystemClock.sleep(1000)
            scenario.onActivity { activity ->
                val location=IntArray(2); findText(activity.window.decorView)!!.getLocationOnScreen(location)
                assertEquals("Lookup must not scroll to the paragraph beginning",before,location[1])
            }
            capture("long-paragraph-after")
        }
    }

    @Test fun reviewSentenceLookupIsLockedUntilRevealedAndUsesPrintedTypeface() {
        val (_, source) = seed()
        store.rate(source, 0, 0L)
        ActivityScenario.launch(MobileActivity::class.java).use { scenario ->
            click("复习")
            assertNotNull(node("Banks work."))
            fun reviewView(): ReadingTextView {
                var result: ReadingTextView? = null
                fun visit(view: android.view.View) {
                    if (view is ReadingTextView && view.text.toString() == "Banks work.") result = view
                    if (view is android.view.ViewGroup) for (n in 0 until view.childCount) visit(view.getChildAt(n))
                }
                scenario.onActivity { visit(it.window.decorView) }
                return result ?: error("Review sentence view missing")
            }
            assertFalse(reviewView().tapLookupEnabled)
            assertEquals(android.graphics.Typeface.SERIF, reviewView().typeface)
            val bounds = Rect(); node("Banks work.").getBoundsInScreen(bounds)
            tap(bounds.left + 40f, bounds.top + 25f)
            SystemClock.sleep(300)
            assertNull(find(automation.rootInActiveWindow, "本文释义"))
            assertEquals(0,store.vocabulary().sumOf { it.clicks })
            click("显示本文释义")
            assertNotNull(node("原文短句 · 点击单词查义"))
            assertTrue(reviewView().tapLookupEnabled)
            var x=0f; var y=0f
            val sentenceView=reviewView()
            scenario.onActivity {
                val view=sentenceView; val location=IntArray(2); view.getLocationOnScreen(location)
                val layout=view.layout
                x=location[0]+(layout.getPrimaryHorizontal(6)+layout.getPrimaryHorizontal(9))/2
                y=location[1]+layout.getLineTop(0)+view.lineHeight/2f
            }
            tap(x,y)
            assertNotNull(node("工作（v. 动词）"))
            assertNotNull(node("本文释义"))
            capture("review-word-lookup")
            assertEquals(1,store.due().size)
            assertEquals(2,store.vocabulary().sumOf { it.count })
            automation.executeShellCommand("input keyevent 4").close()
            SystemClock.sleep(500)
            store.rateCard(store.due().single(),0,0L)
            scenario.recreate()
            assertNotNull(node("显示本文释义"))
            assertFalse(reviewView().tapLookupEnabled)

        }
    }
    @Test fun importFailureReasonPersistsAndManualInputRemainsAvailable() {
        val id=store.createDraft(emptyList())
        val diagnosis=AiDiagnostics.http(400,"""{"error":{"message":"image_url invalid format","code":"invalid_parameter"}}""").message!!
        store.setError(id,diagnosis)
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("导入")
            assertNotNull(node("处理未完成"))
            assertNotNull(node(diagnosis))
            assertTrue(store.draft(id)!!.error.contains("image_url invalid format"))
            capture("import-diagnosis")
            // Manual fallback is below the image guidance and may need one normal page scroll.
            val frame=stableScreenshot()
            swipeDown(frame.width*.5f,frame.height*.75f,frame.height*.32f); frame.recycle()
            click("手动粘贴或录入正文")
            assertNotNull(node("完整正文"))
            assertEquals("confirm",store.draft(id)!!.stage)
            assertEquals(listOf(0L,0L,0L,0L),store.usageTotals())
        }
    }
}
