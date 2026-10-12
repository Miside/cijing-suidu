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
    }
    @After fun cleanup() {
        store.close(); context.deleteDatabase("suidu-local.db")
        context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).edit().clear().commit()
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
            openWord()
            click("查看这个词的所有出现位置")
            assertNotNull(node("bank"))
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            assertNotNull(node("2 词次 · 点词查看本文释义"))
            val afterHistory = stableScreenshot()
            assertSameReaderPixels(before,afterHistory,bounds,"history-back")
            afterHistory.recycle()
            openWord()
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
            click("界面测试文章"); assertNotNull(node("第 1 段"))
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
            click("界面测试文章"); assertNotNull(node("第 1 段"))
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
            click("界面测试文章"); assertNotNull(node("第 1 段"))
            scenario.onActivity { activity -> androidx.lifecycle.ViewModelProvider(activity)[MobileModel::class.java].translateSelection(selection) }
            assertNotNull(node("银行开展工作"))
            assertNotNull(node("本地选段翻译缓存"))
            capture("selection-cached-translation")
            click("返回文章")
            assertEquals(2,store.vocabulary().sumOf { it.count })
            assertEquals(listOf(0L,0L,0L,0L),store.usageTotals())
        }
    }
    @Test fun articleParagraphsHaveSeparateFramesAndTranslations() {
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
            assertNotNull(node("第 1 段")); assertNotNull(node("第 2 段"))
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
            click("删除文章"); assertNotNull(node("删除这篇文章？"))
            click("保留文章"); assertEquals(1,store.articles().size)
            click("删除文章"); click("确认删除文章")
            assertNotNull(node("你的书库还没有文章"))
            assertEquals(0,store.reviewCount()); assertTrue(store.vocabulary().isEmpty())
            capture("deleted-article")
        }
    }
    @Test fun removingReviewWordKeepsReadableArticle() {
        val (_,word)=seed(); store.rate(word,0,0)
        ActivityScenario.launch(MobileActivity::class.java).use {
            click("复习"); click("将 bank 移出复习队列")
            assertNotNull(node("当前没有到期的复习"))
            assertEquals(0,store.reviewCount()); assertEquals(2,store.vocabulary().sumOf { it.count })
            click("书库"); click("界面测试文章"); assertNotNull(node("第 1 段"))
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
            click("界面测试文章"); assertNotNull(node("第 1 段")); openWord()
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
            click("长段位置测试"); assertNotNull(node("第 1 段"))
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
}
