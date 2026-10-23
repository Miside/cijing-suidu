package com.yy.suidu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ImprovementTest {
    @Test fun relativeDatesUseCalendarDaysAcrossDaylightSavingAndYearBoundaries() {
        val zone = java.time.ZoneId.of("America/Los_Angeles")
        fun time(value: String) = java.time.ZonedDateTime.parse(value).toInstant().toEpochMilli()
        val now = time("2026-11-02T00:15:00-08:00[America/Los_Angeles]")
        assertEquals("今天", DisplayDates.article(now, now, zone))
        assertEquals("昨天", DisplayDates.article(time("2026-11-01T00:15:00-07:00[America/Los_Angeles]"), now, zone))
        assertEquals("10月30日", DisplayDates.article(time("2026-10-30T12:00:00-07:00[America/Los_Angeles]"), now, zone))
        assertEquals("2025年12月31日", DisplayDates.article(time("2025-12-31T23:00:00-08:00[America/Los_Angeles]"), now, zone))
    }
    @Test fun latinAccentsCombiningMarksAndOffsetsRemainExact() {
        val text = "😀 中文 café naïve re\u0301sume\u0301 co‑operate l’esprit."
        val tokens = LanguageCore.paragraphs(text).flatMap { it.tokens }
        assertEquals(listOf("café", "naïve", "re\u0301sume\u0301", "co‑operate", "l’esprit"), tokens.map { it.surface })
        tokens.forEach { assertEquals(it.surface, text.substring(it.start, it.end)) }
        assertEquals("résumé", LanguageCore.lemma("RE\u0301SUME\u0301"))
        assertEquals("l'esprit", LanguageCore.lemma("L’ESPRIT"))
        assertThrows(Exception::class.java) { LanguageCore.lemma("中文") }
        assertThrows(Exception::class.java) { LanguageCore.lemma("\u0301resume") }
    }
    @Test fun oldPublishedWordIdsAreAuthoritativeAfterTokenizerChange() {
        val text = "café résumé banks."
        val old = LanguageCore.paragraphs(text, 1).flatMap { it.tokens }
        assertNotEquals(old.size, LanguageCore.paragraphs(text).flatMap { it.tokens }.size)
        val reading = LanguageCore.readingParagraphs(text, old).flatMap { it.tokens }
        assertEquals(old, reading)
        assertEquals("banks", reading.last().surface)
        assertEquals(old.last().id, reading.last().id)
    }
    @Test fun malformedLemmaGetsSpecificDiagnosisRatherThanConflict() {
        val batch = LanguageCore.batches("Banks work.").first()
        val response = JSONObject("""{"w":[[0,"bad lemma","NOUN","银行"],[1,"work","VERB","工作"]],"t":"银行运作。","p":[]}""")
        val issues = AnalysisProgress(batch).merge(response, batch, true)
        assertTrue(issues.any { it.contains("lemma") && it.contains("词元格式") })
        assertFalse(issues.any { it.contains("互相冲突") })
    }
    @Test fun ocrWritesValidJsonAndIndependentBase64PaddingWithoutClosingTransport() {
        val root = File(System.getProperty("suidu.fixtureDir")).apply { mkdirs() }
        val files = (1..3).map { n -> File.createTempFile("stream-ocr-", ".jpg", root).apply { writeBytes(ByteArray(n * 8192 + n) { (it % 251).toByte() }) } }
        try {
            var closed = false
            val out = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
            var checks = 0
            StreamingOcrBody(files, "仅识别\n\"正文\"", "三张图片").write(out) { checks++ }
            assertFalse(closed)
            assertTrue(checks > files.size)
            val json = JSONObject(out.toString("UTF-8"))
            assertEquals("deepseek-flash", json.getString("model"))
            val content = json.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
            assertEquals(4, content.length())
            files.forEachIndexed { i, file ->
                val data = content.getJSONObject(i + 1).getJSONObject("image_url").getString("url").substringAfter("base64,")
                assertArrayEquals(file.readBytes(), java.util.Base64.getDecoder().decode(data))
            }
        } finally { files.forEach { it.delete() } }
    }
    @Test fun streamedOcrStopsOnCancellationBeforeReadingRemainingImages() {
        val file = File.createTempFile("cancel-ocr-", ".jpg", File(System.getProperty("suidu.fixtureDir"))).apply { writeBytes(ByteArray(100_000)) }
        try {
            var checks = 0
            val out = ByteArrayOutputStream()
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                StreamingOcrBody(listOf(file, file), "OCR", "图片").write(out) { if (++checks == 3) throw kotlinx.coroutines.CancellationException() }
            }
            assertTrue(out.size() < file.length())
        } finally { file.delete() }
    }
    @Test fun concurrencyIsBoundedAndEverySuccessfulBatchIsSaved() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val saved = mutableSetOf<Int>()
        BoundedAnalysis.run((0 until 11).toList()) { i ->
            peak.updateAndGet { maxOf(it, active.incrementAndGet()) }
            try { delay(10); saved += i } finally { active.decrementAndGet() }
        }
        assertEquals(3, peak.get())
        assertEquals((0 until 11).toSet(), saved)
        assertEquals(0, active.get())
    }
    @Test fun firstFailureCancelsSiblingsButKeepsCompletedCheckpoint() = runBlocking {
        val firstSaved = CompletableDeferred<Unit>()
        val failed = CompletableDeferred<Unit>()
        val saved = mutableSetOf<Int>()
        var startedAfterFailure = false
        try {
            BoundedAnalysis.run((0 until 20).toList()) { i ->
                if (failed.isCompleted) startedAfterFailure = true
                when (i) {
                    0 -> { saved += 0; firstSaved.complete(Unit) }
                    1 -> { firstSaved.await(); failed.complete(Unit); error("batch failed") }
                    else -> { delay(1000); saved += i }
                }
            }
            fail("Expected failure")
        } catch (e: IllegalStateException) { assertEquals("batch failed", e.message) }
        assertEquals(setOf(0), saved)
        assertFalse(startedAfterFailure)
    }
    @Test fun imageCleanupKeepsReferencesAndCannotLeaveImportRoot() {
        val root = File(System.getProperty("suidu.fixtureDir"), "media-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val draft = File(root, "draft").apply { mkdirs() }
        val keep = File(draft, "keep.jpg").apply { writeText("kept") }
        val orphan = File(draft, "old.jpg").apply { writeText("orphan") }
        try {
            assertEquals(1, ImportedImages.cleanUnused(root, "draft", listOf(keep.path)))
            assertTrue(keep.exists()); assertFalse(orphan.exists())
            assertThrows(Exception::class.java) { ImportedImages.cleanUnused(root, "../", emptyList()) }
            assertTrue(keep.exists())
        } finally { keep.delete(); orphan.delete(); draft.delete(); root.delete() }
    }
}
