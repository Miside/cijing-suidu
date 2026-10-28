package com.yy.suidu

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class RestoreSummary(val articles: Int, val drafts: Int, val cards: Int, val skipped: Int)

/** Only learning data is portable. Device image paths and encryption keys never enter backups. */
object LearningBackup {
    private val tables = listOf("drafts", "batches", "analysis_fragments", "dictionary", "context_cache",
        "articles", "paragraphs", "occurrences", "phrases", "clicks", "cards", "usage", "selection_cache")
    fun export(db: SQLiteDatabase, output: OutputStream) {
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("{\"format\":\"suidu-learning-export\",\"version\":3,\"exported_at\":" +
                System.currentTimeMillis() + ",\"images_included\":false,\"tables\":{")
            tables.forEachIndexed { index, table ->
                if (index > 0) writer.write(",")
                writer.write(JSONObject.quote(table) + ":[")
                db.rawQuery("SELECT * FROM " + table, null).use { c ->
                    var first = true
                    while (c.moveToNext()) {
                        val row = JSONObject()
                        for (col in 0 until c.columnCount) row.put(c.getColumnName(col), when (c.getType(col)) {
                            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(col)
                            android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            else -> c.getString(col)
                        })
                        if (table == "drafts") row.put("images", "[]")
                        if (!first) writer.write(",")
                        first = false
                        writer.write(row.toString())
                    }
                }
                writer.write("]")
            }
            writer.write("}}")
        }
    }

    fun restore(db: SQLiteDatabase, input: InputStream): RestoreSummary {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(bytes.size() + n <= 32 * 1024 * 1024) { "备份超过 32 MB，请先精简备份" }
            bytes.write(buffer, 0, n)
        }
        val root = JSONObject(bytes.toString("UTF-8"))
        require(root.optString("format") == "suidu-learning-export" && root.optInt("version") in 1..3) { "不是支持的随读学习备份（支持版本 1、2、3）" }
        val source = root.getJSONObject("tables")
        require(source.keys().asSequence().all { it in tables }) { "备份含未知数据表，请先升级应用" }
        for (name in listOf("articles", "paragraphs", "occurrences", "cards")) require(source.opt(name) is JSONArray) { "备份缺少必要数据表：" + name }
        fun rows(table: String): List<JSONObject> {
            val array = source.optJSONArray(table) ?: return emptyList()
            require(array.length() <= 200_000)
            return (0 until array.length()).map { array.getJSONObject(it) }
        }
        fun text(row: JSONObject, key: String, max: Int, blank: Boolean = false): String {
            require(row.opt(key) is String) { "备份字段格式错误：" + key }
            return row.getString(key).also { require(it.length <= max && (blank || it.isNotBlank())) { "备份字段为空或过长：" + key } }
        }
        fun number(row: JSONObject, key: String, default: Long? = null): Long {
            if (!row.has(key) && default != null) return default
            val v = row.get(key)
            require(v is Number && v.toDouble().isFinite() && v.toDouble() == v.toLong().toDouble() && v.toLong() >= 0) { "备份数值不合法：" + key }
            return v.toLong()
        }
        fun strings(value: String) {
            val a = JSONArray(value)
            require(a.length() in 1..8)
            for (i in 0 until a.length()) require(a.get(i) is String && a.getString(i).length in 1..500)
        }
        val articles = rows("articles")
        val articleIndex = articles.associateBy { text(it, "id", 100) }
        val safeId = Regex("[A-Za-z0-9_-]{1,100}")
        require(articleIndex.keys.all { safeId.matches(it) }) { "备份文章编号格式错误" }
        require(articleIndex.size == articles.size) { "备份包含重复文章编号" }
        articles.forEach { a ->
            val body = text(a, "text", LanguageCore.MAX_TEXT)
            text(a, "title", 200); number(a, "created")
            require(a.getString("hash") == LanguageCore.articleHash(body)) { "文章正文校验失败" }
        }
        val paragraphs = rows("paragraphs")
        val paragraphIndex = paragraphs.associateBy { it.getString("article_id") + ":" + number(it, "number") }
        require(paragraphIndex.size == paragraphs.size)
        paragraphs.forEach { p ->
            val body = articleIndex[p.getString("article_id")]?.getString("text") ?: error("段落缺少文章")
            val start = number(p, "start")
            val value = text(p, "text", 16000)
            text(p, "translation", 16000, true)
            require(start + value.length <= body.length && body.substring(start.toInt(), start.toInt() + value.length) == value) { "段落位置与正文不一致" }
        }
        val occurrences = rows("occurrences")
        val occurrenceIndex = occurrences.associateBy { it.getString("article_id") + ":" + number(it, "token_id") }
        require(occurrenceIndex.size == occurrences.size)
        occurrences.forEach { o ->
            val body = articleIndex[o.getString("article_id")]?.getString("text") ?: error("词位缺少文章")
            val start = number(o, "start")
            val end = number(o, "end")
            val p = paragraphIndex[o.getString("article_id") + ":" + number(o, "paragraph")] ?: error("词位缺少所属段落")
            require(start >= p.getInt("start") && end > start && end <= p.getInt("start") + p.getString("text").length &&
                end <= body.length && body.substring(start.toInt(), end.toInt()) == text(o, "surface", 200)) { "词位位置与原文不一致" }
            require(AiContract.wordProblem(JSONArray().put(0).put(text(o, "lemma", 100)).put(text(o, "pos", 12)).put(text(o, "meaning", 500))) == null) { "无效词元或释义" }
            strings(text(o, "common", 8000))
            o.put("sense_id", LanguageCore.cardId(o.getString("lemma"), o.getString("pos"), o.getString("meaning")))
        }
        val cards = rows("cards")
        cards.forEach { c ->
            require(text(c, "id", 100) == LanguageCore.cardId(text(c, "lemma", 100), text(c, "pos", 12), text(c, "meaning", 500))) { "复习卡义项校验失败" }
            val o = occurrenceIndex[c.getString("article_id") + ":" + number(c, "token_id")] ?: error("复习卡缺少原文词位")
            require(o.getString("sense_id") == c.getString("id"))
            text(c, "context", 16000); strings(text(c, "common", 8000))
            require(number(c, "rating") in 0..2 && number(c, "repetitions") <= 7)
            number(c, "lapses"); number(c, "due"); c.put("reviews", number(c, "reviews", 0))
        }
        rows("phrases").forEach { p ->
            text(p, "text", 200); text(p, "meaning", 500)
            val ids = JSONArray(p.getString("ids"))
            val values = (0 until ids.length()).map { ids.getInt(it) }
            require(values.size in 2..12 && values == values.distinct().sorted() &&
                values.all { (p.getString("article_id") + ":" + it) in occurrenceIndex }) { "短语引用了无效词位" }
        }
        val sourceDrafts = rows("drafts").associateBy { text(it, "id", 100) }
        require(sourceDrafts.keys.all { safeId.matches(it) }) { "备份草稿编号格式错误" }
        require(sourceDrafts.size == rows("drafts").size) { "备份包含重复草稿编号" }
        sourceDrafts.values.forEach { d ->
            text(d, "title", 200); text(d, "text", LanguageCore.MAX_TEXT, true)
            val kind = ImportKind.check(d.optString("kind", ImportKind.ARTICLE))
            val tokenization = number(d, "tokenization", 1)
            require(tokenization in 1..LanguageCore.MAX_TOKENIZATION_VERSION)
            require(d.optString("stage") in setOf("images", "confirm", "analysis", "ready"))
            require((kind == ImportKind.CLOZE) == (tokenization == ClozeCore.TOKENIZATION.toLong())) { "完形填空分词版本与导入类型不符" }
            if (kind == ImportKind.CLOZE && d.getString("stage") != "images" && d.getString("text").isNotBlank()) {
                require(ClozeCore.parse(d.getString("text")).sourceText() == d.getString("text")) { "备份中的完形填空正文与选项不完整" }
            }
        }
        val columns = tables.associateWith { table -> db.rawQuery("PRAGMA table_info(" + table + ")", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(1)) }
        } }
        fun insert(table: String, row: JSONObject, ignore: Boolean = true) {
            val values = ContentValues()
            for (key in row.keys()) if (key in columns.getValue(table)) when (val v = row.get(key)) {
                is String -> values.put(key, v)
                is Number -> { require(v.toDouble() == v.toLong().toDouble()); values.put(key, v.toLong()) }
                JSONObject.NULL -> values.putNull(key)
                else -> error("备份字段类型不合法：" + key)
            }
            val result = db.insertWithOnConflict(table, null, values, if (ignore) SQLiteDatabase.CONFLICT_IGNORE else SQLiteDatabase.CONFLICT_ABORT)
            if (!ignore) check(result >= 0)
        }
        fun exists(table: String, id: String) = db.rawQuery("SELECT 1 FROM " + table + " WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
        val idMap = mutableMapOf<String, String>()
        val addedArticles = mutableSetOf<String>()
        val newArticleSources = mutableSetOf<String>()
        val addedDrafts = mutableSetOf<String>()
        var addedCards = 0
        var skipped = 0
        db.beginTransaction()
        try {
            articles.forEach { a ->
                val oldId = a.getString("id")
                val existing = db.rawQuery("SELECT id FROM articles WHERE hash=?", arrayOf(a.getString("hash"))).use { if (it.moveToFirst()) it.getString(0) else null }
                val target = existing ?: if (exists("drafts", oldId)) UUID.randomUUID().toString() else oldId
                idMap[oldId] = target
                if (existing != null) { skipped++; return@forEach }
                val d = sourceDrafts[oldId]?.let { JSONObject(it.toString()) } ?: JSONObject().put("title", a.getString("title"))
                    .put("text", a.getString("text")).put("notes", "").put("created", a.getLong("created")).put("tokenization", 1)
                require(d.getString("text") == a.getString("text")) { "文章与草稿正文不一致" }
                d.put("id", target).put("images", "[]").put("stage", "ready").put("error", "")
                    .put("total", LanguageCore.batches(d.getString("text"), d.optInt("tokenization", 1)).size)
                insert("drafts", d, false); addedDrafts += target
                insert("articles", JSONObject(a.toString()).put("id", target), false)
                addedArticles += target; newArticleSources += oldId
            }
            sourceDrafts.forEach { (oldId, original) ->
                if (oldId in idMap) return@forEach
                idMap[oldId] = oldId
                if (exists("drafts", oldId)) { skipped++; return@forEach }
                require(original.getString("stage") != "ready") { "已入库草稿缺少文章" }
                val d = JSONObject(original.toString()).put("images", "[]").put("error", "")
                val version = d.optInt("tokenization", 1)
                d.put("tokenization", version).put("total", runCatching { LanguageCore.batches(d.getString("text"), version).size }.getOrDefault(0))
                insert("drafts", d, false); addedDrafts += oldId
            }
            for (table in listOf("paragraphs", "occurrences", "phrases")) rows(table).forEach { original ->
                val target = idMap[original.getString("article_id")] ?: error("正文记录缺少文章")
                if (original.getString("article_id") in newArticleSources)
                    insert(table, JSONObject(original.toString()).put("article_id", target), false)
            }
            for (table in listOf("batches", "analysis_fragments")) rows(table).forEach { original ->
                val oldId = original.getString("draft_id")
                val target = idMap[oldId] ?: error("解析记录缺少草稿")
                if (oldId in articleIndex && oldId !in newArticleSources) return@forEach
                if (target !in addedDrafts) return@forEach
                val d = sourceDrafts[oldId] ?: return@forEach
                val batch = LanguageCore.batches(d.getString("text"), d.optInt("tokenization", 1)).getOrNull(number(original, "number").toInt()) ?: error("解析批次无效")
                val payload = JSONObject(text(original, "json", 200_000))
                if (table == "batches") AiContract.analysis(payload, batch) else AnalysisProgress(batch, payload)
                insert(table, JSONObject(original.toString()).put("draft_id", target), false)
            }
            rows("dictionary").forEach { d ->
                LanguageCore.lemma(text(d, "lemma", 100)); strings(text(d, "meanings", 8000)); insert("dictionary", d)
            }
            rows("clicks").forEach { c ->
                val lemma = LanguageCore.lemma(text(c, "lemma", 100))
                val count = number(c, "count").coerceAtMost(Int.MAX_VALUE.toLong())
                db.execSQL("INSERT INTO clicks(lemma,count) VALUES(?,?) ON CONFLICT(lemma) DO UPDATE SET count=MAX(count,excluded.count)", arrayOf(lemma, count))
            }
            cards.forEach { original ->
                if (exists("cards", original.getString("id"))) return@forEach
                val oldId = original.getString("article_id")
                val target = idMap.getValue(oldId)
                val word = occurrenceIndex.getValue(oldId + ":" + original.getInt("token_id"))
                val token = db.rawQuery("SELECT token_id FROM occurrences WHERE article_id=? AND sense_id=? AND start=? AND end=?",
                    arrayOf(target, original.getString("id"), word.getInt("start").toString(), word.getInt("end").toString())).use { if (it.moveToFirst()) it.getInt(0) else null }
                if (token == null) { skipped++; return@forEach }
                insert("cards", JSONObject(original.toString()).put("article_id", target).put("token_id", token), false); addedCards++
            }
            rows("usage").forEach { original ->
                val oldId = text(original, "draft_id", 100)
                for (key in listOf("input_tokens", "output_tokens", "cache_hit", "cache_miss")) number(original, key)
                val key = original.optString("import_key").ifBlank { "legacy:" + LanguageCore.hash(oldId + ":" + original.getLong("id")) }
                require(key.length <= 200)
                if (root.getInt("version") == 1 && idMap[oldId] !in addedDrafts &&
                    db.rawQuery("SELECT 1 FROM usage WHERE draft_id=? LIMIT 1", arrayOf(idMap[oldId] ?: oldId)).use { it.moveToFirst() }) return@forEach
                val copy = JSONObject(original.toString()).put("draft_id", idMap[oldId] ?: oldId).put("import_key", key)
                copy.remove("id"); insert("usage", copy)
            }
            for (table in listOf("context_cache", "selection_cache")) rows(table).forEach { row ->
                require(Regex("[a-f0-9]{64}").matches(text(row, "key", 64)))
                text(row, if (table == "context_cache") "json" else "translation", if (table == "context_cache") 200_000 else 8000)
                insert(table, row)
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) { "备份引用不完整，未恢复任何数据" } }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return RestoreSummary(addedArticles.size, addedDrafts.size - addedArticles.size, addedCards, skipped)
    }
}
