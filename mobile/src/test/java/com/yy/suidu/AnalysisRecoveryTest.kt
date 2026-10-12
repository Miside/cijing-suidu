package com.yy.suidu

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AnalysisRecoveryTest {
    private fun response(batch: AnalysisBatch, translate: Boolean = false): JSONObject = JSONObject()
        .put("w", JSONArray(LanguageCore.localBatch(batch).tokens.map { JSONArray().put(it.id).put("word").put("NOUN").put("词") }))
        .put("t", if (translate) "完整段落译文" else "").put("p", JSONArray())
    @Test fun targetsListEveryPositionAndRetainSharedPrefixForSparseRepairs() {
        val batches = LanguageCore.batches((1..161).joinToString(" ") { "word" })
        val batch = batches[1]
        val json = JSONObject(AiRequests.context(batch))
        assertEquals((80..159).toList(), (0 until json.getJSONArray("targets").length()).map { json.getJSONArray("targets").getInt(it) })
        assertEquals(80, json.getJSONArray("tokens").getJSONArray(80).getInt(0))
        val sparse = batch.copy(tokens = listOf(batch.tokens[2], batch.tokens[30], batch.tokens[73]))
        val repair = AiRequests.context(sparse, false)
        assertEquals(listOf(82,110,153), (0..2).map { JSONObject(repair).getJSONArray("targets").getInt(it) })
        assertEquals(AiRequests.context(batch).substringBefore(",\"targets\""), repair.substringBefore(",\"targets\""))
    }
    @Test fun recoveryKeepsGoodRowsAndRequestsOnlyOmissions() = runBlocking {
        val batch = LanguageCore.batches((1..161).joinToString(" ") { "word" })[1]
        val missing = setOf(82,110,153)
        val requests = mutableListOf<List<Int>>()
        var saved: JSONObject? = null
        val result = AnalysisRecovery.resolve(batch, null, request = { target, translate ->
            val ids = LanguageCore.localBatch(target).tokens.map { it.id }
            requests += ids
            if (requests.size == 1) {
                val initial = response(target)
                val rows = initial.getJSONArray("w")
                initial.put("w", JSONArray((0 until rows.length()).map { rows.getJSONArray(it) }.filter { it.getInt(0) !in missing })
                    .put(JSONArray("[0,\"extra\",\"NOUN\",\"其他批次\"]")))
            } else response(target, translate)
        }, checkpoint = { saved = JSONObject(it.toString()) })
        assertEquals(2, requests.size)
        assertEquals(missing.sorted(), requests[1])
        assertEquals(80, AiContract.analysis(result, batch).words.size)
        assertEquals(80, saved!!.getJSONArray("w").length())
    }
    @Test fun ambiguousDuplicatesRemainMissingAndInvalidPhrasesDoNotDiscardWords() {
        val batch = LanguageCore.batches("Banks work.").first()
        val progress = AnalysisProgress(batch)
        progress.merge(JSONObject("""{"w":[[0,"bank","NOUN","银行"],[0,"bank","NOUN","河岸"],[1,"work","VERB","工作"]],"t":"银行运作。","p":[["wrong","错",[0,1]]]}"""), batch, true)
        assertEquals(listOf(0), progress.missing.map { it.id })
        progress.merge(JSONObject("""{"w":[[0,"bank","NOUN","银行"],[0,"bank","NOUN","银行"]],"t":"","p":[["Banks work","银行运作",[1,0]]]}"""), batch.copy(tokens = batch.tokens.take(1)), false)
        assertTrue(progress.complete)
        val parsed = AiContract.analysis(progress.snapshot(), batch)
        assertEquals(2, parsed.words.size)
        assertEquals(listOf(0,1), parsed.phrases.single().tokenIds)
    }
    @Test fun incompleteResponsesAreBoundedAndResumeDoesNotRepeatGoodWords() = runBlocking {
        val batch = LanguageCore.batches((1..80).joinToString(" ") { "word" }).first()
        var checkpoint: JSONObject? = null
        var calls = 0
        var failure: Throwable? = null
        try {
            AnalysisRecovery.resolve(batch, null, request = { target, _ ->
                calls++
                if (calls == 1) response(target.copy(tokens = target.tokens.take(1)), true)
                else JSONObject("""{"w":[],"t":"","p":[]}""")
            }, checkpoint = { checkpoint = JSONObject(it.toString()) })
        } catch (e: IllegalStateException) { failure = e }
        assertNotNull(failure)
        assertTrue(calls <= AnalysisRecovery.MAX_REQUESTS)
        assertEquals(1, checkpoint!!.getJSONArray("w").length())
        val result = AnalysisRecovery.resolve(batch, checkpoint, request = { target, translate ->
            assertFalse(target.tokens.any { it.id == 0 })
            assertFalse(translate)
            response(target)
        }, checkpoint = {})
        assertEquals(80, AiContract.analysis(result, batch).words.size)
    }
    @Test fun missingTranslationIsRepairedWithoutReplacingValidatedMeaning() = runBlocking {
        val batch = LanguageCore.batches("Banks work.").first()
        var calls = 0
        val result = AnalysisRecovery.resolve(batch, null, request = { target, translate ->
            calls++
            assertTrue(translate)
            if (calls == 1) JSONObject("""{"w":[[0,"bank","NOUN","银行"],[1,"work","VERB","工作"]],"t":"","p":[]}""")
            else { assertEquals(1, target.tokens.size); response(target, true) }
        }, checkpoint = {})
        assertEquals(2, calls)
        assertEquals("银行", AiContract.analysis(result, batch).words.first().meaning)
    }
    @Test fun exportLiveFixturesUsesProductionPromptAndRequests() {
        val path = System.getProperty("suidu.fixtureDir") ?: return
        val text = (1..6).joinToString(" ") { "The bank near the river serves the town. People work at the bank and walk along the river bank after work. They read books and discuss what each word means in the article." }
        val batches = LanguageCore.batches(text)
        val batch = batches[1]
        fun write(name: String, target: AnalysisBatch, translate: Boolean) {
            val body = JSONObject().put("model", "deepseek-flash").put("thinking", JSONObject().put("type", "disabled"))
                .put("temperature", .1).put("max_tokens", 10000).put("response_format", JSONObject().put("type", "json_object"))
                .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", AiRequests.ANALYSIS_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", AiRequests.context(target, translate))))
            File(path, "suidu-live-$name-request.json").writeText(JSONObject().put("body", body).put("expected", JSONArray(LanguageCore.localBatch(target).tokens.map { it.id })).toString())
        }
        write("fixed", batch, false)
        write("repair", batch.copy(tokens = listOf(batch.tokens[2],batch.tokens[30],batch.tokens[73])), false)
        write("translation", batches[0].copy(tokens = batches[0].tokens.take(20)), true)
    }
}
