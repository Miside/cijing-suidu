package com.yy.suidu

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

data class WordToken(val id: Int, val surface: String, val start: Int, val end: Int, val paragraph: Int)
data class Paragraph(val id: Int, val text: String, val start: Int, val tokens: List<WordToken>)
data class WordMeaning(
    val token: WordToken, val lemma: String, val pos: String,
    val meaning: String, val common: List<String>,
)
data class PhraseMeaning(val text: String, val meaning: String, val tokenIds: List<Int>)
data class AnalysisBatch(
    val number: Int, val paragraph: Paragraph, val tokens: List<WordToken>,
    val before: String = "", val after: String = "",
)
data class ParsedBatch(val words: List<WordMeaning>, val translation: String, val phrases: List<PhraseMeaning>)
data class ReviewStep(val repetitions: Int, val due: Long, val lapses: Int)

object LanguageCore {
    const val MAX_TEXT = 60_000
    const val BATCH_WORDS = 80
    const val CONTRACT_VERSION = "suidu-context-latin-v3"
    const val TOKENIZATION_VERSION = 2
    private val legacyWord = Regex("[A-Za-z]+(?:[’'][A-Za-z]+)*(?:[-‐‑][A-Za-z]+(?:[’'][A-Za-z]+)*)*")
    private const val LETTERS = "\\p{IsLatin}[\\p{IsLatin}\\p{M}]*"
    private val word = Regex("$LETTERS(?:[’']$LETTERS)*(?:[-‐‑]$LETTERS(?:[’']$LETTERS)*)*")
    fun paragraphs(text: String, tokenization: Int = TOKENIZATION_VERSION): List<Paragraph> {
        require(tokenization in 1..TOKENIZATION_VERSION)
        var next = 0
        val quoteEnds = mutableSetOf<Int>()
        val straight = ArrayDeque<Int>()
        val curly = ArrayDeque<Int>()
        text.forEachIndexed { i, c ->
            val before = text.getOrNull(i - 1)?.isLetter() == true
            val after = text.getOrNull(i + 1)?.isLetter() == true
            when {
                c == '‘' -> curly.addLast(i)
                c == '\'' && !before && after -> straight.addLast(i)
                c == '\'' && before && !after && straight.isNotEmpty() -> { straight.removeLast(); quoteEnds += i }
                c == '’' && before && !after && curly.isNotEmpty() -> { curly.removeLast(); quoteEnds += i }
            }
        }
        return Regex("[^\\r\\n]+(?:\\r?\\n(?![ \\t]*\\r?\\n)[^\\r\\n]+)*").findAll(text).mapIndexed { index, match ->
            val tokens = (if (tokenization == 1) legacyWord else word).findAll(match.value).map { token ->
                val start = match.range.first + token.range.first
                var end = match.range.first + token.range.last + 1
                if (token.value.endsWith("s", true) && text.getOrNull(end) in listOf('\'', '’') && end !in quoteEnds) end++
                WordToken(next++, text.substring(start, end), start, end, index)
            }.toList()
            Paragraph(index, match.value, match.range.first, tokens)
        }.toList()
    }
    /** Saved word positions are authoritative, including articles created by the ASCII tokenizer. */
    fun readingParagraphs(text: String, saved: List<WordToken>): List<Paragraph> {
        val byParagraph = saved.groupBy { it.paragraph }
        return paragraphs(text).map { it.copy(tokens = byParagraph[it.id].orEmpty().sortedBy { token -> token.start }) }
    }
    fun batches(text: String, tokenization: Int = TOKENIZATION_VERSION): List<AnalysisBatch> {
        require(text.isNotBlank() && text.length <= MAX_TEXT) { "正文应为 1–60000 个字符" }
        val result = mutableListOf<AnalysisBatch>()
        val paragraphs = paragraphs(text, tokenization)
        for ((index, p) in paragraphs.withIndex()) {
            require(p.text.length <= 16000) { "段落过长，请在正文预览里分段" }
            p.tokens.chunked(BATCH_WORDS).forEach { result += AnalysisBatch(result.size, p, it,
                paragraphs.getOrNull(index - 1)?.text?.takeLast(600) ?: "",
                paragraphs.getOrNull(index + 1)?.text?.take(600) ?: "") }
        }
        require(result.isNotEmpty()) { "没有识别到英文单词，请检查正文" }
        return result
    }
    fun lemma(value: String): String {
        val result = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace('’', '\'').replace('‐', '-').replace('‑', '-')
        require(Regex("$LETTERS(?:[-']$LETTERS)*").matches(result)) { "词元格式不正确：$value" }
        return result
    }
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun articleHash(text: String): String = hash(
        Normalizer.normalize(text, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " "),
    )
    fun cardId(lemma: String, pos: String, meaning: String): String = hash(
        "$lemma\u0000$pos\u0000${meaning.trim().replace(Regex("\\s+"), " ")}",
    )
    fun localBatch(batch: AnalysisBatch): AnalysisBatch {
        val base = batch.paragraph.tokens.first().id
        return batch.copy(paragraph = batch.paragraph.copy(tokens = batch.paragraph.tokens.map { it.copy(id = it.id - base) }),
            tokens = batch.tokens.map { it.copy(id = it.id - base) })
    }
    fun contextCacheKey(batch: AnalysisBatch, version: String = CONTRACT_VERSION): String {
        val local = localBatch(batch)
        return hash("$version\u0000${local.before}\u0000${local.paragraph.text}\u0000${local.after}\u0000${local.tokens.first().id}:${local.tokens.last().id}")
    }
    /** Ratings are local actions, never inferred from clicks or article frequency. */
    fun schedule(rating: Int, repetitions: Int, lapses: Int, now: Long): ReviewStep {
        require(rating in 0..2)
        return when (rating) {
            0 -> ReviewStep(0, now + 10 * 60_000L, lapses + 1)
            1 -> ReviewStep(repetitions.coerceAtMost(1), now + 86_400_000L, lapses)
            else -> {
                val intervals = listOf(1, 3, 7, 14, 30, 60, 120)
                val r = repetitions.coerceIn(0, intervals.lastIndex)
                ReviewStep((r + 1).coerceAtMost(intervals.size), now + intervals[r] * 86_400_000L, lapses)
            }
        }
    }
}
