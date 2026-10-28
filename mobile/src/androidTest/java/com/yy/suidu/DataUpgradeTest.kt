package com.yy.suidu

import android.database.sqlite.SQLiteDatabase
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

@RunWith(AndroidJUnit4::class)
class DataUpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var store: LocalStore
    @Before fun open() { context.deleteDatabase("suidu-local.db"); store = LocalStore(context) }
    @After fun close() { store.close(); context.deleteDatabase("suidu-local.db") }
    private fun seed(): String {
        val id = store.createDraft(emptyList())
        store.saveOcr(id, OcrResult("备份测试", "Cafés work.", emptyList()))
        val batch = LanguageCore.batches("Cafés work.").first()
        store.saveBatch(id, batch, JSONObject("""{"w":[[0,"café","NOUN","咖啡馆"],[1,"work","VERB","运作"]],"t":"咖啡馆运作。","p":[]}"""))
        store.saveDefinitions(mapOf("café" to listOf("n. 咖啡馆"), "work" to listOf("v. 运作")))
        store.publish(id); store.rate(store.occurrences(articleId = id).first(), 0, 0)
        store.logUsage(id, ApiResult(JSONObject(), 100, 30, 60, 40))
        return id
    }
    private fun backup(): ByteArray = ByteArrayOutputStream().also { store.export(it) }.toByteArray()
    private fun emptyStore() { store.close(); context.deleteDatabase("suidu-local.db"); store = LocalStore(context) }
    @Test fun versionTwoRoundTripIsIdempotentAndKeepsNewerLocalReview() {
        seed(); val bytes = backup(); emptyStore()
        val restored = store.restore(bytes.inputStream())
        assertEquals(1, restored.articles); assertEquals(1, restored.cards)
        assertEquals(2, store.vocabulary().sumOf { it.count })
        assertEquals(listOf(100L,30L,60L,40L), store.usageTotals())
        val card = store.due(600000).single()
        store.rateCard(card, 2, 600000)
        val due = store.card(card.id)!!.due
        assertEquals(0, store.restore(bytes.inputStream()).articles)
        assertEquals(due, store.card(card.id)!!.due)
        assertEquals(1, store.card(card.id)!!.reviews)
        assertEquals(listOf(100L,30L,60L,40L), store.usageTotals())
        assertEquals("", store.articles().single().text)
        assertEquals("Cafés work.", store.article(store.articles().single().id)!!.text)
    }
    @Test fun legacyExportRebuildsDraftParentAndUsesSavedWordPositions() {
        seed()
        val root = JSONObject(String(backup(), Charsets.UTF_8)).put("version", 1)
        val tables = root.getJSONObject("tables")
        for (table in listOf("drafts","batches","analysis_fragments","context_cache","selection_cache")) tables.remove(table)
        val usage = tables.getJSONArray("usage")
        for (i in 0 until usage.length()) usage.getJSONObject(i).remove("import_key")
        emptyStore()
        assertEquals(1, store.restore(root.toString().byteInputStream()).articles)
        val article = store.article(store.articles().single().id)!!
        assertEquals(1, store.draft(article.id)!!.tokenization)
        val saved = store.occurrences(articleId = article.id).map { it.token }
        assertEquals("Cafés", LanguageCore.readingParagraphs(article.text, saved).first().tokens.first().surface)
        assertEquals(listOf(100L,30L,60L,40L), store.usageTotals())
    }
    @Test fun invalidLateRecordRollsBackAllInsertedArticlesAndParents() {
        seed()
        val root = JSONObject(String(backup(), Charsets.UTF_8))
        root.getJSONObject("tables").getJSONArray("usage").getJSONObject(0).put("input_tokens", -1)
        emptyStore()
        assertThrows(Exception::class.java) { store.restore(root.toString().byteInputStream()) }
        assertTrue(store.articles().isEmpty()); assertTrue(store.drafts().isEmpty())
        assertEquals(0, store.reviewCount())
    }
    @Test fun invalidWordOffsetsNeverChangeExistingData() {
        seed(); val root = JSONObject(String(backup(), Charsets.UTF_8))
        root.getJSONObject("tables").getJSONArray("occurrences").getJSONObject(0).put("start", 500)
        assertThrows(Exception::class.java) { store.restore(root.toString().byteInputStream()) }
        assertEquals(1, store.articles().size); assertEquals(2, store.vocabulary().sumOf { it.count })
    }
    @Test fun repeatedContentWithinOneBackupIsMergedWithoutDuplicateOccurrences() {
        seed()
        val root = JSONObject(String(backup(), Charsets.UTF_8))
        val tables = root.getJSONObject("tables")
        for (name in listOf("articles", "drafts", "paragraphs", "occurrences", "batches")) {
            val rows = tables.getJSONArray(name)
            val count = rows.length()
            for (index in 0 until count) {
                val copy = JSONObject(rows.getJSONObject(index).toString())
                copy.put(if (name == "articles" || name == "drafts") "id" else if (name == "batches") "draft_id" else "article_id", "same-content-other-id")
                rows.put(copy)
            }
        }
        emptyStore()
        val result = store.restore(root.toString().byteInputStream())
        assertEquals(1, result.articles)
        assertEquals(1, store.articles().size); assertEquals(2, store.vocabulary().sumOf { it.count })
        assertEquals(1, store.reviewCount())
    }
    @Test fun backupIdentifiersCannotBecomeImportDirectoryPaths() {
        store.createDraft(emptyList())
        val root = JSONObject(String(backup(), Charsets.UTF_8))
        root.getJSONObject("tables").getJSONArray("drafts").getJSONObject(0).put("id", "../outside")
        assertThrows(Exception::class.java) { store.restore(root.toString().byteInputStream()) }
        assertEquals(1, store.drafts().size)
    }
    @Test fun legacyContextCacheCannotSupplyAsciiFragmentsToNewAccentedTokens() {
        val old = LanguageCore.batches("café work.", 1).first()
        val response = """{"w":[[0,"caf","NOUN","旧片段"],[1,"work","VERB","运作"]],"t":"旧译文","p":[]}"""
        store.writableDatabase.execSQL("INSERT INTO context_cache(key,json) VALUES(?,?)",
            arrayOf(LanguageCore.contextCacheKey(old, "suidu-context-explicit-ids-v2"), response))
        assertNotNull(store.cachedContext(old))
        assertNull(store.cachedContext(LanguageCore.batches("café work.").first()))
    }
    @Test fun sourceImagePathsAndKeysAreNeverExportedOrRestored() {
        val id = store.createDraft(listOf("/private/source/secret.jpg"))
        val json = String(backup(), Charsets.UTF_8)
        assertFalse(json.contains("/private/source")); assertFalse(json.contains("ciphertext"))
        val root = JSONObject(json)
        root.getJSONObject("tables").getJSONArray("drafts").getJSONObject(0).put("images", "[\"../../outside.jpg\"]")
        emptyStore(); store.restore(root.toString().byteInputStream())
        assertTrue(store.draft(id)!!.images.isEmpty())
    }
    @Test fun realVersionThreeSchemaMigratesWithoutRetokenizingOldArticles() {
        store.close(); context.deleteDatabase("suidu-local.db")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("suidu-local.db"), null).use { db ->
            val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("schema-v3.sql").bufferedReader().use { it.readLines() }
            schema.filter { it.isNotBlank() }.forEach { db.execSQL(it) }
            val text = "café résumé banks."
            db.execSQL("INSERT INTO drafts VALUES(?,?,?,?,?,?,?,?)", arrayOf("old","旧文章",text,"[]","ready","","",1L))
            db.execSQL("INSERT INTO articles VALUES(?,?,?,?,?)", arrayOf("old","旧文章",text,LanguageCore.articleHash(text),1L))
            db.execSQL("INSERT INTO paragraphs VALUES(?,?,?,?,?)", arrayOf("old",0,0,text,"旧译文"))
            LanguageCore.paragraphs(text, 1).first().tokens.forEach { token ->
                db.execSQL("INSERT INTO occurrences VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf("old",token.id,token.surface,token.start,token.end,0,token.surface,"NOUN","旧释义","[\"n. 旧释义\"]"))
            }
            db.execSQL("INSERT INTO usage(draft_id,input_tokens,output_tokens,cache_hit,cache_miss) VALUES(?,?,?,?,?)", arrayOf("old",100,30,60,40))
            db.version = 3
        }
        store = LocalStore(context)
        assertEquals(5, store.readableDatabase.version)
        assertEquals(1, store.draft("old")!!.tokenization)
        val saved = store.occurrences(articleId = "old").map { it.token }
        assertEquals(LanguageCore.paragraphs("café résumé banks.", 1).first().tokens, saved)
        assertEquals(saved, LanguageCore.readingParagraphs(store.article("old")!!.text, saved).first().tokens)
        assertEquals(listOf(100L,30L,60L,40L), store.usageTotals())
        store.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use { assertEquals(0,it.count) }
    }
}
