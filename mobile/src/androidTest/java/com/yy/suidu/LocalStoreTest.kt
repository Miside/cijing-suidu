package com.yy.suidu

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalStoreTest {
    @Test fun removingWordFromReviewKeepsArticleAndFrequencyAndAllowsReadding() {
        val id=draft(); fill(id); store.publish(id)
        val word=store.occurrences(articleId=id).first()
        store.rate(word,0,0)
        store.removeReview(word.lemma)
        assertEquals(0,store.reviewCount())
        assertEquals(2,store.vocabulary().sumOf { it.count })
        assertNotNull(store.article(id))
        store.rate(word,0,0)
        assertEquals(1,store.reviewCount())
    }
    @Test fun deletingArticleReassignsSharedSenseAndCascadesOnlyItsContent() {
        val first=draft(); fill(first); store.publish(first)
        val second=draft("Banks work!"); fill(second); store.publish(second)
        val bank=store.occurrences(articleId=first).first()
        store.rate(bank,0,0)
        store.logUsage(first,ApiResult(JSONObject(),30,10,20,10))
        store.deleteArticle(first)
        assertNull(store.article(first)); assertNull(store.draft(first))
        assertTrue(store.occurrences(articleId=first).isEmpty())
        assertEquals(2,store.vocabulary().sumOf { it.count })
        assertEquals(second,store.card(LanguageCore.cardId(bank.lemma,bank.pos,bank.meaning))!!.articleId)
        assertEquals(600000L,store.due(600000).single().due)
        assertEquals(listOf(30L,10L,20L,10L),store.usageTotals())
        store.deleteArticle(second)
        assertTrue(store.vocabulary().isEmpty()); assertEquals(0,store.reviewCount())
        assertNotNull(store.definitions("bank"))
        store.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use { assertEquals(0,it.count) }
    }
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var store: LocalStore
    @Before fun open() { context.deleteDatabase("suidu-local.db"); store = LocalStore(context) }
    @After fun close() { store.close(); context.deleteDatabase("suidu-local.db") }
    private fun payload() = JSONObject("""{"w":[[0,"bank","NOUN","银行"],[1,"work","VERB","工作"]],"t":"银行运作。","p":[]}""")
    private fun draft(text: String = "Banks work."): String {
        val id = store.createDraft(emptyList())
        store.saveOcr(id, OcrResult("测试文章", text, emptyList()))
        return id
    }
    private fun fill(id: String) {
        val batch = LanguageCore.batches(store.draft(id)!!.text).first()
        store.saveBatch(id, batch, payload())
        store.saveDefinitions(mapOf("bank" to listOf("n. 银行", "n. 河岸"), "work" to listOf("v. 工作")))
    }
    @Test fun atomicPublishCountsAndIdempotence() {
        val id = draft()
        fill(id)
        assertEquals(id, store.publish(id))
        assertEquals(2, store.vocabulary().sumOf { it.count })
        assertEquals(id, store.publish(id))
        val duplicate = draft("Banks   work.")
        assertEquals(id, store.existingArticle(store.draft(duplicate)!!.text))
        assertEquals(id, store.publish(duplicate))
        assertEquals(1, store.articles().size)
        assertEquals(2, store.vocabulary().sumOf { it.count })
        val bank = store.occurrences(articleId = id).first()
        store.click(bank.lemma)
        assertEquals(2, store.vocabulary().sumOf { it.count })
        assertEquals(1, store.vocabulary().first { it.lemma == "bank" }.clicks)
    }
    @Test fun dictionaryAndContextSurviveReopeningAndOffsetShifts() {
        val id = draft()
        fill(id)
        val batch = LanguageCore.batches("Earlier paragraph.\n\nBanks work.").last()
        store.cacheContext(batch, payload())
        store.close()
        store = LocalStore(context)
        assertEquals(emptyList<String>(), store.missingDefinitions(listOf("bank", "work")))
        val shifted = LanguageCore.batches("An extra paragraph.\n\nEarlier paragraph.\n\nBanks work.").last()
        val parsed = AiContract.analysis(store.cachedContext(shifted)!!, shifted)
        assertEquals(5, parsed.words.first().token.id)
        assertEquals("Banks", parsed.words.first().token.surface)
        assertEquals("银行", parsed.words.first().meaning)
    }
    @Test fun missingDictionaryRollsBackWholeArticleAndCanResume() {
        val id = draft()
        store.saveBatch(id, LanguageCore.batches("Banks work.").first(), payload())
        assertThrows(Exception::class.java) { store.publish(id) }
        assertTrue(store.articles().isEmpty())
        assertTrue(store.vocabulary().isEmpty())
        store.saveDefinitions(mapOf("bank" to listOf("n. 银行"), "work" to listOf("v. 工作")))
        store.publish(id)
        assertEquals(2, store.occurrences(articleId = id).size)
    }
    @Test fun editsKeepSpentUsageButInvalidateAffectedCheckpoints() {
        val id = draft()
        fill(id)
        store.logUsage(id, ApiResult(JSONObject(), 100, 30, 64, 36))
        store.editText(id, "新标题", "Banks work.")
        assertNotNull(store.batch(id, 0))
        store.editText(id, "新标题", "Banks sleep.")
        assertNull(store.batch(id, 0))
        assertEquals(100L, store.draft(id)!!.inputTokens)
        assertEquals(64L, store.draft(id)!!.cacheHitTokens)
        store.discard(id)
        assertEquals(listOf(100L, 30L, 64L, 36L), store.usageTotals())
    }
    @Test fun reviewIsRealPersistentAndExportExcludesKeys() {
        val id = draft()
        fill(id)
        store.publish(id)
        val word = store.occurrences(articleId = id).first()
        store.rate(word, 0, 0L)
        assertTrue(store.due(599_999L).isEmpty())
        val card = store.due(600_000L).single()
        assertEquals("银行", card.meaning)
        assertEquals("Banks work.", card.context)
        store.rateCard(card, 2, 600_000L)
        assertTrue(store.due(600_001L).isEmpty())
        val out = java.io.ByteArrayOutputStream()
        store.export(out)
        val json = JSONObject(out.toString("UTF-8"))
        assertEquals(1, json.getJSONObject("tables").getJSONArray("cards").length())
        assertEquals(2, json.getJSONObject("tables").getJSONArray("occurrences").length())
        assertFalse(out.toString("UTF-8").contains("ciphertext"))
        assertFalse(out.toString("UTF-8").contains("deepseek-private"))
    }
    @Test fun vaultDoesNotStorePlaintext() {
        val vault = KeyVault(context)
        try {
            vault.save("sk-fixture-not-a-real-key")
            assertEquals("sk-fixture-not-a-real-key", vault.read())
            assertFalse(context.getSharedPreferences("deepseek-private", android.content.Context.MODE_PRIVATE).getString("ciphertext", "")!!.contains("sk-fixture"))
        } finally { vault.clear() }
    }
    @Test fun validatedFragmentsSurviveReopeningAndCannotPublishUntilComplete() {
        val id = draft()
        val batch = LanguageCore.batches("Banks work.").first()
        val partial = JSONObject("""{"w":[[0,"bank","NOUN","银行"]],"t":"银行运作。","p":[]}""")
        store.saveFragment(id, batch, partial)
        assertEquals(0, store.draft(id)!!.chunks)
        assertThrows(Exception::class.java) { store.publish(id) }
        store.close(); store = LocalStore(context)
        assertEquals(1, store.fragment(id, batch)!!.getJSONArray("w").length())
        store.editText(id, "只改标题", "Banks work.")
        assertNotNull(store.fragment(id, batch))
        store.saveBatch(id, batch, payload())
        assertNull(store.fragment(id, batch))
        assertEquals(1, store.draft(id)!!.chunks)
        store.editText(id, "改正文", "Banks sleep.")
        store.saveFragment(id, LanguageCore.batches("Banks sleep.").first(), partial)
        store.editText(id, "改正文", "Banks stop.")
        assertNull(store.fragment(id, batch))
        store.saveFragment(id, LanguageCore.batches("Banks stop.").first(), partial)
        store.discard(id)
        assertNull(store.fragment(id, batch))
    }
    @Test fun versionOneMigrationPreservesArticlesUsageAndOldContextCache() {
        val id = draft()
        fill(id); store.publish(id)
        store.logUsage(id, ApiResult(JSONObject(), 100, 40, 64, 36))
        val batch = LanguageCore.batches("Banks work.").first()
        store.writableDatabase.execSQL("INSERT INTO context_cache(key,json) VALUES(?,?)", arrayOf(LanguageCore.contextCacheKey(batch, "suidu-context-compact-v1"), payload().toString()))
        store.writableDatabase.execSQL("DROP TABLE analysis_fragments")
        legacyCards()
        store.writableDatabase.execSQL("DROP TABLE selection_cache")
        store.writableDatabase.version = 1
        store.close(); store = LocalStore(context)
        assertEquals(3, store.readableDatabase.version)
        assertEquals(2, store.vocabulary().sumOf { it.count })
        assertEquals(1, store.articles().size)
        assertEquals(listOf(100L,40L,64L,36L), store.usageTotals())
        assertNotNull(store.cachedContext(batch))
        val another = draft()
        store.saveFragment(another, batch, JSONObject("""{"w":[],"t":"","p":[]}"""))
        assertNotNull(store.fragment(another, batch))
    }
    private fun legacyCards() {
        val db = store.writableDatabase
        val columns = "id,lemma,pos,meaning,common,article_id,token_id,context,repetitions,lapses,due,rating"
        db.execSQL("CREATE TABLE cards_legacy(id TEXT PRIMARY KEY,lemma TEXT NOT NULL,pos TEXT NOT NULL,meaning TEXT NOT NULL,common TEXT NOT NULL,article_id TEXT NOT NULL REFERENCES articles(id),token_id INTEGER NOT NULL,context TEXT NOT NULL,repetitions INTEGER NOT NULL,lapses INTEGER NOT NULL,due INTEGER NOT NULL,rating INTEGER NOT NULL)")
        db.execSQL("INSERT INTO cards_legacy($columns) SELECT $columns FROM cards")
        db.execSQL("DROP TABLE cards")
        db.execSQL("ALTER TABLE cards_legacy RENAME TO cards")
        db.execSQL("CREATE INDEX due_index ON cards(due)")
    }
    @Test fun versionTwoMigrationPreservesReviewAndTranslationCachePersists() {
        val id = draft(); fill(id); store.publish(id)
        val word = store.occurrences(articleId=id).first(); store.rate(word,0,0L)
        store.logUsage(id,ApiResult(JSONObject(),30,10,16,14))
        legacyCards(); store.writableDatabase.execSQL("DROP TABLE selection_cache")
        store.writableDatabase.version=2
        store.close(); store=LocalStore(context)
        assertEquals(3,store.readableDatabase.version)
        assertEquals("银行",store.due(600000L).single().meaning)
        assertEquals(0,store.due(600000L).single().reviews)
        assertEquals(2,store.vocabulary().sumOf { it.count })
        assertEquals(listOf(30L,10L,16L,14L),store.usageTotals())
        val selection=TextSelection(id,0,0,5,"Banks work.")
        store.saveSelectedTranslation(selection.cacheKey,"银行")
        store.close(); store=LocalStore(context)
        assertEquals("银行",store.selectedTranslation(selection.cacheKey))
        assertNull(store.selectedTranslation(selection.copy(paragraph="Banks fail.").cacheKey))
    }
    @Test fun reviewUsesShortSameSenseSentencesAndRotatesAfterThreeCompletedReviews() {
        val text="Banks open early. Banks serve banks. We sit on the banks.\n\n" + (0..45).joinToString(" ") { if(it==23) "Banks" else "work" } + "."
        val id=draft(text)
        for(batch in LanguageCore.batches(text)) {
            val base=batch.paragraph.tokens.first().id
            val rows=JSONArray()
            batch.tokens.forEach { token ->
                val bank=token.surface.equals("Banks",true)
                val river=bank && token.start<text.indexOf("\n\n") && token.start==text.lastIndexOf("banks.")
                rows.put(JSONArray().put(token.id-base).put(if(bank) "bank" else "work").put(if(bank) "NOUN" else "VERB").put(if(river) "河岸" else if(bank) "银行" else "工作"))
            }
            store.saveBatch(id,batch,JSONObject().put("w",rows).put("t","测试译文").put("p",JSONArray()))
        }
        store.saveDefinitions(mapOf("bank" to listOf("n. 银行","n. 河岸"),"work" to listOf("v. 工作"))); store.publish(id)
        val words=store.occurrences(articleId=id)
        val longBank=words.last { it.lemma=="bank" }
        store.rate(longBank,0,0L)
        var card=store.due(600000L).single()
        assertEquals("Banks open early.",card.context)
        assertEquals(3,card.contextChoices)
        val first=card.context
        for(round in 1..3) {
            store.rateCard(card,0,round*600000L)
            card=store.due((round+1)*600000L).single()
            if(round<3) assertEquals(first,card.context)
        }
        assertEquals("Banks serve banks.",card.context)
        assertEquals(3,card.reviews)
        assertEquals("Banks",card.context.substring(card.targetStart,card.targetEnd))
        assertFalse(card.context.contains("sit"))
        store.close(); store=LocalStore(context)
        assertEquals(card.context,store.due(2400000L).single().context)
        val river=store.occurrences(articleId=id).first { it.meaning=="河岸" }
        store.rate(river,0,0L)
        assertEquals("We sit on the banks.",store.due(600000L).first { it.meaning=="河岸" }.context)
    }
}
