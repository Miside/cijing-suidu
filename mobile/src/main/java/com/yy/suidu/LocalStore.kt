package com.yy.suidu

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.UUID

data class Draft(
    val id: String, val title: String, val text: String, val images: List<String>,
    val stage: String, val error: String, val notes: String, val chunks: Int, val total: Int,
    val inputTokens: Long, val outputTokens: Long,
    val cacheHitTokens: Long = 0, val cacheMissTokens: Long = 0,
    val tokenization: Int = LanguageCore.TOKENIZATION_VERSION,
    val kind: String = ImportKind.ARTICLE,
)
data class Article(val id: String, val title: String, val text: String, val words: Int, val created: Long, val kind: String = ImportKind.ARTICLE)
data class Vocabulary(val lemma: String, val count: Int, val articles: Int, val clicks: Int, val difficult: Int)
data class Occurrence(
    val articleId: String, val articleTitle: String, val token: WordToken,
    val lemma: String, val pos: String, val meaning: String, val common: List<String>, val paragraphText: String, val paragraphStart: Int = 0,
)
data class ReviewCard(
    val id: String, val lemma: String, val pos: String, val meaning: String, val common: List<String>,
    val articleId: String, val tokenId: Int, val context: String, val repetitions: Int, val lapses: Int, val due: Long,
    val reviews: Int = 0, val targetStart: Int = -1, val targetEnd: Int = -1,
    val shortened: Boolean = false, val sourceTitle: String = "", val contextChoices: Int = 1,
)

class LocalStore(context: Context) : SQLiteOpenHelper(context, "suidu-local.db", null, 5) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE drafts(id TEXT PRIMARY KEY,title TEXT NOT NULL,text TEXT NOT NULL,images TEXT NOT NULL,stage TEXT NOT NULL,error TEXT NOT NULL,notes TEXT NOT NULL,created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE batches(draft_id TEXT NOT NULL REFERENCES drafts(id) ON DELETE CASCADE,number INTEGER NOT NULL,json TEXT NOT NULL,PRIMARY KEY(draft_id,number))")
        db.execSQL("CREATE TABLE usage(id INTEGER PRIMARY KEY AUTOINCREMENT,draft_id TEXT NOT NULL,input_tokens INTEGER NOT NULL,output_tokens INTEGER NOT NULL,cache_hit INTEGER NOT NULL,cache_miss INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE dictionary(lemma TEXT PRIMARY KEY,meanings TEXT NOT NULL)")
        db.execSQL("CREATE TABLE context_cache(key TEXT PRIMARY KEY,json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE ocr_cache(key TEXT PRIMARY KEY,json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE articles(id TEXT PRIMARY KEY REFERENCES drafts(id),title TEXT NOT NULL,text TEXT NOT NULL,hash TEXT NOT NULL UNIQUE,created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE paragraphs(article_id TEXT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,number INTEGER NOT NULL,start INTEGER NOT NULL,text TEXT NOT NULL,translation TEXT NOT NULL,PRIMARY KEY(article_id,number))")
        db.execSQL("CREATE TABLE occurrences(article_id TEXT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,token_id INTEGER NOT NULL,surface TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,paragraph INTEGER NOT NULL,lemma TEXT NOT NULL,pos TEXT NOT NULL,meaning TEXT NOT NULL,common TEXT NOT NULL,PRIMARY KEY(article_id,token_id))")
        db.execSQL("CREATE INDEX word_index ON occurrences(lemma)")
        db.execSQL("CREATE TABLE phrases(article_id TEXT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,ids TEXT NOT NULL,text TEXT NOT NULL,meaning TEXT NOT NULL,PRIMARY KEY(article_id,ids))")
        db.execSQL("CREATE TABLE clicks(lemma TEXT PRIMARY KEY,count INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE cards(id TEXT PRIMARY KEY,lemma TEXT NOT NULL,pos TEXT NOT NULL,meaning TEXT NOT NULL,common TEXT NOT NULL,article_id TEXT NOT NULL REFERENCES articles(id),token_id INTEGER NOT NULL,context TEXT NOT NULL,repetitions INTEGER NOT NULL,lapses INTEGER NOT NULL,due INTEGER NOT NULL,rating INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX due_index ON cards(due)")
        createFragments(db)
        createReadingTools(db)
        upgradeFour(db)
        upgradeFive(db)
    }
    private fun createFragments(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE analysis_fragments(draft_id TEXT NOT NULL REFERENCES drafts(id) ON DELETE CASCADE,number INTEGER NOT NULL,json TEXT NOT NULL,PRIMARY KEY(draft_id,number))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createFragments(db)
        if (oldVersion < 3) createReadingTools(db)
        if (oldVersion < 4) upgradeFour(db)
        if (oldVersion < 5) upgradeFive(db)
    }
    private fun upgradeFive(db: SQLiteDatabase) {
        val exists = db.rawQuery("PRAGMA table_info(drafts)", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == "kind") found = true
            found
        }
        if (!exists) db.execSQL("ALTER TABLE drafts ADD COLUMN kind TEXT NOT NULL DEFAULT 'article'")
    }
    private fun upgradeFour(db: SQLiteDatabase) {
        fun has(table: String, column: String) = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == column) found = true
            found
        }
        if (!has("drafts", "tokenization")) db.execSQL("ALTER TABLE drafts ADD COLUMN tokenization INTEGER NOT NULL DEFAULT 1")
        if (!has("drafts", "total")) {
            db.execSQL("ALTER TABLE drafts ADD COLUMN total INTEGER NOT NULL DEFAULT 0")
            db.rawQuery("SELECT id,text,tokenization FROM drafts", null).use { c ->
                while (c.moveToNext()) db.update("drafts", values("total" to runCatching { LanguageCore.batches(c.getString(1), c.getInt(2)).size }.getOrDefault(0)), "id=?", arrayOf(c.getString(0)))
            }
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS cards_lemma_index ON cards(lemma)")
        if (!has("occurrences", "sense_id")) {
            db.execSQL("ALTER TABLE occurrences ADD COLUMN sense_id TEXT NOT NULL DEFAULT ''")
            db.rawQuery("SELECT article_id,token_id,lemma,pos,meaning FROM occurrences", null).use { c ->
                while (c.moveToNext()) db.update("occurrences", values("sense_id" to LanguageCore.cardId(c.getString(2), c.getString(3), c.getString(4))), "article_id=? AND token_id=?", arrayOf(c.getString(0), c.getString(1)))
            }
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS occurrence_sense_index ON occurrences(sense_id)")
        if (!has("usage", "import_key")) {
            db.execSQL("ALTER TABLE usage ADD COLUMN import_key TEXT NOT NULL DEFAULT ''")
            val origin = UUID.randomUUID().toString()
            db.execSQL("UPDATE usage SET import_key=? || ':' || id", arrayOf(origin))
        }
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS usage_import_index ON usage(import_key)")
    }
    private fun createReadingTools(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE selection_cache(key TEXT PRIMARY KEY,translation TEXT NOT NULL)")
        db.execSQL("ALTER TABLE cards ADD COLUMN reviews INTEGER NOT NULL DEFAULT 0")
    }
    @Synchronized fun selectedTranslation(key: String): String? = readableDatabase.rawQuery("SELECT translation FROM selection_cache WHERE key=?", arrayOf(key))
        .use { if (it.moveToFirst()) it.getString(0) else null }
    @Synchronized fun saveSelectedTranslation(key: String, translation: String) {
        require(translation.isNotBlank() && translation.length <= 8000)
        writableDatabase.insertWithOnConflict("selection_cache", null, values("key" to key, "translation" to translation), SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun values(vararg pairs: Pair<String, Any>): ContentValues = ContentValues().apply {
        pairs.forEach { (key, value) -> when (value) {
            is String -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            else -> error("Unsupported value")
        } }
    }
    private fun strings(json: String): List<String> = JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } }
    @Synchronized fun createDraft(images: List<String>, id: String = UUID.randomUUID().toString(), kind: String = ImportKind.ARTICLE): String {
        ImportKind.check(kind)
        writableDatabase.insertOrThrow("drafts", null, values("id" to id, "title" to if (kind == ImportKind.CLOZE) "新完形填空" else "新文章", "text" to "", "images" to JSONArray(images).toString(),
            "stage" to "images", "error" to "", "notes" to "", "created" to System.currentTimeMillis(), "total" to 0,
            "tokenization" to if (kind == ImportKind.CLOZE) ClozeCore.TOKENIZATION else LanguageCore.TOKENIZATION_VERSION, "kind" to kind))
        return id
    }
    @Synchronized fun updateImages(id: String, images: List<String>) {
        require(draft(id)?.stage == "images")
        writableDatabase.update("drafts", values("images" to JSONArray(images).toString()), "id=?", arrayOf(id))
    }
    @Synchronized fun drafts(): List<Draft> = readableDatabase.rawQuery("""
        SELECT d.*, (SELECT COUNT(*) FROM batches b WHERE b.draft_id=d.id) chunks,
        COALESCE((SELECT SUM(input_tokens) FROM usage u WHERE u.draft_id=d.id),0) input_tokens,
        COALESCE((SELECT SUM(output_tokens) FROM usage u WHERE u.draft_id=d.id),0) output_tokens
        ,COALESCE((SELECT SUM(cache_hit) FROM usage u WHERE u.draft_id=d.id),0) cache_hit
        ,COALESCE((SELECT SUM(cache_miss) FROM usage u WHERE u.draft_id=d.id),0) cache_miss
        FROM drafts d WHERE stage!='ready' ORDER BY created DESC
    """.trimIndent(), null).use { c -> buildList { while (c.moveToNext()) add(draft(c)) } }
    @Synchronized fun draft(id: String): Draft? = readableDatabase.rawQuery("""
        SELECT d.*, (SELECT COUNT(*) FROM batches b WHERE b.draft_id=d.id) chunks,
        COALESCE((SELECT SUM(input_tokens) FROM usage u WHERE u.draft_id=d.id),0) input_tokens,
        COALESCE((SELECT SUM(output_tokens) FROM usage u WHERE u.draft_id=d.id),0) output_tokens
        ,COALESCE((SELECT SUM(cache_hit) FROM usage u WHERE u.draft_id=d.id),0) cache_hit
        ,COALESCE((SELECT SUM(cache_miss) FROM usage u WHERE u.draft_id=d.id),0) cache_miss
        FROM drafts d WHERE id=?
    """.trimIndent(), arrayOf(id)).use { c -> if (c.moveToFirst()) draft(c) else null }
    private fun draft(c: Cursor): Draft {
        val text = c.s("text")
        return Draft(c.s("id"), c.s("title"), text, strings(c.s("images")), c.s("stage"), c.s("error"), c.s("notes"),
            c.i("chunks"), c.i("total"), c.l("input_tokens"), c.l("output_tokens"), c.l("cache_hit"), c.l("cache_miss"), c.i("tokenization"), c.s("kind"))
    }
    @Synchronized fun setError(id: String, error: String) { writableDatabase.update("drafts", values("error" to error.take(4000)), "id=?", arrayOf(id)) }
    @Synchronized fun ocrFailures(id: String, fingerprint: String): Int {
        val d = draft(id) ?: return 0
        if (d.stage != "images") return 0
        val notes = runCatching { JSONObject(d.notes) }.getOrNull() ?: return 0
        return if (notes.optString("fingerprint") == fingerprint) notes.optInt("ocr_failures", 0).coerceIn(0, 2) else 0
    }
    @Synchronized fun recordOcrFailure(id: String, fingerprint: String) {
        val notes = JSONObject().put("fingerprint", fingerprint).put("ocr_failures", (ocrFailures(id, fingerprint) + 1).coerceAtMost(2))
        writableDatabase.update("drafts", values("notes" to notes.toString()), "id=? AND stage='images'", arrayOf(id))
    }
    @Synchronized fun setStage(id: String, stage: String) { writableDatabase.update("drafts", values("stage" to stage, "error" to ""), "id=?", arrayOf(id)) }
    @Synchronized fun saveOcr(id: String, result: OcrResult) {
        val draft = draft(id) ?: error("草稿不存在")
        if (draft.kind == ImportKind.CLOZE && result.text.isNotBlank()) ClozeCore.parse(result.text)
        val version = if (draft.kind == ImportKind.CLOZE) ClozeCore.TOKENIZATION else LanguageCore.TOKENIZATION_VERSION
        val total = runCatching { LanguageCore.batches(result.text, version).size }.getOrDefault(0)
        writableDatabase.update("drafts", values("title" to result.title, "text" to result.text, "notes" to result.notes.joinToString("\n"), "stage" to "confirm", "error" to "", "total" to total, "tokenization" to version), "id=?", arrayOf(id))
    }
    @Synchronized fun editText(id: String, title: String, text: String) {
        val old = draft(id) ?: error("草稿不存在")
        require(old.stage in setOf("confirm", "analysis"))
        if (old.kind == ImportKind.CLOZE) require(ClozeCore.parse(text).sourceText() == text) { "请先核对并保存完形填空的正文和选项" }
        val tokenization = if (old.kind == ImportKind.CLOZE) ClozeCore.TOKENIZATION else if (old.text == text) old.tokenization else LanguageCore.TOKENIZATION_VERSION
        val total = LanguageCore.batches(text, tokenization).size
        require(title.isNotBlank() && title.length <= 200)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (old.text != text) {
                db.delete("batches", "draft_id=?", arrayOf(id))
                db.delete("analysis_fragments", "draft_id=?", arrayOf(id))
            }
            db.update("drafts", values("title" to title.trim(), "text" to text, "stage" to if (old.text != text) "confirm" else old.stage, "error" to "", "total" to total, "tokenization" to tokenization), "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun logUsage(id: String, result: ApiResult) {
        writableDatabase.insertOrThrow("usage", null, values("draft_id" to id, "input_tokens" to result.inputTokens, "output_tokens" to result.outputTokens, "cache_hit" to result.cacheHitTokens, "cache_miss" to result.cacheMissTokens, "import_key" to UUID.randomUUID().toString()))
    }
    @Synchronized fun batch(id: String, n: Int): JSONObject? = readableDatabase.rawQuery("SELECT json FROM batches WHERE draft_id=? AND number=?", arrayOf(id, n.toString()))
        .use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    @Synchronized fun saveBatch(id: String, batch: AnalysisBatch, json: JSONObject) {
        AiContract.analysis(json, batch)
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(db.insertWithOnConflict("batches", null, values("draft_id" to id, "number" to batch.number, "json" to json.toString()), SQLiteDatabase.CONFLICT_REPLACE) >= 0)
            db.delete("analysis_fragments", "draft_id=? AND number=?", arrayOf(id, batch.number.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun fragment(id: String, batch: AnalysisBatch): JSONObject? = readableDatabase.rawQuery("SELECT json FROM analysis_fragments WHERE draft_id=? AND number=?", arrayOf(id, batch.number.toString()))
        .use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    @Synchronized fun saveFragment(id: String, batch: AnalysisBatch, json: JSONObject) {
        val clean = AnalysisProgress(batch, json).snapshot()
        clean.put("_failures", json.optInt("_failures", 0).coerceIn(0, AnalysisRecovery.MAX_INVALID_RESPONSES))
        clean.put("_diagnosis", json.optString("_diagnosis").take(1500))
        writableDatabase.insertWithOnConflict("analysis_fragments", null, values("draft_id" to id, "number" to batch.number, "json" to clean.toString()), SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun cachedContext(batch: AnalysisBatch): JSONObject? {
        fun read(key: String): JSONObject? = readableDatabase.rawQuery("SELECT json FROM context_cache WHERE key=?", arrayOf(key))
            .use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
        val legacy = LanguageCore.paragraphs(batch.paragraph.text, 1).first().tokens
        val compatible = legacy.map { Triple(it.surface, it.start, it.end) } ==
            batch.paragraph.tokens.map { Triple(it.surface, it.start - batch.paragraph.start, it.end - batch.paragraph.start) }
        val cached = read(LanguageCore.contextCacheKey(batch)) ?: if (compatible && !batch.cloze)
            read(LanguageCore.contextCacheKey(batch, "suidu-context-explicit-ids-v2")) ?: read(LanguageCore.contextCacheKey(batch, "suidu-context-compact-v1")) else null
        return cached?.takeIf { runCatching { AiContract.analysis(it, batch) }.isSuccess }
    }
    @Synchronized fun cacheContext(batch: AnalysisBatch, json: JSONObject) {
        AiContract.analysis(json, batch)
        writableDatabase.insertWithOnConflict("context_cache", null, values("key" to LanguageCore.contextCacheKey(batch), "json" to json.toString()), SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun cachedOcr(key: String): JSONObject? = readableDatabase.rawQuery("SELECT json FROM ocr_cache WHERE key=?", arrayOf(key))
        .use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    @Synchronized fun cacheOcr(key: String, json: JSONObject) { writableDatabase.insertWithOnConflict("ocr_cache", null, values("key" to key, "json" to json.toString()), SQLiteDatabase.CONFLICT_REPLACE) }
    @Synchronized fun definitions(lemma: String): List<String>? = readableDatabase.rawQuery("SELECT meanings FROM dictionary WHERE lemma=?", arrayOf(lemma))
        .use { if (it.moveToFirst()) strings(it.getString(0)) else null }
    @Synchronized fun missingDefinitions(lemmas: List<String>): List<String> = lemmas.distinct().sorted().filter { definitions(it) == null }
    @Synchronized fun saveDefinitions(definitions: Map<String, List<String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            definitions.forEach { (lemma, meanings) -> db.insertWithOnConflict("dictionary", null,
                values("lemma" to lemma, "meanings" to JSONArray(meanings).toString()), SQLiteDatabase.CONFLICT_IGNORE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun usageTotals(): List<Long> = readableDatabase.rawQuery("SELECT COALESCE(SUM(input_tokens),0),COALESCE(SUM(output_tokens),0),COALESCE(SUM(cache_hit),0),COALESCE(SUM(cache_miss),0) FROM usage", null)
        .use { it.moveToFirst(); (0..3).map { i -> it.getLong(i) } }
    @Synchronized fun existingArticle(text: String): String? = readableDatabase.rawQuery("SELECT id FROM articles WHERE hash=?", arrayOf(LanguageCore.articleHash(text)))
        .use { if (it.moveToFirst()) it.getString(0) else null }
    @Synchronized fun publish(id: String): String {
        val draft = draft(id) ?: error("找不到草稿")
        if (draft.kind == ImportKind.CLOZE) ClozeCore.parse(draft.text)
        existingArticle(draft.text)?.let { existing -> setError(id, "这篇文章已经在书库里，词频不会重复计入"); return existing }
        val batches = LanguageCore.batches(draft.text, draft.tokenization)
        val parsed = batches.map { b -> b to AiContract.analysis(batch(id, b.number) ?: error("逐词解析尚未完成"), b) }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("articles", null, values("id" to id, "title" to draft.title, "text" to draft.text, "hash" to LanguageCore.articleHash(draft.text), "created" to System.currentTimeMillis()))
            parsed.groupBy { it.first.paragraph.id }.forEach { (number, list) ->
                val p = list.first().first.paragraph
                db.insertOrThrow("paragraphs", null, values("article_id" to id, "number" to number, "start" to p.start, "text" to p.text, "translation" to list.first().second.translation))
            }
            // Paragraphs with no English tokens still remain visible in the full-text reader.
            parsed.forEach { (_, result) ->
                result.words.forEach { w -> db.insertOrThrow("occurrences", null, values(
                    "article_id" to id, "token_id" to w.token.id, "surface" to w.token.surface, "start" to w.token.start, "end" to w.token.end,
                    "paragraph" to w.token.paragraph, "lemma" to w.lemma, "pos" to w.pos, "meaning" to w.meaning,
                    "common" to JSONArray(definitions(w.lemma) ?: error("${w.lemma} 尚缺常见释义")).toString(), "sense_id" to LanguageCore.cardId(w.lemma, w.pos, w.meaning))) }
                result.phrases.forEach { p -> db.insertWithOnConflict("phrases", null, values("article_id" to id, "ids" to JSONArray(p.tokenIds).toString(), "text" to p.text, "meaning" to p.meaning), SQLiteDatabase.CONFLICT_IGNORE) }
            }
            db.update("drafts", values("stage" to "ready", "error" to ""), "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return id
    }
    @Synchronized fun articles(): List<Article> = readableDatabase.rawQuery("SELECT a.id,a.title,a.created,d.kind, (SELECT COUNT(*) FROM occurrences o WHERE o.article_id=a.id) words FROM articles a JOIN drafts d ON d.id=a.id ORDER BY a.created DESC", null)
        .use { c -> buildList { while (c.moveToNext()) add(Article(c.s("id"), c.s("title"), "", c.i("words"), c.l("created"), c.s("kind"))) } }
    @Synchronized fun article(id: String): Article? = readableDatabase.rawQuery("SELECT a.*,d.kind, (SELECT COUNT(*) FROM occurrences o WHERE o.article_id=a.id) words FROM articles a JOIN drafts d ON d.id=a.id WHERE a.id=?", arrayOf(id))
        .use { c -> if (c.moveToFirst()) Article(c.s("id"), c.s("title"), c.s("text"), c.i("words"), c.l("created"), c.s("kind")) else null }
    @Synchronized fun vocabulary(): List<Vocabulary> = readableDatabase.rawQuery("""
        SELECT o.lemma,COUNT(*) n,COUNT(DISTINCT article_id) articles,
        COALESCE((SELECT count FROM clicks c WHERE c.lemma=o.lemma),0) clicks,
        (SELECT COUNT(*) FROM cards c WHERE c.lemma=o.lemma AND rating<2) difficult
        FROM occurrences o GROUP BY o.lemma ORDER BY n DESC,o.lemma
    """.trimIndent(), null).use { c -> buildList { while (c.moveToNext()) add(Vocabulary(c.s("lemma"), c.i("n"), c.i("articles"), c.i("clicks"), c.i("difficult"))) } }
    @Synchronized fun click(lemma: String) {
        writableDatabase.execSQL("INSERT INTO clicks(lemma,count) VALUES(?,1) ON CONFLICT(lemma) DO UPDATE SET count=count+1", arrayOf(lemma))
    }
    @Synchronized fun occurrences(articleId: String? = null, lemma: String? = null, senseId: String? = null): List<Occurrence> {
        val clause = when { articleId != null -> "o.article_id=?"; senseId != null -> "o.sense_id=?"; lemma != null -> "o.lemma=?"; else -> error("需要文章或词元") }
        return readableDatabase.rawQuery("""
            SELECT o.*,a.title article_title,p.text paragraph_text,p.start paragraph_start FROM occurrences o
            JOIN articles a ON a.id=o.article_id JOIN paragraphs p ON p.article_id=o.article_id AND p.number=o.paragraph
            WHERE $clause ORDER BY a.created DESC,o.token_id
        """.trimIndent(), arrayOf(articleId ?: senseId ?: lemma!!)).use { c -> buildList {
            while (c.moveToNext()) add(Occurrence(c.s("article_id"), c.s("article_title"),
                WordToken(c.i("token_id"), c.s("surface"), c.i("start"), c.i("end"), c.i("paragraph")),
                c.s("lemma"), c.s("pos"), c.s("meaning"), strings(c.s("common")), c.s("paragraph_text"), c.i("paragraph_start")))
        } }
    }
    @Synchronized fun translations(id: String): Map<Int, String> = readableDatabase.rawQuery("SELECT number,translation FROM paragraphs WHERE article_id=?", arrayOf(id))
        .use { c -> buildMap { while (c.moveToNext()) put(c.getInt(0), c.getString(1)) } }
    @Synchronized fun phrases(id: String): List<PhraseMeaning> = readableDatabase.rawQuery("SELECT * FROM phrases WHERE article_id=?", arrayOf(id))
        .use { c -> buildList { while (c.moveToNext()) add(PhraseMeaning(c.s("text"), c.s("meaning"), JSONArray(c.s("ids")).let { a -> (0 until a.length()).map { a.getInt(it) } })) } }
    @Synchronized fun rate(o: Occurrence, rating: Int, now: Long = System.currentTimeMillis()) {
        val id = LanguageCore.cardId(o.lemma, o.pos, o.meaning)
        val card = card(id)
        val step = LanguageCore.schedule(rating, card?.repetitions ?: 0, card?.lapses ?: 0, now)
        writableDatabase.insertWithOnConflict("cards", null, values("id" to id, "lemma" to o.lemma, "pos" to o.pos, "meaning" to o.meaning, "common" to JSONArray(o.common).toString(),
            "article_id" to o.articleId, "token_id" to o.token.id, "context" to o.paragraphText,
            "repetitions" to step.repetitions, "lapses" to step.lapses, "due" to step.due, "rating" to rating,
            "reviews" to (card?.reviews ?: 0)), SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun rateCard(card: ReviewCard, rating: Int, now: Long = System.currentTimeMillis()) {
        val step = LanguageCore.schedule(rating, card.repetitions, card.lapses, now)
        writableDatabase.update("cards", values("repetitions" to step.repetitions, "lapses" to step.lapses, "due" to step.due, "rating" to rating,
            "reviews" to card.reviews + 1), "id=?", arrayOf(card.id))
    }
    @Synchronized fun due(now: Long = System.currentTimeMillis()): List<ReviewCard> = readableDatabase.rawQuery("""
        SELECT c.* FROM cards c WHERE due<=? ORDER BY due,
        (SELECT COUNT(*) FROM occurrences o WHERE o.lemma=c.lemma) DESC LIMIT 100
    """.trimIndent(), arrayOf(now.toString())).use { c ->
        val sources = mutableMapOf<String,List<Occurrence>>()
        buildList { while (c.moveToNext()) {
            val card = review(c)
            add(reviewContext(card, sources.getOrPut(card.id) { occurrences(senseId = card.id) }))
        } }
    }
    @Synchronized fun card(id: String): ReviewCard? = readableDatabase.rawQuery("SELECT * FROM cards WHERE id=?", arrayOf(id))
        .use { if (it.moveToFirst()) review(it) else null }
    private fun review(c: Cursor) = ReviewCard(c.s("id"), c.s("lemma"), c.s("pos"), c.s("meaning"), strings(c.s("common")), c.s("article_id"), c.i("token_id"), c.s("context"), c.i("repetitions"), c.i("lapses"), c.l("due"), c.i("reviews"))
    private fun reviewContext(card: ReviewCard, sources: List<Occurrence>): ReviewCard {
        val candidates = sources.filter { LanguageCore.cardId(it.lemma, it.pos, it.meaning) == card.id }
            .map { o -> o to ReadingTools.excerpt(o.paragraphText, o.token.start - o.paragraphStart, o.token.end - o.paragraphStart) }
            .sortedWith(compareBy<Pair<Occurrence, ReviewExcerpt>> { it.second.shortened }.thenBy { it.second.text.length }.thenBy { it.first.articleId }.thenBy { it.first.token.id })
            .distinctBy { LanguageCore.articleHash(it.second.text) }
        if (candidates.isEmpty()) return card
        // Keep a stable sentence for three completed reviews; then rotate only within this exact sense.
        val (o, excerpt) = candidates[(card.reviews / 3) % candidates.size]
        return card.copy(articleId = o.articleId, tokenId = o.token.id, context = excerpt.text,
            targetStart = excerpt.targetStart, targetEnd = excerpt.targetEnd, shortened = excerpt.shortened,
            sourceTitle = o.articleTitle, contextChoices = candidates.size)
    }
    @Synchronized fun reviewCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM cards", null).use { it.moveToFirst(); it.getInt(0) }
    @Synchronized fun removeReview(lemma: String) {
        writableDatabase.delete("cards", "lemma=?", arrayOf(lemma))
    }
    /** Preserve a shared sense's schedule when another saved article still contains it. */
    @Synchronized fun deleteArticle(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cards = db.rawQuery("SELECT * FROM cards WHERE article_id=?", arrayOf(id)).use { c ->
                buildList { while (c.moveToNext()) add(review(c)) }
            }
            cards.forEach { card ->
                val replacement = occurrences(lemma = card.lemma).firstOrNull {
                    it.articleId != id && LanguageCore.cardId(it.lemma, it.pos, it.meaning) == card.id
                }
                if (replacement == null) db.delete("cards", "id=?", arrayOf(card.id))
                else db.update("cards", values("article_id" to replacement.articleId, "token_id" to replacement.token.id,
                    "context" to replacement.paragraphText), "id=?", arrayOf(card.id))
            }
            db.delete("articles", "id=?", arrayOf(id))
            db.delete("drafts", "id=?", arrayOf(id))
            db.execSQL("DELETE FROM clicks WHERE lemma NOT IN (SELECT lemma FROM occurrences)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun discard(id: String) {
        require(article(id) == null) { "已入库文章不能作为草稿删除" }
        writableDatabase.delete("drafts", "id=?", arrayOf(id))
    }
    /** Backups exclude API keys and source images. */
    @Synchronized fun export(output: OutputStream) = LearningBackup.export(readableDatabase, output)
    @Synchronized fun restore(input: java.io.InputStream): RestoreSummary = LearningBackup.restore(writableDatabase, input)
}
private fun Cursor.s(name: String) = getString(getColumnIndexOrThrow(name))
private fun Cursor.i(name: String) = getInt(getColumnIndexOrThrow(name))
private fun Cursor.l(name: String) = getLong(getColumnIndexOrThrow(name))
