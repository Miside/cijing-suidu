package com.yy.suidu

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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class ClozeImportTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var store: LocalStore
    private val exercise = ClozeCore.fromParts("She [[21]] home. She felt [[22]].",
        "21. A. walked  B. sang  C. slept  D. read\n\n22. A. happy  B. sad  C. calm  D. tired")
    @Before fun open() { app.deleteDatabase("suidu-local.db"); store = LocalStore(app) }
    @After fun close() { store.close(); app.deleteDatabase("suidu-local.db"); KeyVault(app).clear() }
    private fun seed(): String {
        val id = store.createDraft(emptyList(), kind = ImportKind.CLOZE)
        store.saveOcr(id, OcrResult("完形原题测试", exercise.sourceText(), emptyList()))
        val batches = LanguageCore.batches(exercise.sourceText(), ClozeCore.TOKENIZATION)
        batches.forEach { b ->
            val local = LanguageCore.localBatch(b)
            val words = JSONArray(local.tokens.map { JSONArray().put(it.id).put(LanguageCore.lemma(it.surface)).put("NOUN").put("测试词义") })
            store.saveBatch(id, b, JSONObject().put("w", words).put("t", if (local.tokens.first().id == 0)
                "原文译文 " + ClozeCore.blank.findAll(b.paragraph.text).joinToString(" ") { it.value } else "").put("p", JSONArray()))
        }
        store.saveDefinitions(batches.flatMap { it.tokens }.associate { LanguageCore.lemma(it.surface) to listOf("n. 测试词义") })
        return store.publish(id)
    }
    @Test fun publishingAndBackupKeepAllBlanksOptionsAndExistingWordTools() {
        val id = seed()
        assertEquals(ImportKind.CLOZE, store.article(id)!!.kind)
        assertEquals(ImportKind.CLOZE, store.articles().single().kind)
        assertEquals(3, store.draft(id)!!.tokenization)
        assertEquals(exercise.sourceText(), store.article(id)!!.text)
        val words = store.occurrences(articleId = id)
        assertEquals(12, words.size)
        assertFalse(words.any { it.token.surface in listOf("A", "B", "C", "D") })
        assertTrue(words.any { it.token.surface == "walked" })
        store.rate(words.first(), 0, 0); assertEquals(1, store.reviewCount())
        val bytes = ByteArrayOutputStream().also { store.export(it) }.toByteArray()
        assertEquals(3, JSONObject(String(bytes)).getInt("version"))
        store.close(); app.deleteDatabase("suidu-local.db"); store = LocalStore(app)
        assertEquals(1, store.restore(bytes.inputStream()).articles)
        assertEquals(exercise.sourceText(), store.article(id)!!.text)
        assertEquals(ImportKind.CLOZE, store.article(id)!!.kind)
        assertEquals(1, store.reviewCount())
        assertEquals(0, store.restore(bytes.inputStream()).articles)
        assertEquals(12, store.vocabulary().sumOf { it.count })
    }
    @Test fun editingRejectsMismatchedQuestionsWithoutDestroyingSavedProgress() {
        val id = store.createDraft(emptyList(), kind = ImportKind.CLOZE)
        store.saveOcr(id, OcrResult("完形草稿", exercise.sourceText(), emptyList()))
        assertThrows(Exception::class.java) { store.editText(id, "错题号", exercise.sourceText().replace("[[22]]", "[[23]]")) }
        assertEquals(exercise.sourceText(), store.draft(id)!!.text)
        store.editText(id, "修改后", exercise.sourceText().replace("B. sad", "B. excited"))
        assertEquals(ClozeCore.TOKENIZATION, store.draft(id)!!.tokenization)
        assertTrue(store.draft(id)!!.text.contains("B. excited"))
    }
    @Test fun backupRejectsMismatchedModeAndRetainsEmptyManualClozeDraft() {
        val id = seed()
        val bad = JSONObject(ByteArrayOutputStream().also { store.export(it) }.toString("UTF-8"))
        bad.getJSONObject("tables").getJSONArray("drafts").getJSONObject(0).put("kind", "article")
        assertThrows(Exception::class.java) { store.restore(bad.toString().byteInputStream()) }
        assertEquals(exercise.sourceText(), store.article(id)!!.text)
        val manual = store.createDraft(emptyList(), kind = ImportKind.CLOZE)
        store.saveOcr(manual, OcrResult("手动原题", "", emptyList()))
        val bytes = ByteArrayOutputStream().also { store.export(it) }.toByteArray()
        store.close(); app.deleteDatabase("suidu-local.db"); store = LocalStore(app)
        store.restore(bytes.inputStream())
        assertEquals("confirm", store.draft(manual)!!.stage)
        assertEquals(ImportKind.CLOZE, store.draft(manual)!!.kind)
    }
    @Test fun cameraOutputProviderCanWriteOnlyScanCacheAndCancellationCleansItsFile() {
        val file = ScanCapture.create(app)
        val uri = ScanCapture.uri(app, file)
        assertEquals("content", uri.scheme)
        app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(byteArrayOf(1, 2, 3)) }
        assertEquals(3L, file.length())
        val outside = File(app.cacheDir, "not-a-scan.jpg").apply { writeText("keep") }
        try {
            assertThrows(IllegalArgumentException::class.java) { ScanCapture.uri(app, outside) }
            ScanCapture.discard(app, outside.absolutePath); assertTrue(outside.exists())
            ScanCapture.discard(app, file.absolutePath); assertFalse(file.exists())
        } finally { outside.delete(); file.delete() }
    }
    private class FixtureConnection(private val json: JSONObject) : HttpsURLConnection(URL("https://api.deepseek.com/chat/completions")) {
        val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getResponseCode() = 200
        override fun getInputStream() = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("content", json.toString())))).put("usage", JSONObject().put("prompt_tokens", 20).put("completion_tokens", 10))
            .toString().byteInputStream()
        override fun disconnect() = Unit
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "TEST"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 8000
        while (!condition() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(30)
        assertTrue("Operation did not finish", condition())
    }
    @Test fun imageImportUsesClozePromptAndOwnCacheWithoutRealNetworkRequests() {
        val vault = KeyVault(app); vault.save("sk-fixture-not-a-real-key")
        val file = File(app.cacheDir, "cloze-fixture.jpg")
        android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888).let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        }
        val json = JSONObject().put("title", "完形识别").put("pages", JSONArray().put(JSONObject().put("page", 1).put("text", exercise.body)))
            .put("questions", JSONArray(exercise.questions.map { q -> JSONObject().put("number", q.number)
                .put("options", JSONArray(q.options.map { JSONArray().put(it.label).put(it.text) })) })).put("uncertainties", JSONArray())
        val connections = java.util.concurrent.CopyOnWriteArrayList<FixtureConnection>()
        val holder = androidx.lifecycle.ViewModelStore()
        lateinit var model: MobileModel
        try {
            instrumentation.runOnMainSync {
                model = MobileModel(app) { key, usage -> DeepSeekClient(key, usage, connect = {
                    assertEquals("https://api.deepseek.com/chat/completions", it)
                    FixtureConnection(json).also { connection -> connections += connection }
                }) }; holder.put("model", model)
            }
            await { model.state.value.loaded }
            instrumentation.runOnMainSync { model.addImages(listOf(android.net.Uri.fromFile(file)), kind = ImportKind.CLOZE) }
            await { !model.state.value.busy && store.drafts().isNotEmpty() }
            val id = store.drafts().single().id
            assertEquals(ImportKind.CLOZE, store.draft(id)!!.kind)
            val image = File(store.draft(id)!!.images.single())
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(image.readBytes()).joinToString("") { "%02x".format(it) }
            store.cacheOcr(LanguageCore.hash("suidu-ocr-body-v2\u0000" + hash), JSONObject().put("title", "不应复用的普通正文")
                .put("pages", JSONArray().put(JSONObject().put("page", 1).put("text", "She walked home."))))
            instrumentation.runOnMainSync { model.recognize(id) }
            await { !model.state.value.busy && store.draft(id)!!.stage == "confirm" }
            assertEquals(exercise.sourceText(), store.draft(id)!!.text)
            assertEquals(1, connections.size)
            val sent = JSONObject(connections.single().sent.toString("UTF-8"))
            assertTrue(sent.getJSONArray("messages").getJSONObject(0).getString("content").contains("绝不做题"))
            val next = store.createDraft(listOf(image.absolutePath), kind = ImportKind.CLOZE)
            instrumentation.runOnMainSync { model.recognize(next) }
            await { !model.state.value.busy && store.draft(next)!!.stage == "confirm" }
            assertEquals(1, connections.size)
            assertEquals(exercise.sourceText(), store.draft(next)!!.text)
            assertEquals(listOf(20L, 10L, 0L, 20L), store.usageTotals())
        } finally { instrumentation.runOnMainSync { holder.clear() }; file.delete(); vault.clear() }
    }
}
