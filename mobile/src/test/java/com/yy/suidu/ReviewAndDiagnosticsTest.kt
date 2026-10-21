package com.yy.suidu

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReviewAndDiagnosticsTest {
    @Test fun dictionaryFailureIdentifiesTheStageAndActualWordCount() {
        val failure = assertThrows(AiFailure::class.java) {
            AiContract.dictionary(JSONObject("""{"d":[["bank",["n. 银行"]]]}"""), listOf("bank", "work"))
        }
        assertEquals("常见释义", failure.stage)
        assertEquals("DICTIONARY_SCHEMA", failure.code)
        assertTrue(failure.reason.contains("实际 1 个"))
    }
    @Test fun excerptLookupUsesExactRepeatedWordAndHandlesEllipses() {
        val paragraph = "Banks open early. People walk by the banks."
        val tokens = LanguageCore.paragraphs(paragraph).single().tokens
        val words = tokens.map { Occurrence("a", "标题", it, it.surface.lowercase(), "NOUN", "词义 ${it.id}", emptyList(), paragraph) }
        val target = words.last()
        val excerpt = ReadingTools.excerpt(paragraph, target.token.start, target.token.end)
        val card = ReviewCard("c", "bank", "NOUN", "河岸", emptyList(), "a", target.token.id, excerpt.text, 0, 0, 0,
            targetStart = excerpt.targetStart, targetEnd = excerpt.targetEnd, shortened = excerpt.shortened)
        assertEquals(target.token.id, ReadingTools.reviewWord(card, card.targetStart + 1, words)?.token?.id)
        assertEquals("People", ReadingTools.reviewWord(card, 1, words)?.token?.surface)
        assertNull(ReadingTools.reviewWord(card, card.context.lastIndex, words))
        assertNull(ReadingTools.reviewWord(card, -1, words))
        val long = (0..55).joinToString(" ") { if (it == 40) "banks" else "word" } + "."
        val longWords = LanguageCore.paragraphs(long).single().tokens.map { Occurrence("a", "", it, it.surface, "NOUN", "词", emptyList(), long) }
        val source = longWords[40]
        val clipped = ReadingTools.excerpt(long, source.token.start, source.token.end)
        val clippedCard = card.copy(tokenId = source.token.id, context = clipped.text, targetStart = clipped.targetStart,
            targetEnd = clipped.targetEnd, shortened = true)
        assertTrue(clipped.text.startsWith("… "))
        assertEquals(source.token.id, ReadingTools.reviewWord(clippedCard, clipped.targetStart + 1, longWords)?.token?.id)
        assertNull(ReadingTools.reviewWord(clippedCard, 0, longWords))
    }

    @Test fun speechWaitsForInitializationAndStopDiscardsOldCard() {
        val queue = WordSpeechQueue()
        assertNull(queue.request("bank"))
        assertNull(queue.request("river"))
        assertEquals("river", queue.ready())
        assertEquals("work", queue.request("work"))
        val stopped = WordSpeechQueue()
        stopped.request("old")
        stopped.stop()
        assertNull(stopped.ready())
    }

    @Test fun diagnosticsPreserveServerCauseAndRedactKeys() {
        val failure = AiDiagnostics.http(400, """{"error":{"code":"invalid_parameter","message":"image_url rejected; Bearer abc123 sk-secret-123"}}""", "req-42")
        assertTrue(failure.message!!.contains("image_url rejected"))
        assertTrue(failure.message!!.contains("req-42"))
        assertTrue(failure.message!!.contains("HTTP 400"))
        assertFalse(failure.message!!.contains("abc123"))
        assertFalse(failure.message!!.contains("secret-123"))
    }

    @Test fun truncationAndMalformedJsonHaveDifferentCorrectiveActions() {
        fun response(reason: String, text: String) = JSONObject().put("usage", JSONObject().put("completion_tokens", 22000))
            .put("choices", JSONArray().put(JSONObject().put("finish_reason", reason).put("message", JSONObject().put("content", text))))
        val length = assertThrows(AiFailure::class.java) { AiDiagnostics.completion(response("length", "{"), 22000, "图片识别") }
        assertEquals("FINISH_LENGTH", length.code)
        assertTrue(length.action.contains("更少页"))
        val invalid = assertThrows(AiFailure::class.java) { AiDiagnostics.completion(response("stop", "{"), 22000, "图片识别") }
        assertEquals("INVALID_JSON", invalid.code)
        assertEquals(1, AiDiagnostics.completion(response("stop", "```json\n{\"ok\":1}\n```"), 20, "图片识别").getInt("ok"))
    }

    @Test fun ocrSortsUniquePageNumbersWithoutAnotherRequest() {
        val result = AiContract.ocr(JSONObject("""{"pages":[{"page":2,"text":"People work."},{"page":1,"text":"Banks open."}]}"""), 2)
        assertEquals("Banks open.\n\nPeople work.", result.text)
        val missing = assertThrows(AiFailure::class.java) { AiContract.ocr(JSONObject("""{"pages":[{"page":1,"text":"Banks work."}]}"""), 2) }
        assertTrue(missing.reason.contains("实际 1 页"))
    }

    @Test fun invalidResponsesStopOnceAndBlockAfterOneCorrection() = runBlocking {
        val batch = LanguageCore.batches("Banks work.").first()
        var saved: JSONObject? = null
        var calls = 0
        repeat(2) {
            try {
                AnalysisRecovery.resolve(batch, saved, request = { _, _ ->
                    calls++
                    JSONObject("""{"w":[[0,"bank","BAD_POS","银行"]],"t":"银行运作。","p":[]}""")
                }, checkpoint = { saved = JSONObject(it.toString()) })
                fail("Must stop")
            } catch (e: AiFailure) {
                assertEquals("BATCH_VALIDATION_FAILED", e.code)
                assertTrue(e.detail.contains("pos"))
                assertTrue(e.detail.contains("1:work"))
            }
            assertEquals(it + 1, calls)
        }
        try {
            AnalysisRecovery.resolve(batch, saved, request = { _, _ -> calls++; error("Must not request again") }, checkpoint = {})
            fail("Must block")
        } catch (e: AiFailure) { assertEquals("CORRECTION_REQUIRED", e.code) }
        assertEquals(2, calls)
        val feedback = JSONObject(AiRequests.context(batch, false, saved!!.getString("_diagnosis")))
        assertTrue(feedback.getString("validation_feedback").contains("BAD_POS"))
    }
}
