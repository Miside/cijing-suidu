package com.yy.suidu

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class RequestLifecycleTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private class PendingConnection : HttpsURLConnection(URL("https://api.deepseek.com/models")) {
        val started = CountDownLatch(1)
        val disconnected = AtomicBoolean(false)
        private val released = CountDownLatch(1)
        override fun getResponseCode(): Int {
            started.countDown()
            check(released.await(10, TimeUnit.SECONDS)) { "Test connection timed out" }
            throw IOException("Connection disconnected")
        }
        override fun disconnect() { disconnected.set(true); released.countDown() }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "TEST"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (!condition() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
        assertTrue("Operation did not finish", condition())
    }
    @Test fun repeatedLookupsAccumulateWithoutReloadingLibraryThenRefreshOnReaderExit() {
        app.deleteDatabase("suidu-local.db")
        val store = LocalStore(app)
        val holder = androidx.lifecycle.ViewModelStore()
        lateinit var model: MobileModel
        try {
            val id = store.createDraft(emptyList())
            store.saveOcr(id, OcrResult("查词刷新测试", "Banks work.", emptyList()))
            store.saveBatch(id, LanguageCore.batches("Banks work.").first(), JSONObject("""{"w":[[0,"bank","NOUN","银行"],[1,"work","VERB","运作"]],"t":"银行运作。","p":[]}"""))
            store.saveDefinitions(mapOf("bank" to listOf("n. 银行"), "work" to listOf("v. 运作")))
            store.publish(id)
            val word = store.occurrences(articleId = id).first()
            instrumentation.runOnMainSync { model = MobileModel(app); holder.put("model", model) }
            await { model.state.value.loaded }
            val library = model.state.value.articles
            instrumentation.runOnMainSync { model.openArticle(id) }
            await { model.state.value.reader != null }
            instrumentation.runOnMainSync { repeat(30) { model.lookup(word) } }
            await { store.vocabulary().first { it.lemma == "bank" }.clicks == 30 }
            assertSame("Point lookup must not reload the library", library, model.state.value.articles)
            assertEquals(0, model.state.value.vocabulary.first { it.lemma == "bank" }.clicks)
            instrumentation.runOnMainSync { model.closeArticle() }
            await { model.state.value.vocabulary.first { it.lemma == "bank" }.clicks == 30 }
            assertEquals(2, store.vocabulary().sumOf { it.count })
        } finally {
            instrumentation.runOnMainSync { holder.clear() }
            store.close(); app.deleteDatabase("suidu-local.db")
        }
    }
    @Test fun cancellingConcurrentRequestsDisconnectsEveryOwnedConnection() = runBlocking {
        val vault = KeyVault(app)
        vault.save("sk-fixture-not-a-real-key")
        val connections = java.util.concurrent.CopyOnWriteArrayList<PendingConnection>()
        val client = DeepSeekClient(vault, connect = {
            assertTrue(it.startsWith("https://api.deepseek.com/"))
            PendingConnection().also { connection -> connections += connection }
        })
        val job = launch(Dispatchers.Default) { BoundedAnalysis.run(listOf(1, 2, 3)) { client.check() } }
        try {
            await { connections.size == 3 && connections.all { it.started.count == 0L } }
            job.cancel()
            withTimeout(4000) { job.join() }
            assertEquals(3, connections.size)
            assertTrue(connections.all { it.disconnected.get() })
        } finally { client.cancel(); job.cancelAndJoin(); vault.clear() }
    }
    @Test fun reviewRatingCompletesWhileAnAiRequestIsWaitingAndSurvivesCancellation() {
        app.deleteDatabase("suidu-local.db")
        val store = LocalStore(app)
        val vault = KeyVault(app)
        val connection = PendingConnection()
        val holder = androidx.lifecycle.ViewModelStore()
        lateinit var model: MobileModel
        try {
            val id = store.createDraft(emptyList())
            store.saveOcr(id, OcrResult("并行任务测试", "Banks work.", emptyList()))
            store.saveBatch(id, LanguageCore.batches("Banks work.").first(), JSONObject("""{"w":[[0,"bank","NOUN","银行"],[1,"work","VERB","运作"]],"t":"银行运作。","p":[]}"""))
            store.saveDefinitions(mapOf("bank" to listOf("n. 银行"), "work" to listOf("v. 运作")))
            store.publish(id); store.rate(store.occurrences(articleId = id).first(), 0, 0)
            val card = store.due(600000).single()
            vault.save("sk-fixture-not-a-real-key")
            instrumentation.runOnMainSync {
                model = MobileModel(app) { key, usage -> DeepSeekClient(key, usage) { connection } }
                holder.put("model", model)
            }
            await { model.state.value.loaded }
            instrumentation.runOnMainSync { model.checkConnection() }
            assertTrue(connection.started.await(3, TimeUnit.SECONDS))
            assertTrue(model.state.value.busy)
            instrumentation.runOnMainSync { model.rateCard(card, 2) }
            await { store.card(card.id)!!.reviews == 1 && !model.state.value.localBusy }
            assertTrue("The AI request must still be waiting during local rating", model.state.value.busy)
            assertEquals(1, store.card(card.id)!!.repetitions)
            instrumentation.runOnMainSync { model.cancel() }
            await { !model.state.value.busy }
            assertTrue(connection.disconnected.get())
            assertEquals(1, store.card(card.id)!!.reviews)
        } finally {
            connection.disconnect()
            instrumentation.runOnMainSync { holder.clear() }
            vault.clear(); store.close(); app.deleteDatabase("suidu-local.db")
        }
    }
}
