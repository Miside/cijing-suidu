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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClozeUiTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private lateinit var store: LocalStore
    private val exercise = ClozeCore.fromParts("On a quiet morning, Emma [[21]] an old friend.\n\nThey talked for a while and felt [[22]] again.",
        "21. A. met  B. left  C. called  D. helped\n\n22. A. happy  B. afraid  C. tired  D. lonely")
    @Before fun open() {
        context.deleteDatabase("suidu-local.db"); store = LocalStore(context)
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        context.getSharedPreferences("suidu-ui", 0).edit().clear().commit()
        automation.setServiceInfo(automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS })
    }
    @After fun close() {
        store.close(); context.deleteDatabase("suidu-local.db")
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
    }
    private fun seed(publish: Boolean = true): String {
        val id = store.createDraft(emptyList(), kind = ImportKind.CLOZE)
        store.saveOcr(id, OcrResult("一次温暖的相遇", exercise.sourceText(), emptyList()))
        if (!publish) return id
        val batches = LanguageCore.batches(exercise.sourceText(), ClozeCore.TOKENIZATION)
        batches.forEach { b ->
            val local = LanguageCore.localBatch(b)
            store.saveBatch(id, b, JSONObject().put("w", JSONArray(local.tokens.map {
                JSONArray().put(it.id).put(LanguageCore.lemma(it.surface)).put("NOUN").put("原文释义：" + it.surface)
            })).put("t", "原文译文 " + ClozeCore.blank.findAll(b.paragraph.text).joinToString(" ") { it.value }).put("p", JSONArray()))
        }
        store.saveDefinitions(batches.flatMap { it.tokens }.associate { LanguageCore.lemma(it.surface) to listOf("n. 本地测试释义") })
        return store.publish(id)
    }
    private fun find(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.text?.toString()?.replace('\u00a0', ' ') == text || root.contentDescription?.toString() == text) return root
        for (index in 0 until root.childCount) find(root.getChild(index), text)?.let { return it }
        return null
    }
    private fun refreshTree() {
        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache() else automation.setServiceInfo(automation.serviceInfo)
    }
    private fun node(text: String): AccessibilityNodeInfo {
        repeat(70) {
            refreshTree()
            find(automation.rootInActiveWindow, text)?.let { return it }
            automation.windows.forEach { w -> find(w.root, text)?.let { return it } }
            SystemClock.sleep(100)
        }
        capture("failure")
        error("Missing UI text: $text")
    }
    private fun click(text: String) {
        var value: AccessibilityNodeInfo? = node(text)
        while (value != null && !value.isClickable) value = value.parent
        assertTrue("Could not click $text", value?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync(); SystemClock.sleep(200)
    }
    private fun capture(name: String) {
        SystemClock.sleep(300)
        automation.takeScreenshot()!!.let { bitmap ->
            java.io.File(context.getExternalFilesDir(null), "cloze-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }; bitmap.recycle()
        }
    }
    private fun scrollUp() {
        val bounds = Rect(); automation.rootInActiveWindow.getBoundsInScreen(bounds)
        val x = bounds.width() * .55f; val from = bounds.height() * .77f; val to = bounds.height() * .32f
        val start = SystemClock.uptimeMillis()
        for (n in 0..20) {
            val action = when (n) { 0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, from + (to - from) * n / 20, 0).let {
                it.source = InputDevice.SOURCE_TOUCHSCREEN; automation.injectInputEvent(it, true); it.recycle()
            }; SystemClock.sleep(14)
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(300)
    }
    private fun reveal(text: String) {
        repeat(8) {
            refreshTree()
            find(automation.rootInActiveWindow, text)?.let { node ->
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                val window = Rect(); automation.rootInActiveWindow.getBoundsInScreen(window)
                if (node.isVisibleToUser && bounds.centerY() in (window.height() * .16f).toInt()..(window.height() * .79f).toInt()) return
            }
            scrollUp()
        }
        node(text)
    }
    private fun views(root: android.view.View): List<ReadingTextView> = buildList {
        if (root is ReadingTextView) add(root)
        if (root is android.view.ViewGroup) for (n in 0 until root.childCount) addAll(views(root.getChildAt(n)))
    }
    @Test fun importOffersClozeCameraAndImagesAndManualFieldsWithoutAnswerControls() {
        store.createDraft(emptyList(), kind = ImportKind.CLOZE)
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("导入"); node("扫描完形填空 · 拍照"); node("完形填空 · 选择图片")
            capture("import")
            reveal("手动粘贴或录入正文"); click("手动粘贴或录入正文")
            reveal("完形正文 · 保留空格"); node("完形正文 · 保留空格")
            reveal("全部题目选项"); capture("manual-editor")
            assertFalse(store.drafts().single().text.contains("answer"))
            assertEquals(listOf(0L, 0L, 0L, 0L), store.usageTotals())
        }
    }
    @Test fun scannedDraftKeepsSeparateBodyAndOptionsAfterActivityRecreation() {
        val id = seed(false)
        ActivityScenario.launch(MobileActivity::class.java).use { scenario ->
            click("导入"); reveal("完形正文 · 保留空格"); capture("preview-body")
            reveal("全部题目选项"); capture("preview-options")
            scenario.recreate()
            node("全部题目选项")
            assertEquals(exercise.sourceText(), store.draft(id)!!.text)
            assertTrue(store.draft(id)!!.text.contains("[[21]]"))
            assertEquals(listOf(0L, 0L, 0L, 0L), store.usageTotals())
        }
    }
    @Test fun readerDisplaysBlankBodyAndAllOptionsWithWorkingWordLookupInBothThemes() {
        val id = seed()
        ActivityScenario.launch(MobileActivity::class.java).use { scenario ->
            click("一次温暖的相遇"); node("正文段落 1"); capture("reader-light")
            scenario.onActivity { activity ->
                val paragraph = views(activity.window.decorView).first { it.text.contains("__21__") }
                assertTrue(paragraph.text.contains("Emma __21__ an old friend"))
                assertFalse(paragraph.text.contains("[[21]]"))
            }
            scrollUp(); reveal("21. A. met  B. left  C. called  D. helped"); capture("options-light")
            scenario.onActivity { activity ->
                val paragraph = views(activity.window.decorView).first { it.text.toString().startsWith("21.") }
                // Native tap callback uses the displayed UTF-16 offset, exactly as a finger tap.
                paragraph.onWord(paragraph.text.indexOf("met") + 1)
            }
            node(ReadingTools.meaningWithPos("原文释义：met", "NOUN")); capture("option-lookup")
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            SystemClock.sleep(500)
            assertEquals(exercise.sourceText(), store.article(id)!!.text)
            assertEquals(1, store.vocabulary().sumOf { it.clicks })
        }
        context.getSharedPreferences("appearance", 0).edit().putInt("dark", 2).commit()
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("一次温暖的相遇"); node("正文段落 1"); capture("reader-dark")
            reveal("22. A. happy  B. afraid  C. tired  D. lonely"); capture("options-dark")
            assertEquals(0, store.reviewCount())
            assertEquals(listOf(0L, 0L, 0L, 0L), store.usageTotals())
        }
    }
}
