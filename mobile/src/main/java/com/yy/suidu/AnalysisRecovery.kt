package com.yy.suidu

import org.json.JSONArray
import org.json.JSONObject

/** Accumulate only validated, unambiguous rows, without ever shifting or guessing word IDs. */
class AnalysisProgress(private val batch: AnalysisBatch, saved: JSONObject? = null) {
    private val words = sortedMapOf<Int, JSONArray>()
    private val phrases = linkedMapOf<String, JSONArray>()
    private val local = LanguageCore.localBatch(batch)
    private var translation = ""
    val needsTranslation: Boolean get() = local.tokens.first().id == 0 && translation.isBlank()
    val missing: List<WordToken> get() = batch.tokens.filter { it.id - batch.paragraph.tokens.first().id !in words }
    val complete: Boolean get() = missing.isEmpty() && !needsTranslation
    val count: Int get() = words.size

    init { if (saved != null) merge(saved, batch, true) }

    fun merge(response: JSONObject, requested: AnalysisBatch, translate: Boolean) {
        val allowed = LanguageCore.localBatch(requested).tokens.map { it.id }.toSet()
        val rows = response.optJSONArray("w") ?: JSONArray()
        val grouped = (0 until rows.length()).mapNotNull { rows.optJSONArray(it) }
            .groupBy { row ->
                val v = row.opt(0)
                if (v is Number && v.toDouble() == v.toInt().toDouble()) v.toInt() else -1
            }
        grouped.forEach { (id, group) ->
            if (id in allowed && id !in words) {
                val clean = group.map { AiContract.cleanWord(it, batch) }
                // Identical duplicates are harmless. Conflicting or malformed duplicates must be re-requested.
                if (clean.all { it != null } && clean.map { it.toString() }.distinct().size == 1) words[id] = clean.first()!!
            }
        }
        if (translate && local.tokens.first().id == 0 && translation.isBlank()) {
            val value = response.opt("t")
            if (value is String && value.trim().length in 1..16000) translation = value.trim()
        }
        val p = response.optJSONArray("p") ?: JSONArray()
        for (i in 0 until minOf(p.length(), 50)) {
            val row = p.optJSONArray(i) ?: continue
            val clean = AiContract.cleanPhrase(row, batch) ?: continue
            val key = clean.getJSONArray(2).toString()
            if (phrases.size < 50 || key in phrases) phrases.putIfAbsent(key, clean)
        }
    }

    fun snapshot(): JSONObject = JSONObject().put("w", JSONArray(words.values.toList()))
        .put("t", translation).put("p", JSONArray(phrases.values.toList()))

    fun requests(size: Int): List<AnalysisBatch> =
        (if (missing.isEmpty() && needsTranslation) listOf(batch.tokens.take(1)) else missing.chunked(size))
            .map { batch.copy(tokens = it) }
}

object AnalysisRecovery {
    const val MAX_REQUESTS = 10
    suspend fun resolve(
        batch: AnalysisBatch, saved: JSONObject?,
        request: suspend (AnalysisBatch, Boolean) -> JSONObject,
        checkpoint: suspend (JSONObject) -> Unit,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): JSONObject {
        val result = AnalysisProgress(batch, saved)
        var calls = 0
        suspend fun fetch(target: AnalysisBatch) {
            progress(result.count, batch.tokens.size)
            val translate = result.needsTranslation
            val response = request(target, translate)
            calls++
            result.merge(response, target, translate)
            checkpoint(result.snapshot())
        }
        if (saved == null) fetch(batch)
        for (size in listOf(20, 8)) {
            if (result.complete) break
            for (target in result.requests(size)) {
                if (calls >= MAX_REQUESTS || result.complete) break
                fetch(target)
            }
        }
        check(result.complete) {
            "本批已保留 ${result.count}/${batch.tokens.size} 个词位，仍缺 ${result.missing.size} 个${if (result.needsTranslation) "及段落译文" else ""}。自动补齐已达到本次上限；点继续只补缺项。"
        }
        return result.snapshot().also { AiContract.analysis(it, batch) }
    }
}
