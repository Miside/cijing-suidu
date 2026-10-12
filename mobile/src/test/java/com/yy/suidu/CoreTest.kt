package com.yy.suidu

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class CoreTest {
    @Test fun quotesPossessivesAndWindowsParagraphsRemainExact() {
        val text = "'Victorians' work.\r\n\r\nTeachers’ books aren't cheap."
        val paragraphs = LanguageCore.paragraphs(text)
        assertEquals(2, paragraphs.size)
        assertEquals(listOf("Victorians", "work", "Teachers’", "books", "aren't", "cheap"), paragraphs.flatMap { it.tokens }.map { it.surface })
        paragraphs.flatMap { it.tokens }.forEach { assertEquals(it.surface, text.substring(it.start, it.end)) }
    }
    @Test fun positionsSurviveUnicodeAndParagraphs() {
        val text = "😀 Banks work.\n\nThey’ve worked at a river-bank."
        val words = LanguageCore.paragraphs(text).flatMap { it.tokens }
        assertEquals(words.indices.toList(), words.map { it.id })
        words.forEach { assertEquals(it.surface, text.substring(it.start, it.end)) }
        assertEquals(3, words.first().start) // Surrogate pair followed by one space.
    }
    @Test fun whitespaceDedupDoesNotMergeDifferentArticles() {
        assertEquals(LanguageCore.articleHash(" Banks work. \n\n They sleep. "), LanguageCore.articleHash("Banks work. They sleep."))
        assertNotEquals(LanguageCore.articleHash("Banks work."), LanguageCore.articleHash("Banks sleep."))
    }
    @Test fun cacheKeysIgnoreArticlePositionButPreserveParagraphContent() {
        val first = LanguageCore.batches("Before sentence.\n\nBanks work.\n\nAfter sentence.")[1]
        val shifted = LanguageCore.batches("Extra words.\n\nBefore sentence.\n\nBanks work.\n\nAfter sentence.")[2]
        assertEquals(LanguageCore.contextCacheKey(first), LanguageCore.contextCacheKey(shifted))
        assertEquals(AiRequests.context(first), AiRequests.context(shifted))
        assertNotEquals(LanguageCore.contextCacheKey(first), LanguageCore.contextCacheKey(LanguageCore.batches("Banks sleep.").first()))
        assertNotEquals(LanguageCore.contextCacheKey(first), LanguageCore.contextCacheKey(LanguageCore.batches("Different context.\n\nBanks work.\n\nAfter sentence.")[1]))
    }
    @Test fun variableTargetsComeAfterSharedPrefixAndTranslationsAreNotRepeated() {
        val text = (1..161).joinToString(" ") { "word" } + "."
        val batches = LanguageCore.batches(text)
        assertEquals(3, batches.size)
        val a = AiRequests.context(batches[0])
        val b = AiRequests.context(batches[1])
        assertEquals(a.substringBefore(",\"targets\""), b.substringBefore(",\"targets\""))
        assertTrue(a.endsWith("\"translate\":true}"))
        assertTrue(b.endsWith("\"translate\":false}"))
        assertEquals(161, batches.flatMap { it.tokens }.map { it.id }.distinct().size)
    }
    @Test fun cachedResponseRebasesEveryWordAndPhrase() {
        val b = LanguageCore.batches("Old paragraph.\n\nBanks work.").last()
        val json = JSONObject("""{"w":[[0,"bank","NOUN","银行"],[1,"work","VERB","工作"]],"t":"银行运作。","p":[["Banks work","银行运作",[0,1]]]}""")
        val parsed = AiContract.analysis(json, b)
        assertEquals(listOf(2, 3), parsed.words.map { it.token.id })
        assertEquals(listOf(2, 3), parsed.phrases.single().tokenIds)
        parsed.words.forEach { assertTrue(it.token.id in b.tokens.map { t -> t.id }) }
    }
    @Test fun omissionsDuplicatesAndInvalidPhrasesAreRejected() {
        val b = LanguageCore.batches("Banks work.").first()
        val good = """{"w":[[0,"bank","NOUN","银行"],[1,"work","VERB","工作"]],"t":"银行运作。","p":[]}"""
        assertEquals(2, AiContract.analysis(JSONObject(good), b).words.size)
        assertThrows(Exception::class.java) { AiContract.analysis(JSONObject(good.replace("[1,\"work\"", "[0,\"work\"")), b) }
        assertThrows(Exception::class.java) { AiContract.analysis(JSONObject(good).put("w", JSONArray().put(JSONArray("[0,\"bank\",\"NOUN\",\"银行\"]"))), b) }
        assertThrows(Exception::class.java) { AiContract.analysis(JSONObject(good).put("p", JSONArray("[[\"wrong phrase\",\"错\",[0,1]]]")), b) }
    }
    @Test fun dictionaryIsCompleteAndDistinct() {
        val good = JSONObject("""{"d":[["bank",["n. 银行","n. 河岸"]],["work",["v. 工作"]]]}""")
        assertEquals(2, AiContract.dictionary(good, listOf("bank", "work")).size)
        assertThrows(Exception::class.java) { AiContract.dictionary(good, listOf("bank")) }
        assertThrows(Exception::class.java) { AiContract.dictionary(JSONObject("""{"d":[["bank",[]]]}"""), listOf("bank")) }
    }
    @Test fun frequencyDoesNotControlReviewSchedule() {
        val now = 12345L
        val fail = LanguageCore.schedule(0, 4, 2, now)
        assertEquals(now + 600_000L, fail.due)
        assertEquals(3, fail.lapses)
        assertEquals(0, fail.repetitions)
        assertEquals(now + 86_400_000L, LanguageCore.schedule(2, 0, 0, now).due)
        assertEquals(now + 3 * 86_400_000L, LanguageCore.schedule(2, 1, 0, now).due)
        assertEquals(now + 120 * 86_400_000L, LanguageCore.schedule(2, 1000, 0, now).due)
    }
}
