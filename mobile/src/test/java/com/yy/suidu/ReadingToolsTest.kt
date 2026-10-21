package com.yy.suidu

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReadingToolsTest {
    @Test fun reviewSelectionKeepsOriginalContextAndOnlyTranslatesDisplayedExcerpt() {
        val text="Banks work. People walk by the banks."
        val token=LanguageCore.paragraphs(text).first().tokens.last()
        val occurrence=Occurrence("a","测试",token,"bank","NOUN","河岸",emptyList(),text)
        val excerpt=ReadingTools.excerpt(text,token.start,token.end)
        val card=ReviewCard("c","bank","NOUN","河岸",emptyList(),"a",token.id,excerpt.text,0,0,0,
            targetStart=excerpt.targetStart,targetEnd=excerpt.targetEnd,shortened=excerpt.shortened)
        val selection=ReadingTools.reviewSelection(card,occurrence)
        assertEquals("People walk by the banks.",selection.text)
        assertEquals(text,selection.paragraph)
        assertEquals(12,selection.start)
    }
    @Test fun ocrAllowsExcludedPagesButPreservesContinuationOrder() {
        val json = JSONObject("""{"title":"银行如何服务社区","pages":[{"page":1,"text":"Banks work."},{"page":2,"text":""},{"page":3,"text":"People save money."}],"uncertainties":[],"excluded":["第2页为练习题"]}""")
        val result = AiContract.ocr(json,3)
        assertEquals("Banks work.\n\nPeople save money.",result.text)
        assertEquals("银行如何服务社区",result.title)
        assertTrue(result.notes.single().contains("练习题"))
        json.getJSONArray("pages").getJSONObject(2).put("page",2)
        assertThrows(AiFailure::class.java) { AiContract.ocr(json,3) }
    }
    @Test fun ocrRejectsAllNonArticleImages() {
        val json=JSONObject("""{"title":"无正文","pages":[{"page":1,"text":""}],"uncertainties":[],"excluded":["第1页广告"]}""")
        assertThrows(AiFailure::class.java) { AiContract.ocr(json,1) }
    }
    @Test fun wordQuestionHasStableSourcePrefixAndBoundsLongArticles() {
        val article=Article("a","银行的作用","Banks work.",2,123L)
        val token=LanguageCore.paragraphs(article.text).first().tokens.first()
        val word=Occurrence("a",article.title,token,"bank","NOUN","银行",emptyList(),article.text)
        val first=AiRequests.wordQuestion(article,word,"为什么？")
        val second=AiRequests.wordQuestion(article,word,"解释语法")
        assertEquals(first.substringBefore(",\"question\":"),second.substringBefore(",\"question\":"))
        assertEquals("Banks work.",JSONObject(first).getString("paragraph"))
        assertEquals("为什么？",JSONObject(first).getString("question"))
        val long=JSONObject(AiRequests.wordQuestion(article.copy(text="Banks work. ".repeat(5000)),word,"主旨是什么？"))
        assertFalse(long.getBoolean("context_complete"))
        assertTrue(long.getString("article_context").length < 12000)
        assertThrows(IllegalArgumentException::class.java) { AiRequests.wordQuestion(article,word,"x".repeat(601)) }
    }
    @Test fun excerptUsesTheExactOccurrenceAndKeepsSentencePunctuation() {
        val text = "Banks open early. We walk along the banks after lunch."
        val start = text.lastIndexOf("banks")
        val result = ReadingTools.excerpt(text, start, start + 5)
        assertEquals("We walk along the banks after lunch.", result.text)
        assertEquals("banks", result.text.substring(result.targetStart, result.targetEnd))
        assertFalse(result.shortened)
    }
    @Test fun longSentenceBecomesBoundedOriginalExcerptWithTargetAndEllipses() {
        val text = "😀 " + (0..59).joinToString(" ") { if (it == 45) "banks" else "word" } + "."
        val start = text.indexOf("banks")
        val result = ReadingTools.excerpt(text, start, start + 5)
        assertTrue(result.shortened)
        assertTrue(result.text.startsWith("… "))
        assertTrue(LanguageCore.paragraphs(result.text).sumOf { it.tokens.size } <= 26)
        assertEquals("banks", result.text.substring(result.targetStart, result.targetEnd))
        assertTrue(text.contains(result.text.removePrefix("… ").removeSuffix(" …")))
    }
    @Test fun sentenceAtEndAndContractionsRemainIntact() {
        val text = "It ended yesterday. Banks don't close"
        val start = text.indexOf("don't")
        val result = ReadingTools.excerpt(text, start, start + 5)
        assertEquals("Banks don't close", result.text)
        assertEquals("don't", result.text.substring(result.targetStart, result.targetEnd))
    }
    @Test fun selectionCacheIncludesContextAndCharacterRange() {
        val first = TextSelection("a", 0, 0, 5, "Banks work.")
        assertEquals(first.cacheKey, first.copy(articleId = "b", paragraphId = 4).cacheKey)
        assertNotEquals(first.cacheKey, first.copy(paragraph = "Banks fail.").cacheKey)
        assertNotEquals(first.cacheKey, first.copy(end = 10).cacheKey)
        val request = JSONObject(AiRequests.selection(first))
        assertEquals("Banks", request.getString("selected"))
        assertEquals("Banks work.", request.getString("paragraph"))
    }
    @Test fun translationPromptBoundsContextAndRejectsOversizedSelection() {
        val paragraph = "x".repeat(16000)
        val request = JSONObject(AiRequests.selection(TextSelection("a",0,7000,9000,paragraph)))
        assertEquals(2000,request.getString("selected").length)
        assertEquals(3200,request.getString("paragraph").length)
        assertThrows(IllegalArgumentException::class.java) { AiRequests.selection(TextSelection("a",0,0,4001,paragraph)) }
    }
    @Test fun meaningsPutReadablePartOfSpeechAfterTheMeaning() {
        assertEquals("银行（n. 名词）",ReadingTools.meaningWithPos("银行","NOUN"))
        assertEquals("conj. 连词",ReadingTools.partOfSpeech("CONJ"))
    }
}
