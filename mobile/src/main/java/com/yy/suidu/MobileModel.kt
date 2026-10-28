package com.yy.suidu

import android.app.Application
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class ReaderData(val article: Article, val occurrences: List<Occurrence>, val translations: Map<Int, String>, val phrases: List<PhraseMeaning>, val images: List<String>)
data class SelectionTranslation(val selection: TextSelection, val loading: Boolean = true, val translation: String = "", val source: String = "", val error: String = "")
data class AiExplanation(val key: String, val loading: Boolean = true, val text: String = "", val error: String = "", val cached: Boolean = false)
data class MobileState(
    val articles: List<Article> = emptyList(), val drafts: List<Draft> = emptyList(),
    val vocabulary: List<Vocabulary> = emptyList(), val due: List<ReviewCard> = emptyList(),
    val reviewCount: Int = 0, val configured: Boolean = false, val busy: Boolean = false, val localBusy: Boolean = false, val autoTranslate: Boolean = true,
    val activeDraft: String? = null, val progress: String = "", val message: String = "", val loaded: Boolean = false,
    val reader: ReaderData? = null, val wordHistory: List<Occurrence> = emptyList(),
    val usage: List<Long> = listOf(0, 0, 0, 0),
    val selectionTranslation: SelectionTranslation? = null,
    val reviewTranslation: AiExplanation? = null, val wordAnswer: AiExplanation? = null,
)

class MobileModel @JvmOverloads constructor(app: Application,
    clientFactory: (KeyVault, (ApiResult) -> Unit) -> DeepSeekClient = { vault, usage -> DeepSeekClient(vault, usage) }) : AndroidViewModel(app) {
    private val store = LocalStore(app)
    private val vault = KeyVault(app)
    private val preferences = app.getSharedPreferences("suidu-ui", android.content.Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(MobileState(autoTranslate = preferences.getBoolean("autoTranslate", true)))
    private val refreshMutex = Mutex()
    private val vocabularyRevision = AtomicInteger()
    private val displayedVocabularyRevision = AtomicInteger(-1)
    private var readerRequest = 0
    @Volatile private var selectionUsageOwner: String? = null
    private val client = clientFactory(vault) { result -> (mutable.value.activeDraft ?: selectionUsageOwner)?.let { store.logUsage(it, result) } }
    val state = mutable.asStateFlow()
    private var work: Job? = null
    init { viewModelScope.launch { refresh() } }
    suspend fun refresh(library: Boolean = true, words: Boolean = true, review: Boolean = true) = refreshMutex.withLock {
        val clickRevision = vocabularyRevision.get()
        val data = withContext(Dispatchers.IO) {
            val old = mutable.value
            old.copy(articles = if (library) store.articles() else old.articles,
                drafts = if (library) store.drafts() else old.drafts,
                vocabulary = if (words) store.vocabulary() else old.vocabulary,
                due = if (review) store.due() else old.due,
                reviewCount = if (review) store.reviewCount() else old.reviewCount,
                configured = vault.configured(), loaded = true,
                usage = if (library) store.usageTotals() else old.usage)
        }
        mutable.update { old -> old.copy(articles = if (library) data.articles else old.articles,
            drafts = if (library) data.drafts else old.drafts, vocabulary = if (words) data.vocabulary else old.vocabulary,
            due = if (review) data.due else old.due, reviewCount = if (review) data.reviewCount else old.reviewCount,
            configured = data.configured, usage = if (library) data.usage else old.usage, loaded = true) }
        if (words) displayedVocabularyRevision.set(clickRevision)
    }
    private fun localTask(library: Boolean = false, action: suspend () -> Unit) {
        if (mutable.value.localBusy) return
        mutable.update { it.copy(localBusy = true) }
        viewModelScope.launch {
            try { action() }
            catch (e: Exception) { if (e !is CancellationException) mutable.update { it.copy(message = AiDiagnostics.describe(e, "本地操作")) } }
            finally { withContext(NonCancellable) {
                refresh(library = library)
                mutable.update { it.copy(localBusy = false) }
            } }
        }
    }
    fun pageVisible(tab: Int) { if (tab == 0 || tab == 2 || tab == 3) viewModelScope.launch {
        refresh(library = tab == 0, words = vocabularyRevision.get() != displayedVocabularyRevision.get() || tab == 2, review = tab == 3)
    } }
    fun setAutoTranslate(value: Boolean) {
        preferences.edit().putBoolean("autoTranslate", value).apply()
        mutable.update { it.copy(autoTranslate = value) }
    }
    private fun cleanImages(id: String, references: List<String>) {
        // Cleanup failure must never remove the new image after its reference has committed.
        runCatching { ImportedImages.cleanUnused(File(getApplication<Application>().filesDir, "imports"), id, references) }
    }
    fun clearMessage() { mutable.update { it.copy(message = "") } }
    private fun task(draftId: String? = null, stage: String = "处理任务", action: suspend () -> Unit) {
        if (mutable.value.busy) { mutable.update { it.copy(message = "当前任务完成后再操作") }; return }
        mutable.update { it.copy(busy = true, activeDraft = draftId, message = "") }
        work = viewModelScope.launch {
            try { action() }
            catch (_: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { if (draftId != null) store.setError(draftId, "任务已暂停，已完成批次保留，可继续处理") }
                mutable.update { it.copy(message = "已暂停，可从已保存进度继续") }
            } catch (e: Exception) {
                val message = AiDiagnostics.describe(e, stage)
                withContext(Dispatchers.IO) { if (draftId != null) store.setError(draftId, message) }
                mutable.update { it.copy(message = if (draftId == null) message else "$stage 未完成，具体原因和处理办法已保存在草稿中。") }
            } finally {
                withContext(NonCancellable) {
                    mutable.update { it.copy(busy = false, activeDraft = null, progress = "") }
                    refresh()
                }
            }
        }
    }
    fun cancel() { work?.cancel(); client.cancel() }
    fun saveKey(value: String) = task { withContext(Dispatchers.IO) { vault.save(value) }; mutable.update { it.copy(message = "API Key 已加密保存在本机") } }
    fun removeKey() = task { withContext(Dispatchers.IO) { vault.clear() }; mutable.update { it.copy(message = "API Key 已移除") } }
    fun checkConnection() = task { mutable.update { it.copy(progress = "验证 DeepSeek 连接…") }; val result = client.check(); mutable.update { it.copy(message = result) } }
    fun addImages(uris: List<Uri>, existingId: String? = null, kind: String = ImportKind.ARTICLE, cameraFile: String? = null) = task(existingId) {
        require(uris.isNotEmpty())
        mutable.update { it.copy(progress = "保存图片到本机…") }
        try { withContext(Dispatchers.IO) {
            ImportKind.check(kind)
            val old = existingId?.let { store.draft(it) ?: error("草稿不存在") }
            require(old == null || old.stage == "images")
            require(uris.size + (old?.images?.size ?: 0) <= 12) { "每篇最多 12 张图片，请分批导入文章" }
            val id = old?.id ?: UUID.randomUUID().toString()
            val directory = File(getApplication<Application>().filesDir, "imports/$id").apply { mkdirs() }
            val files = mutableListOf<File>()
            try {
                for ((index, uri) in uris.withIndex()) {
                    coroutineContext.ensureActive()
                    mutable.update { it.copy(progress = "保存第 ${index + 1}/${uris.size} 张图片…") }
                    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)) { decoder, info, _ ->
                        require(info.size.width in 1..40000 && info.size.height in 1..40000) { "图片尺寸过大" }
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val ratio = 2600.0 / maxOf(info.size.width, info.size.height)
                        if (ratio < 1) decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
                    }
                    val file = File(directory, "${UUID.randomUUID()}.jpg")
                    files += file
                    try {
                        file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it)) }
                        if (file.length() > 1_500_000) file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, it)) }
                        require(file.length() <= 3 * 1024 * 1024) { "图片压缩后仍过大，请先裁剪正文区域" }
                    } finally { bitmap.recycle() }
                }
                val all = (old?.images ?: emptyList()) + files.map { it.absolutePath }
                require(all.sumOf { File(it).length() } <= 18 * 1024 * 1024) { "本篇图片合计不能超过 18 MB" }
                if (old == null) store.createDraft(all, id, kind) else store.updateImages(id, all)
                mutable.update { it.copy(activeDraft = id, message = "已保存 ${uris.size} 张图片，请检查页序后开始识别") }
            } catch (e: Exception) { files.forEach { it.delete() }; throw e }
        } } finally { cameraFile?.let { ScanCapture.discard(getApplication(), it) } }
    }
    fun orderImages(id: String, from: Int, to: Int) = task(id) {
        withContext(Dispatchers.IO) {
            val d = store.draft(id) ?: error("草稿不存在")
            val images = d.images.toMutableList()
            require(from in images.indices && to in images.indices)
            images.add(to, images.removeAt(from))
            store.updateImages(id, images)
        }
    }
    fun removeImage(id: String, index: Int) = task(id) {
        withContext(Dispatchers.IO) {
            val d = store.draft(id) ?: error("草稿不存在")
            val remaining = d.images.filterIndexed { i, _ -> i != index }
            store.updateImages(id, remaining)
            cleanImages(id, remaining)
        }
    }
    fun rotateImage(id: String, index: Int) = task(id) {
        withContext(Dispatchers.IO) {
            val d = store.draft(id) ?: error("草稿不存在")
            require(d.stage == "images")
            val original = File(d.images[index])
            val b = android.graphics.BitmapFactory.decodeFile(original.absolutePath) ?: error("图片无法读取")
            val rotated = android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(90f) }, true)
            val target = File(original.parentFile, "${UUID.randomUUID()}.jpg")
            try {
                target.outputStream().use { check(rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it)) }
                val paths = d.images.toMutableList().apply { set(index, target.absolutePath) }
                require(paths.sumOf { File(it).length() } <= 18 * 1024 * 1024) { "图片合计超过 18 MB" }
                store.updateImages(id, paths)
                cleanImages(id, paths)
            } catch (e: Exception) { target.delete(); throw e }
            finally { rotated.recycle(); if (b !== rotated) b.recycle() }
        }
    }
    fun recognize(id: String) = task(id, "图片识别") {
        val d = withContext(Dispatchers.IO) { store.draft(id) ?: error("草稿不存在") }
        require(d.stage == "images" && d.images.isNotEmpty()) { "请先选择图片" }
        val fingerprint = withContext(Dispatchers.IO) {
            val hashes = d.images.map { path ->
                java.security.MessageDigest.getInstance("SHA-256").run {
                    File(path).inputStream().use { input -> val buf = ByteArray(8192); while (true) { val n = input.read(buf); if (n < 0) break; update(buf, 0, n) } }
                    digest().joinToString("") { "%02x".format(it) }
                }
            }
            require(hashes.size == hashes.distinct().size) { "选图中有完全重复的页面，请移除重复图片后识别" }
            LanguageCore.hash((if (d.kind == ImportKind.CLOZE) ClozeCore.OCR_VERSION else "suidu-ocr-body-v2") + "\u0000" + hashes.joinToString("\u0000"))
        }
        val cached = withContext(Dispatchers.IO) { store.cachedOcr(fingerprint) }
        if (cached != null) {
            withContext(Dispatchers.IO) { store.saveOcr(id, AiContract.ocr(cached, d.images.size, d.kind)) }
            mutable.update { it.copy(message = "命中本地图片识别缓存，本次识别未调用 API") }
            return@task
        }
        if (withContext(Dispatchers.IO) { store.ocrFailures(id, fingerprint) } >= 2) throw AiFailure("图片识别", "CORRECTION_REQUIRED",
            "这组图片已两次返回不合格的识别结果，已阻止再次原样请求。",
            "根据之前的具体原因裁剪、旋转或拆分图片，或者使用手动粘贴正文；更改图片后才能重新识别。", d.error.take(700))
        mutable.update { it.copy(progress = if (d.kind == ImportKind.CLOZE) "DeepSeek 正在抄录 ${d.images.size} 页完形填空原题与选项…" else "DeepSeek 正在识别 ${d.images.size} 页正文…") }
        try {
            val response = client.recognize(d.images.map { File(it) }, d.error, d.kind)
            withContext(Dispatchers.IO) {
                val parsed = AiContract.ocr(response.json, d.images.size, d.kind)
                store.cacheOcr(fingerprint, response.json)
                store.saveOcr(id, parsed)
            }
        } catch (e: AiFailure) {
            if (!e.code.startsWith("HTTP_") && e.code != "CORRECTION_REQUIRED")
                withContext(Dispatchers.IO) { store.recordOcrFailure(id, fingerprint) }
            throw e
        }
        mutable.update { it.copy(message = if (d.kind == ImportKind.CLOZE) "原题已识别，请核对空格、题号和全部选项，再开始精读解析" else "全文已识别，请预览正文后开始逐词解析") }
    }
    fun saveText(id: String, title: String, text: String) = task(id) {
        withContext(Dispatchers.IO) { store.editText(id, title, text) }
        mutable.update { it.copy(message = "正文已保存") }
    }
    fun analyze(id: String, title: String, text: String) = task(id, "逐词解析") {
        val lastDiagnosis = withContext(Dispatchers.IO) { store.draft(id)?.error.orEmpty().take(1500) }
        val existing = withContext(Dispatchers.IO) {
            val old = store.draft(id) ?: error("草稿不存在")
            if (old.title != title.trim() || old.text != text) store.editText(id, title, text)
            LanguageCore.batches(text, old.tokenization)
            store.existingArticle(text)
        }
        require(existing == null) { "这篇文章已在书库中，无需重复解析，词频不会重复计入" }
        withContext(Dispatchers.IO) { store.setStage(id, "analysis") }
        val version = withContext(Dispatchers.IO) { store.draft(id)!!.tokenization }
        val batches = LanguageCore.batches(text, version)
        val localHits = AtomicInteger()
        val completed = AtomicInteger()
        BoundedAnalysis.run(batches) { batch ->
            coroutineContext.ensureActive()
            mutable.update { it.copy(progress = "逐词解析 ${batch.number + 1}/${batches.size} 批 · 本地复用 $localHits 批") }
            if (withContext(Dispatchers.IO) { store.batch(id, batch.number) } != null) { completed.incrementAndGet(); return@run }
            val cached = withContext(Dispatchers.IO) { store.cachedContext(batch) }
            if (cached != null) {
                withContext(Dispatchers.IO) { store.saveBatch(id, batch, cached) }
                localHits.incrementAndGet()
                completed.incrementAndGet()
                refresh(words = false, review = false)
                return@run
            }
            val saved = withContext(Dispatchers.IO) { store.fragment(id, batch) }
            val result = AnalysisRecovery.resolve(batch, saved,
                request = { target, translate -> coroutineContext.ensureActive(); client.analyze(target, translate,
                    saved?.optString("_diagnosis").orEmpty().ifBlank { lastDiagnosis }).json },
                checkpoint = { partial -> withContext(Dispatchers.IO) { store.saveFragment(id, batch, partial) } },
                progress = { done, total -> mutable.update { it.copy(progress = "逐词解析 ${batch.number + 1}/${batches.size} 批 · 已保留 $done/$total 词位 · 仅补缺项") } })
            withContext(Dispatchers.IO) { store.saveBatch(id, batch, result); store.cacheContext(batch, result) }
            completed.incrementAndGet()
            mutable.update { it.copy(progress = "逐词解析已完成 " + completed.get() + "/" + batches.size + " 批 · 最多同时处理 3 批") }
            refresh(words = false, review = false)
        }
        val lemmas = withContext(Dispatchers.IO) { batches.flatMap { b -> AiContract.analysis(store.batch(id, b.number)!!, b).words.map { it.lemma } }.distinct().sorted() }
        val missing = withContext(Dispatchers.IO) { store.missingDefinitions(lemmas) }
        mutable.update { it.copy(progress = "常见释义：本地复用 ${lemmas.size - missing.size} 词，补充 ${missing.size} 个新词…") }
        for ((number, list) in missing.chunked(40).withIndex()) {
            coroutineContext.ensureActive()
            mutable.update { it.copy(progress = "补充新词常见释义 ${number + 1}/${(missing.size + 39) / 40} 批 · 已有释义不再请求") }
            val response = client.dictionary(list, lastDiagnosis)
            withContext(Dispatchers.IO) { store.saveDefinitions(AiContract.dictionary(response.json, list)) }
            refresh(words = false, review = false)
        }
        mutable.update { it.copy(progress = "校验全文并保存本地词库…") }
        withContext(Dispatchers.IO) { store.publish(id); cleanImages(id, store.draft(id)!!.images) }
        mutable.update { it.copy(message = "文章已入库 · 本地复用 $localHits 批语境、${lemmas.size - missing.size} 词的常见释义") }
    }
    fun discard(id: String) = task(id) {
        withContext(Dispatchers.IO) {
            store.discard(id)
            val directory = File(getApplication<Application>().filesDir, "imports/$id")
            val root = File(getApplication<Application>().filesDir, "imports").canonicalFile
            require(directory.canonicalFile.parentFile == root)
            directory.deleteRecursively()
        }
    }
    fun openArticle(id: String) {
        val request = ++readerRequest
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    ReaderData(store.article(id) ?: error("找不到文章"), store.occurrences(articleId = id), store.translations(id), store.phrases(id), store.draft(id)?.images ?: emptyList())
                }
                if (request == readerRequest) mutable.update { it.copy(reader = data) }
            } catch (e: Exception) { if (request == readerRequest && e !is CancellationException) mutable.update { it.copy(message = e.message.orEmpty()) } }
        }
    }
    fun closeArticle() {
        readerRequest++
        mutable.update { it.copy(reader = null) }
        if (vocabularyRevision.get() != displayedVocabularyRevision.get()) viewModelScope.launch { refresh(library = false, review = false) }
    }
    fun deleteArticle(id: String) = localTask(library = true) {
        withContext(Dispatchers.IO) {
            val root = File(getApplication<Application>().filesDir, "imports").canonicalFile
            val directory = File(root, id).canonicalFile
            require(directory.parentFile == root)
            store.deleteArticle(id)
            directory.deleteRecursively()
        }
        mutable.update { it.copy(reader = it.reader?.takeUnless { reader -> reader.article.id == id },
            wordHistory = emptyList(), message = "文章已删除，词频与复习来源已更新") }
    }
    fun removeReview(lemma: String) = localTask {
        withContext(Dispatchers.IO) { store.removeReview(lemma) }
        mutable.update { it.copy(message = "$lemma 已移出复习队列，文章与词频保留") }
    }
    fun clearWordAnswer() { mutable.update { it.copy(wordAnswer = null) } }
    fun askWord(word: Occurrence, question: String) {
        if (mutable.value.busy) return
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        val identity = "${word.articleId}:${word.token.id}"
        mutable.update { it.copy(wordAnswer = AiExplanation(identity)) }
        task {
            selectionUsageOwner = word.articleId
            mutable.update { it.copy(progress = "正在结合原文解答…") }
            try {
                val article = withContext(Dispatchers.IO) { store.article(word.articleId) ?: error("文章已删除") }
                val key = LanguageCore.hash("suidu-word-qa-v1\u0000" + AiRequests.wordQuestion(article, word, trimmed))
                val saved = withContext(Dispatchers.IO) { store.selectedTranslation(key) }
                val answer = saved ?: run {
                    require(vault.configured()) { "请先在设置中填写 API Key" }
                    val json = client.askWord(article, word, trimmed).json
                    val value = json.get("answer").also { require(it is String) }.toString().trim()
                    require(value.isNotBlank() && value.length <= 8000) { "回答不完整，请重试" }
                    withContext(Dispatchers.IO) { store.saveSelectedTranslation(key, value) }
                    value
                }
                mutable.update { if (it.wordAnswer?.key == identity) it.copy(wordAnswer = AiExplanation(identity, false, answer, cached = saved != null)) else it }
            } catch (e: Exception) {
                mutable.update { if (it.wordAnswer?.key == identity) it.copy(wordAnswer = AiExplanation(identity, false, error =
                    if (e is CancellationException) "回答已暂停，可以重新提问" else e.message?.take(200) ?: "提问失败，请重试")) else it }
                throw e
            } finally { selectionUsageOwner = null }
        }
    }
    fun translateReview(card: ReviewCard) {
        if (mutable.value.busy) return
        val identity = LanguageCore.hash("${card.id}\u0000${card.context}")
        mutable.update { it.copy(reviewTranslation = AiExplanation(identity)) }
        task {
            selectionUsageOwner = card.articleId
            mutable.update { it.copy(progress = "正在准备复习句子的译文…") }
            try {
                val occurrence = withContext(Dispatchers.IO) { store.occurrences(articleId = card.articleId).firstOrNull { it.token.id == card.tokenId } }
                val selection = occurrence?.let { ReadingTools.reviewSelection(card, it) }
                    ?: TextSelection(card.articleId, 0, 0, card.context.length, card.context)
                val local = withContext(Dispatchers.IO) {
                    if (occurrence?.paragraphText?.trim() == card.context.trim()) store.translations(card.articleId)[occurrence.token.paragraph] else null
                }
                val saved = local ?: withContext(Dispatchers.IO) { store.selectedTranslation(selection.cacheKey) }
                val translation = saved ?: run {
                    require(vault.configured()) { "这句尚无译文，请在设置中填写 API Key 后重试" }
                    val json = client.translateSelection(selection).json
                    val value = json.get("translation").also { require(it is String) }.toString().trim()
                    require(value.isNotBlank() && value.length <= 8000) { "句子译文不完整，请重试" }
                    require(ClozeCore.keepsBlanks(selection.text, value)) { "译文没有保留原题空格，本次未保存，请重试" }
                    withContext(Dispatchers.IO) { store.saveSelectedTranslation(selection.cacheKey, value) }
                    value
                }
                mutable.update { it.copy(reviewTranslation = AiExplanation(identity, false, translation, cached = saved != null)) }
            } catch (e: Exception) {
                mutable.update { it.copy(reviewTranslation = AiExplanation(identity, false, error =
                    if (e is CancellationException) "翻译已暂停" else e.message?.take(200) ?: "翻译失败，请重试")) }
                throw e
            } finally { selectionUsageOwner = null }
        }
    }
    fun translateSelection(selection: TextSelection) {
        val reader = mutable.value.reader ?: return
        if (mutable.value.busy) { mutable.update { it.copy(message = "当前任务完成后再翻译") }; return }
        mutable.update { it.copy(selectionTranslation = SelectionTranslation(selection)) }
        task {
            selectionUsageOwner = selection.articleId
            fun result(text: String, source: String) { mutable.update { state ->
                if (state.selectionTranslation?.selection == selection) state.copy(selectionTranslation = SelectionTranslation(selection, false, text, source)) else state
            } }
            try {
                val local = withContext(Dispatchers.IO) { ReadingTools.localTranslation(selection, reader) }
                if (local != null) { result(local.first, local.second); return@task }
                val cached = withContext(Dispatchers.IO) { store.selectedTranslation(selection.cacheKey) }
                if (cached != null) { result(cached, "本地选段翻译缓存"); return@task }
                require(vault.configured()) { "这个选段尚未保存译文，请在设置中填写 API Key 后翻译" }
                mutable.update { it.copy(progress = "正在翻译选中的原文…") }
                val response = client.translateSelection(selection)
                val text = response.json.get("translation").also { require(it is String) }.toString().trim()
                require(text.isNotBlank() && text.length <= 8000) { "选段译文不完整，请重试" }
                require(ClozeCore.keepsBlanks(selection.text, text)) { "译文没有保留原题空格，本次未保存，请重试" }
                withContext(Dispatchers.IO) { store.saveSelectedTranslation(selection.cacheKey, text) }
                result(text, "DeepSeek 生成 · 已缓存到本机")
            } catch (e: Exception) {
                mutable.update { state -> if (state.selectionTranslation?.selection == selection)
                    state.copy(selectionTranslation = SelectionTranslation(selection, false, error = if (e is CancellationException) "翻译已暂停" else e.message?.take(200) ?: "翻译失败，可重试")) else state }
                throw e
            } finally { selectionUsageOwner = null }
        }
    }
    fun dismissSelection() {
        if (selectionUsageOwner != null) cancel()
        mutable.update { it.copy(selectionTranslation = null) }
    }
    fun lookup(o: Occurrence) {
        viewModelScope.launch { withContext(Dispatchers.IO) { store.click(o.lemma) }; vocabularyRevision.incrementAndGet() }
    }
    suspend fun reviewOccurrences(card: ReviewCard): List<Occurrence> = withContext(Dispatchers.IO) {
        store.occurrences(articleId = card.articleId)
    }
    fun enterManualText(id: String) = task(id) {
        withContext(Dispatchers.IO) {
            val draft = store.draft(id) ?: error("草稿不存在")
            require(draft.stage == "images")
            store.saveOcr(id, OcrResult(if (draft.kind == ImportKind.CLOZE) "手动录入完形填空" else "手动录入文章", "", listOf("请粘贴或录入原文。原图保留，可随时对照。")))
        }
    }
    fun wordHistory(lemma: String) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.occurrences(lemma = lemma) }
            mutable.update { it.copy(wordHistory = list) }
        }
    }
    fun clearHistory() { mutable.update { it.copy(wordHistory = emptyList()) } }
    fun rate(o: Occurrence, rating: Int) = localTask {
        withContext(Dispatchers.IO) { store.rate(o, rating) }
        mutable.update { it.copy(message = if (rating == 0) "已加入复习，10 分钟后再练" else "已记录，按间隔安排复习") }
    }
    fun rateCard(card: ReviewCard, rating: Int) = localTask { withContext(Dispatchers.IO) { store.rateCard(card, rating) } }
    fun export(uri: Uri) = task {
        withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { store.export(it) } ?: error("无法写入导出文件") }
        mutable.update { it.copy(message = "学习数据已导出（不含 API Key 和原图）") }
    }
    fun restore(uri: Uri) = task(stage = "恢复学习备份") {
        val result = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { store.restore(it) } ?: error("无法读取备份文件")
        }
        mutable.update { it.copy(message = "已恢复 " + result.articles + " 篇文章、" + result.drafts + " 份草稿、" + result.cards + " 张复习卡；保留已有记录") }
    }
    fun refreshNow() { viewModelScope.launch { refresh() } }
    override fun onCleared() {
        client.cancel()
        viewModelScope.coroutineContext[Job]?.invokeOnCompletion { store.close() }
        super.onCleared()
    }
}
