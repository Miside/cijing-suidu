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

    fun merge(response: JSONObject, requested: AnalysisBatch, translate: Boolean): List<String> {
        val issues = mutableListOf<String>()
        val allowed = LanguageCore.localBatch(requested).tokens.map { it.id }.toSet()
        val rows = response.optJSONArray("w") ?: JSONArray()
        if (response.opt("w") !is JSONArray) issues += "w 字段缺失或不是数组"
        for (i in 0 until rows.length()) if (rows.optJSONArray(i) == null) issues += "w[$i] 不是词位数组"
        val grouped = (0 until rows.length()).mapNotNull { rows.optJSONArray(it) }
            .groupBy { row ->
                val v = row.opt(0)
                if (v is Number && v.toDouble() == v.toInt().toDouble()) v.toInt() else -1
            }
        grouped.forEach { (id, group) ->
            if (id !in allowed) issues += "返回了非请求词位 id=$id"
            if (id in allowed && id !in words) {
                val clean = group.map { AiContract.cleanWord(it, batch) }
                // Identical duplicates are harmless. Conflicting or malformed duplicates must be re-requested.
                if (clean.all { it != null } && clean.map { it.toString() }.distinct().size == 1) words[id] = clean.first()!!
                else issues += "词位 id=$id：" + (group.firstNotNullOfOrNull { AiContract.wordProblem(it) } ?: "相同编号出现了互相冲突的释义")
            }
        }
        if (translate && local.tokens.first().id == 0 && translation.isBlank()) {
            val value = response.opt("t")
            if (value is String && value.trim().length in 1..16000) translation = value.trim()
            else issues += "t 字段缺失、为空、类型错误或超过 16000 字符"
        }
        val p = response.optJSONArray("p") ?: JSONArray()
        for (i in 0 until minOf(p.length(), 50)) {
            val row = p.optJSONArray(i) ?: continue
            val clean = AiContract.cleanPhrase(row, batch) ?: continue
            val key = clean.getJSONArray(2).toString()
            if (phrases.size < 50 || key in phrases) phrases.putIfAbsent(key, clean)
        }
        return issues.distinct().take(12)
    }

    fun snapshot(): JSONObject = JSONObject().put("w", JSONArray(words.values.toList()))
        .put("t", translation).put("p", JSONArray(phrases.values.toList()))

    fun requests(size: Int): List<AnalysisBatch> =
        (if (missing.isEmpty() && needsTranslation) listOf(batch.tokens.take(1)) else missing.chunked(size))
            .map { batch.copy(tokens = it) }
}

object AnalysisRecovery {
    const val MAX_REQUESTS = 1
    const val MAX_INVALID_RESPONSES = 2
    suspend fun resolve(
        batch: AnalysisBatch, saved: JSONObject?,
        request: suspend (AnalysisBatch, Boolean) -> JSONObject,
        checkpoint: suspend (JSONObject) -> Unit,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): JSONObject {
        val result = AnalysisProgress(batch, saved)
        val failures = saved?.optInt("_failures", 0)?.coerceIn(0, MAX_INVALID_RESPONSES) ?: 0
        if (!result.complete) {
            if (failures >= MAX_INVALID_RESPONSES) throw AiFailure("逐词解析", "CORRECTION_REQUIRED",
                "第 ${batch.number + 1} 批已连续两次返回不合格结果，已阻止再次原样请求。",
                "查看具体词位问题，修正正文分段或请求规则后再处理；修改正文会重建本篇解析进度。",
                saved?.optString("_diagnosis").orEmpty().take(700))
            val target = if (saved == null) batch else batch.copy(tokens = result.missing.ifEmpty { batch.tokens.take(1) })
            progress(result.count, batch.tokens.size)
            val translate = result.needsTranslation
            val response = try { request(target, translate) } catch (e: AiFailure) {
                if (!e.code.startsWith("HTTP_")) checkpoint(result.snapshot().put("_failures", failures + 1)
                    .put("_diagnosis", e.message.orEmpty().take(1500)))
                throw e
            }
            val issues = result.merge(response, target, translate)
            val missing = result.missing.take(12).joinToString("、") { "${it.id - batch.paragraph.tokens.first().id}:${it.surface}" }
            val diagnosis = (issues + listOfNotNull(missing.takeIf { it.isNotBlank() }?.let { "缺失词位 id:原词 = $it" })).joinToString("；")
            checkpoint(result.snapshot().put("_failures", if (result.complete) 0 else failures + 1).put("_diagnosis", diagnosis.take(1500)))
            if (!result.complete) {
                throw AiFailure("逐词解析", "BATCH_VALIDATION_FAILED",
                    "第 ${batch.paragraph.id + 1} 段 · 第 ${batch.number + 1} 批：已保留 ${result.count}/${batch.tokens.size} 个词位，仍缺 ${result.missing.size} 个${if (result.needsTranslation) "及段落译文" else ""}。",
                    if (failures + 1 >= MAX_INVALID_RESPONSES) "已达到两次不合格上限，停止再次原样请求。请修正正文分段或请求规则。"
                    else "本次已停止。检查下方具体问题；手动继续时仅请求缺项，并把此次校验原因提供给模型纠正，不会自动抽样重试。",
                    diagnosis)
            }
        }
        return result.snapshot().also { AiContract.analysis(it, batch) }
    }
}
