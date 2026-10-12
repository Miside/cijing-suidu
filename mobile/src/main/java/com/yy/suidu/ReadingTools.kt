package com.yy.suidu

import java.text.BreakIterator
import java.util.Locale

data class TextSelection(val articleId: String, val paragraphId: Int, val start: Int, val end: Int, val paragraph: String) {
    init { require(start in 0 until end && end <= paragraph.length) }
    val text: String get() = paragraph.substring(start, end)
    val cacheKey: String get() = LanguageCore.hash("suidu-selection-v1\u0000$paragraph\u0000$start:$end")
}

data class ReviewExcerpt(val text: String, val targetStart: Int, val targetEnd: Int, val shortened: Boolean)

object ReadingTools {
    fun reviewSelection(card: ReviewCard, occurrence: Occurrence): TextSelection {
        val raw = if (card.shortened) card.context.removePrefix("… ").removeSuffix(" …") else card.context
        val prefix = if (card.shortened && card.context.startsWith("… ")) 2 else 0
        val start = occurrence.token.start - occurrence.paragraphStart - card.targetStart + prefix
        val end = start + raw.length
        require(start >= 0 && end <= occurrence.paragraphText.length && occurrence.paragraphText.substring(start, end) == raw)
        return TextSelection(card.articleId, occurrence.token.paragraph, start, end, occurrence.paragraphText)
    }
    fun partOfSpeech(pos: String): String = when (pos) {
        "NOUN" -> "n. 名词"; "VERB" -> "v. 动词"; "ADJ" -> "adj. 形容词"
        "ADV" -> "adv. 副词"; "PRON" -> "pron. 代词"; "DET" -> "det. 限定词"
        "ADP" -> "prep. 介词"; "AUX" -> "aux. 助动词"; "CONJ" -> "conj. 连词"; "CCONJ" -> "conj. 并列连词"
        "SCONJ" -> "conj. 从属连词"; "NUM" -> "num. 数词"; "PART" -> "part. 小品词"
        "INTJ" -> "int. 感叹词"; "PROPN" -> "专有名词"; else -> "其他词性"
    }
    fun meaningWithPos(meaning: String, pos: String) = "$meaning（${partOfSpeech(pos)}）"

    /** Extract an actual sentence containing this occurrence, never a lemma match elsewhere. */
    fun excerpt(paragraph: String, targetStart: Int, targetEnd: Int, maxWords: Int = 26): ReviewExcerpt {
        require(targetStart in 0 until targetEnd && targetEnd <= paragraph.length && maxWords >= 4)
        val iterator = BreakIterator.getSentenceInstance(Locale.US).apply { setText(paragraph) }
        var begin = iterator.first()
        var finish = iterator.next()
        while (finish != BreakIterator.DONE && targetStart >= finish) { begin = finish; finish = iterator.next() }
        if (finish == BreakIterator.DONE) finish = paragraph.length
        // BreakIterator can treat a period in an abbreviation as a boundary. Never cut the target.
        finish = finish.coerceAtLeast(targetEnd)
        while (begin < targetStart && paragraph[begin].isWhitespace()) begin++
        while (finish > targetEnd && paragraph[finish - 1].isWhitespace()) finish--
        val words = LanguageCore.paragraphs(paragraph.substring(begin, finish)).flatMap { it.tokens }
        if (words.size <= maxWords) return ReviewExcerpt(paragraph.substring(begin, finish), targetStart - begin, targetEnd - begin, false)
        val target = words.indexOfFirst { it.start <= targetStart - begin && it.end >= targetEnd - begin }.coerceAtLeast(0)
        val first = (target - maxWords / 2).coerceIn(0, words.size - maxWords)
        val last = first + maxWords - 1
        val clipStart = if (first == 0) begin else begin + words[first].start
        val clipEnd = if (last == words.lastIndex) finish else begin + words[last].end
        val prefix = if (clipStart > begin) "… " else ""
        val suffix = if (clipEnd < finish) " …" else ""
        return ReviewExcerpt(prefix + paragraph.substring(clipStart, clipEnd) + suffix,
            targetStart - clipStart + prefix.length, targetEnd - clipStart + prefix.length, true)
    }

    fun localTranslation(selection: TextSelection, reader: ReaderData): Pair<String, String>? {
        require(selection.articleId == reader.article.id)
        val paragraph = LanguageCore.paragraphs(reader.article.text).first { it.id == selection.paragraphId }
        require(paragraph.text == selection.paragraph)
        val selected = selection.text.trim()
        if (selected == paragraph.text.trim()) reader.translations[paragraph.id]?.takeIf { it.isNotBlank() }?.let { return it to "已保存的段落译文" }
        val words = paragraph.tokens.filter { it.start - paragraph.start >= selection.start && it.end - paragraph.start <= selection.end }
        if (words.size == 1 && selected == words.single().surface) {
            reader.occurrences.firstOrNull { it.token.id == words.single().id }?.let { return meaningWithPos(it.meaning, it.pos) to "已保存的本文释义" }
        }
        reader.phrases.firstOrNull { phrase ->
            phrase.tokenIds == words.map { it.id } && selected.replace('’', '\'').equals(phrase.text.replace('’', '\''), true)
        }?.let { return it.meaning to "已保存的短语释义" }
        return null
    }
}
