package com.yy.suidu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClozeTest {
    private val body = "😀 She [[21]] home.\n\nCafé life was [[22]]."
    private val options = "21. A. walked  B. sang  C. slept  D. read\n\n22. A. happy  B. quickly  C. inside  D. with"
    private fun exercise() = ClozeCore.fromParts(body, options)
    private fun payload() = JSONObject().put("title", "回家的路").put("pages", JSONArray()
        .put(JSONObject().put("page", 2).put("text", "Café life was [[22]]."))
        .put(JSONObject().put("page", 1).put("text", "😀 She [[21]] home.")))
        .put("questions", JSONArray(exercise().questions.reversed().map { q -> JSONObject().put("number", q.number)
            .put("options", JSONArray(q.options.map { JSONArray().put(it.label).put(it.text) })) }))
        .put("uncertainties", JSONArray().put("示例疑点"))

    @Test fun multiPageOcrKeepsOriginalNumbersAndEveryUnselectedOption() {
        val result = AiContract.ocr(payload(), 2, ImportKind.CLOZE)
        assertEquals(exercise().sourceText(), result.text)
        assertEquals(listOf(21, 22), ClozeCore.parse(result.text).questions.map { it.number })
        assertEquals(8, ClozeCore.parse(result.text).questions.sumOf { it.options.size })
        assertFalse(result.text.contains("answer")); assertEquals(listOf("示例疑点"), result.notes)
    }
    @Test fun optionsOnlyPageDoesNotDropItsQuestionsOrRequireBodyText() {
        val json = payload()
        json.getJSONArray("pages").put(JSONObject().put("page", 3).put("text", ""))
        assertEquals(exercise().sourceText(), AiContract.ocr(json, 3, ImportKind.CLOZE).text)
    }
    @Test fun missingRepeatedOrMismatchedQuestionsCannotEnterReader() {
        val missing = payload(); missing.getJSONArray("questions").remove(0)
        assertThrows(AiFailure::class.java) { AiContract.ocr(missing, 2, ImportKind.CLOZE) }
        assertThrows(Exception::class.java) { ClozeCore.fromParts(body.replace("[[22]]", "[[21]]"), options) }
        assertThrows(Exception::class.java) { ClozeCore.fromParts(body.replace("[[22]]", "[[23]]"), options) }
        assertThrows(Exception::class.java) { ClozeCore.fromParts(body.replace("[[22]]", "happy"), options) }
        assertThrows(Exception::class.java) { ClozeCore.fromParts(body, options.replace("B. sang", "C. sang")) }
        assertThrows(Exception::class.java) { ClozeCore.fromParts(body, options.replace("B. sang", "B. ")) }
    }
    @Test fun malformedPagesAndNonIntegerQuestionNumbersAreRejected() {
        val duplicate = payload(); duplicate.getJSONArray("pages").getJSONObject(0).put("page", 1)
        assertThrows(AiFailure::class.java) { AiContract.ocr(duplicate, 2, ImportKind.CLOZE) }
        val fraction = payload(); fraction.getJSONArray("questions").getJSONObject(0).put("number", 22.5)
        assertThrows(AiFailure::class.java) { AiContract.ocr(fraction, 2, ImportKind.CLOZE) }
    }
    @Test fun editingAndRoundTripPreserveLongOptionsAndAbbreviations() {
        val value = ClozeCore.fromParts("He [[1]] it.", "1. A. a grade A. result  B. a long\ncontinued option  C. A  D. résumé")
        assertEquals("a grade A. result", value.questions.first().options.first().text)
        assertEquals("a long continued option", value.questions.first().options[1].text)
        assertEquals(value, ClozeCore.parse(value.sourceText()))
    }
    @Test fun optionLabelsAreExcludedButOptionWordsAndUtf16PositionsArePreserved() {
        val text = exercise().sourceText()
        val tokens = LanguageCore.paragraphs(text, ClozeCore.TOKENIZATION).flatMap { it.tokens }
        assertEquals(listOf("She", "home", "Café", "life", "was", "walked", "sang", "slept", "read", "happy", "quickly", "inside", "with"), tokens.map { it.surface })
        assertEquals(tokens.indices.toList(), tokens.map { it.id })
        tokens.forEach { assertEquals(it.surface, text.substring(it.start, it.end)) }
        assertEquals(tokens, LanguageCore.readingParagraphs(text, tokens).flatMap { it.tokens })
        assertTrue(LanguageCore.paragraphs("A. real sentence.").first().tokens.any { it.surface == "A" })
    }
    @Test fun clozeAnalysisHasSeparateCacheAndExplicitBlankContract() {
        val text = exercise().sourceText()
        val cloze = LanguageCore.batches(text, ClozeCore.TOKENIZATION).first()
        val ordinary = LanguageCore.batches(text).first()
        assertTrue(cloze.cloze)
        assertNotEquals(LanguageCore.contextCacheKey(cloze), LanguageCore.contextCacheKey(ordinary))
        assertTrue(JSONObject(AiRequests.context(cloze)).getBoolean("cloze"))
        assertFalse(JSONObject(AiRequests.context(ordinary)).has("cloze"))
    }
    @Test fun generatedTranslationCannotFillOrDropTheOriginalBlanks() {
        val batch = LanguageCore.batches(exercise().sourceText(), ClozeCore.TOKENIZATION).first()
        val words = JSONArray(LanguageCore.localBatch(batch).tokens.map { JSONArray().put(it.id).put(LanguageCore.lemma(it.surface)).put("NOUN").put("示例词义") })
        val guessed = JSONObject().put("w", words).put("t", "她走回了家。").put("p", JSONArray())
        assertThrows(Exception::class.java) { AiContract.analysis(guessed, batch) }
        val progress = AnalysisProgress(batch)
        assertTrue(progress.merge(guessed, batch, true).any { it.contains("空格") })
        assertEquals(batch.tokens.size, progress.count); assertTrue(progress.needsTranslation)
        val corrected = JSONObject(guessed.toString()).put("t", "她 [[21]] 回家。")
        progress.merge(corrected, batch, true)
        assertTrue(progress.complete)
        assertEquals("她 [[21]] 回家。", AiContract.analysis(progress.snapshot(), batch).translation)
    }
    @Test fun displaySubstitutionKeepsSavedWordOffsetsExactly() {
        val text = exercise().sourceText()
        val display = ClozeCore.display(text)
        assertEquals(text.length, display.length)
        LanguageCore.paragraphs(text, ClozeCore.TOKENIZATION).flatMap { it.tokens }.forEach {
            assertEquals(it.surface, display.substring(it.start, it.end))
        }
        assertTrue(display.contains("__21__")); assertFalse(display.contains("[[21]]"))
    }
}
