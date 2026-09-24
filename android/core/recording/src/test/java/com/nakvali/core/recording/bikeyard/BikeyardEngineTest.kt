package com.nakvali.core.recording.bikeyard

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class BikeyardEngineTest {
    private val clock = 1_000_000L
    private val file = File("unused-by-fake.tcx")
    private class MemoryStore(var value: BikeyardStoredState) : BikeyardStore {
        override fun read() = value
        override fun write(state: BikeyardStoredState) { value = state }
    }
    private class Remote : BikeyardRemote {
        var exchanges = 0
        var refreshes = 0
        var uploads = 0
        var metrics = 0
        var metricsFailure: BikeyardFailure? = null
        var uploadGate: CompletableDeferred<Unit>? = null
        val uploadEntered = CompletableDeferred<Unit>()
        var failRefresh = false
        var failUpload = false
        var receipt = BikeyardReceipt("upload", "complete", rideId = "ride")
        val submitted = mutableListOf<BikeyardUpload>()
        private fun response() = BikeyardTokenResponse("access-new", "refresh-new", 21600, "Bearer", "rider", BikeyardPkce.SCOPES)
        override suspend fun exchange(environment: BikeyardEnvironment, code: String, verifier: String): BikeyardTokenResponse { exchanges++; return response() }
        override suspend fun refresh(environment: BikeyardEnvironment, token: String): BikeyardTokenResponse {
            refreshes++
            if (failRefresh) throw BikeyardFailure(BikeyardFailure.Kind.RETRY, "Ambiguous network failure")
            return response()
        }
        override suspend fun rider(environment: BikeyardEnvironment, token: String) = BikeyardRider("rider", "rider", "Test", "Rider")
        override suspend fun revoke(environment: BikeyardEnvironment, token: String) = Unit
        override suspend fun upload(environment: BikeyardEnvironment, token: String, job: BikeyardUpload, file: File): BikeyardReceipt {
            uploads++; submitted += job
            uploadEntered.complete(Unit)
            uploadGate?.await()
            if (failUpload) throw BikeyardFailure(BikeyardFailure.Kind.RETRY, "Rate limited", 2_000_000)
            return receipt
        }
        override suspend fun receipt(environment: BikeyardEnvironment, token: String, id: String) = receipt
        override suspend fun putMetrics(environment: BikeyardEnvironment, token: String, rideId: String,
            file: File): BikeyardMetricsReceipt {
            metrics++
            metricsFailure?.let { throw it }
            return BikeyardMetricsReceipt(rideId, metrics)
        }
    }
    private fun connected(expired: Boolean = false) = BikeyardStoredState(tokens = BikeyardTokens(
        "access", "refresh", if (expired) 0 else clock + 1000000, "rider", "Test Rider",
    ))
    private fun engine(store: MemoryStore, remote: Remote) = BikeyardEngine(store, remote) { clock }
    private suspend fun queue(engine: BikeyardEngine, id: String = "recording") = engine.enqueue(id, "Ride", "", "mtb")!!

    @Test fun `PKCE challenge matches RFC 7636 vector`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", BikeyardPkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun `authorization requests write scope and distinct cryptographic values`() = runBlocking {
        val store = MemoryStore(BikeyardStoredState()); val core = engine(store, Remote())
        val url = core.beginConnect().toHttpUrl(); val first = store.value.pending!!
        assertEquals(BikeyardPkce.SCOPES, url.queryParameter("scope"))
        assertEquals(BikeyardEnvironment.LIVE.clientId, url.queryParameter("client_id"))
        assertEquals(BikeyardPkce.challenge(first.verifier), url.queryParameter("code_challenge"))
        assertNotEquals(first.verifier, first.state)
        core.beginConnect(); assertNotEquals(first.state, store.value.pending!!.state)
    }

    @Test fun `wrong state or callback origin cannot exchange or consume the pending code`() = runBlocking {
        val store = MemoryStore(BikeyardStoredState()); val remote = Remote(); val core = engine(store, remote)
        core.beginConnect(); val state = store.value.pending!!.state
        core.finishConnect("${BikeyardPkce.REDIRECT_URI}?state=wrong&code=x")
        core.finishConnect("https://evil.example/oauth/bikeyard/callback?state=$state&code=x")
        core.finishConnect("${BikeyardPkce.REDIRECT_URI}?state=$state&state=$state&code=x")
        assertEquals(0, remote.exchanges); assertNotNull(store.value.pending)
    }

    @Test fun `valid response is consumed exactly once and persists granted account`() = runBlocking {
        val store = MemoryStore(BikeyardStoredState()); val remote = Remote(); val core = engine(store, remote)
        core.beginConnect(); val url = "${BikeyardPkce.REDIRECT_URI}?state=${store.value.pending!!.state}&code=one-use"
        core.finishConnect(url); core.finishConnect(url)
        assertEquals(1, remote.exchanges); assertNull(store.value.pending)
        assertEquals("Test Rider", core.state.value.riderName); assertTrue(core.state.value.connected)
        assertFalse(core.state.value.automatic)
    }

    @Test fun `denial clears pending without granting a connection`() = runBlocking {
        val store = MemoryStore(BikeyardStoredState()); val remote = Remote(); val core = engine(store, remote)
        core.beginConnect(); core.finishConnect("${BikeyardPkce.REDIRECT_URI}?state=${store.value.pending!!.state}&error=access_denied")
        assertEquals(0, remote.exchanges); assertNull(store.value.pending); assertFalse(core.state.value.connected)
    }

    @Test fun `parallel uploads rotate the refresh token once`() = runBlocking {
        val store = MemoryStore(connected(expired = true)); val remote = Remote(); val core = engine(store, remote)
        val jobs = listOf(queue(core, "one"), queue(core, "two"))
        jobs.map { async { core.process(it.key, file) } }.awaitAll()
        assertEquals(1, remote.refreshes); assertEquals("refresh-new", store.value.tokens!!.refresh)
        assertFalse(store.value.tokens!!.refreshInFlight)
        assertEquals(2, remote.uploads)
    }

    @Test fun `ambiguous refresh failure never reuses a single use refresh token`() = runBlocking {
        val store = MemoryStore(connected(expired = true)); val remote = Remote().apply { failRefresh = true }; val core = engine(store, remote)
        val job = queue(core); core.process(job.key, file); core.process(job.key, file)
        assertEquals(1, remote.refreshes); assertNull(store.value.tokens); assertEquals(0, remote.uploads)
        assertEquals(BikeyardUploadStatus.NEEDS_AUTH, core.state.value.uploads.single().status)
    }

    @Test fun `process death during refresh requires reconnect`() {
        val initial = connected().let { it.copy(tokens = it.tokens!!.copy(refreshInFlight = true), autoConsentId = "old") }
        val core = engine(MemoryStore(initial), Remote())
        assertFalse(core.state.value.connected); assertFalse(core.state.value.automatic)
    }

    @Test fun `duplicate belonging to another rider is not reported uploaded`() = runBlocking {
        val remote = Remote().apply { receipt = BikeyardReceipt("upload", "duplicate") }
        val core = engine(MemoryStore(connected()), remote); val job = queue(core)
        core.process(job.key, file)
        assertEquals(BikeyardUploadStatus.DUPLICATE, core.state.value.uploads.single().status)
        assertNull(core.state.value.uploads.single().rideId)
    }

    @Test fun `own duplicate records the confirmed ride id`() = runBlocking {
        val remote = Remote().apply { receipt = BikeyardReceipt("upload", "duplicate", duplicateOf = "existing") }
        val core = engine(MemoryStore(connected()), remote); val job = queue(core)
        core.process(job.key, file)
        assertEquals(BikeyardUploadStatus.UPLOADED, core.state.value.uploads.single().status)
        assertEquals("existing", core.state.value.uploads.single().rideId)
    }

    @Test fun `sensor metrics wait for a private confirmed ride and leave track status intact`() = runBlocking {
        val remote = Remote(); val core = engine(MemoryStore(connected()), remote); val job = queue(core)
        assertFalse(core.queueMetrics(job.key, manual = true))
        core.prepared(job.key, listOf(BikeyardSensorScope(1_000, 2_000)))
        core.process(job.key, file)
        assertTrue(core.queueMetrics(job.key, manual = true))
        assertTrue(core.processMetrics(job.key, file))
        val uploaded = core.state.value.uploads.single()
        assertEquals(BikeyardUploadStatus.UPLOADED, uploaded.status)
        assertEquals(BikeyardMetricsStatus.UPLOADED, uploaded.metricsStatus)
        assertEquals(1, uploaded.metricsRevision)
        assertEquals(1, remote.metrics)
    }

    @Test fun `ambiguous metrics response retries the frozen document`() = runBlocking {
        val remote = Remote(); val core = engine(MemoryStore(connected()), remote); val job = queue(core)
        core.prepared(job.key, listOf(BikeyardSensorScope(1_000, 2_000)))
        core.process(job.key, file); core.queueMetrics(job.key, manual = true)
        remote.metricsFailure = BikeyardFailure(BikeyardFailure.Kind.RETRY, "Response lost")
        assertFalse(core.processMetrics(job.key, file))
        assertEquals(BikeyardMetricsStatus.QUEUED, core.state.value.uploads.single().metricsStatus)
        remote.metricsFailure = null
        assertTrue(core.processMetrics(job.key, file))
        assertEquals(BikeyardMetricsStatus.UPLOADED, core.state.value.uploads.single().metricsStatus)
        assertEquals(BikeyardUploadStatus.UPLOADED, core.state.value.uploads.single().status)
        assertEquals(2, remote.metrics)
    }

    @Test fun `queued metrics survive engine restart without repeating the track upload`() = runBlocking {
        val store = MemoryStore(connected()); val remote = Remote()
        val first = engine(store, remote); val job = queue(first)
        first.prepared(job.key, listOf(BikeyardSensorScope(1_000, 2_000)))
        first.process(job.key, file)
        first.queueMetrics(job.key, manual = true)

        val restarted = engine(store, remote)
        assertTrue(restarted.processMetrics(job.key, file))
        assertEquals(1, remote.uploads)
        assertEquals(1, remote.metrics)
        assertEquals(BikeyardMetricsStatus.UPLOADED, store.value.uploads.single().metricsStatus)
    }

    @Test fun `public ride cannot queue experimental sensor metrics`() = runBlocking {
        val remote = Remote(); val core = engine(MemoryStore(connected()), remote)
        core.settings(false, BikeyardVisibility.PUBLIC)
        val job = queue(core)
        core.prepared(job.key, listOf(BikeyardSensorScope(1_000, 2_000)))
        core.process(job.key, file)
        assertFalse(core.queueMetrics(job.key, manual = true))
        assertEquals(0, remote.metrics)
    }

    @Test fun `queued visibility and metadata do not change with new defaults or retry`() = runBlocking {
        val core = engine(MemoryStore(connected()), Remote()); val first = queue(core)
        core.settings(false, BikeyardVisibility.PUBLIC)
        val retry = core.enqueue("recording", "Edited name", "Edited description", "emtb")!!
        assertEquals(first.key, retry.key); assertEquals(first.externalId, retry.externalId)
        assertEquals(BikeyardVisibility.PRIVATE, retry.visibility); assertEquals("Ride", retry.name)
    }

    @Test fun `turning auto sync off invalidates save time consent and cancels queued automatic work`() = runBlocking {
        val core = engine(MemoryStore(connected()), Remote())
        core.settings(true, BikeyardVisibility.PRIVATE); val consent = core.automaticRequest()!!
        core.enqueue("one", "Ride", "", "mtb", consent)
        core.settings(false, BikeyardVisibility.PRIVATE)
        core.settings(true, BikeyardVisibility.PRIVATE)
        assertNull(core.enqueue("two", "Ride", "", "mtb", consent))
        assertEquals(BikeyardUploadStatus.CANCELLED, core.state.value.uploads.single().status)
    }

    @Test fun `automatic airtime consent is separate and frozen with a private new ride`() = runBlocking {
        val remote = Remote(); val core = engine(MemoryStore(connected()), remote)
        core.settings(true, BikeyardVisibility.PRIVATE)
        assertFalse(core.automaticRequest()!!.sensorMetrics)
        core.metricsSettings(true, BikeyardMounting.POCKET)
        val consent = core.automaticRequest()!!
        assertTrue(consent.sensorMetrics)
        val job = core.enqueue("new-ride", "Ride", "", "mtb", consent)!!
        assertTrue(job.metricsAutomatic)
        assertEquals(BikeyardMounting.POCKET, job.metricsMounting)
        core.prepared(job.key, listOf(BikeyardSensorScope(1_000, 2_000)))
        core.process(job.key, file)
        assertTrue(core.queueMetrics(job.key))
        core.metricsSettings(false, BikeyardMounting.POCKET)
        assertEquals(BikeyardMetricsStatus.CANCELLED, core.state.value.uploads.single().metricsStatus)
        assertTrue(core.processMetrics(job.key, file))
        assertEquals(0, remote.metrics)
    }

    @Test fun `public visibility disables automatic sensor submission`() = runBlocking {
        val core = engine(MemoryStore(connected()), Remote())
        core.settings(true, BikeyardVisibility.PRIVATE)
        core.metricsSettings(true, BikeyardMounting.POCKET)
        core.settings(true, BikeyardVisibility.PUBLIC)
        assertFalse(core.state.value.automaticMetrics)
        assertFalse(core.automaticRequest()!!.sensorMetrics)
    }

    @Test fun `different account cannot consume an existing queue`() = runBlocking {
        val store = MemoryStore(connected()); val remote = Remote(); val first = engine(store, remote); val job = queue(first)
        store.value = store.value.copy(tokens = store.value.tokens!!.copy(riderId = "different"))
        val second = engine(store, remote); second.process(job.key, file)
        assertEquals(0, remote.uploads); assertEquals(BikeyardUploadStatus.NEEDS_AUTH, second.state.value.uploads.single().status)
    }

    @Test fun `rate limit deadline suppresses another network request`() = runBlocking {
        val remote = Remote().apply { failUpload = true }; val core = engine(MemoryStore(connected()), remote); val job = queue(core)
        assertFalse(core.process(job.key, file)); assertFalse(core.process(job.key, file))
        assertEquals(1, remote.uploads)
    }

    @Test fun `processing receipt is polled rather than uploaded again`() = runBlocking {
        var time = clock
        val remote = Remote().apply { receipt = BikeyardReceipt("upload", "processing") }
        val core = BikeyardEngine(MemoryStore(connected()), remote) { time }; val job = queue(core)
        assertFalse(core.process(job.key, file)); time += 31000
        remote.receipt = BikeyardReceipt("upload", "complete", rideId = "ride")
        assertTrue(core.process(job.key, file)); assertEquals(1, remote.uploads)
    }

    @Test fun `disconnect forgets credentials profile consent and ledger`() = runBlocking {
        val store = MemoryStore(connected()); val core = engine(store, Remote()); queue(core)
        core.settings(true, BikeyardVisibility.PUBLIC); core.disconnect()
        assertNull(store.value.tokens); assertNull(store.value.autoConsentId); assertTrue(store.value.uploads.isEmpty())
        assertEquals(BikeyardVisibility.PRIVATE, core.state.value.visibility)
    }
    @Test fun `automatic work stays stopped after reconnect until manually retried`() = runBlocking {
        val store = MemoryStore(connected()); val remote = Remote(); val core = engine(store, remote)
        core.settings(true, BikeyardVisibility.PRIVATE)
        val job = core.enqueue("auto", "Ride", "", "mtb", core.automaticRequest())!!
        store.value = store.value.copy(autoConsentId = null)
        val restarted = engine(store, remote)
        restarted.process(job.key, file)
        assertEquals(0, remote.uploads)
        assertEquals(BikeyardUploadStatus.CANCELLED, restarted.state.value.uploads.single().status)
        val retried = restarted.enqueue("auto", "Ride", "", "mtb")!!
        assertFalse(retried.automatic)
        restarted.process(job.key, file)
        assertEquals(1, remote.uploads)
    }

    @Test fun `local save consent does not wait for an in flight network upload`() = runBlocking {
        val remote = Remote().apply { uploadGate = CompletableDeferred() }
        val core = engine(MemoryStore(connected()), remote)
        core.settings(true, BikeyardVisibility.PRIVATE)
        val job = queue(core)
        val upload = async { core.process(job.key, file) }
        remote.uploadEntered.await()
        assertNotNull(withTimeout(100) { core.automaticRequest() })
        remote.uploadGate!!.complete(Unit)
        assertTrue(upload.await())
    }

    @Test fun `sandbox state is retired without converting its tokens or queue to live`() {
        val legacy = connected().copy(
            environment = BikeyardEnvironment.SANDBOX,
            pending = BikeyardPending("old-state", "old-verifier", clock),
            autoConsentId = "old-consent",
            uploads = listOf(BikeyardUpload("old-key", "recording", "SANDBOX:rider",
                BikeyardVisibility.PUBLIC, true, externalId = "old", name = "Test", description = "", bikeType = "mtb")),
        )
        val store = MemoryStore(legacy)
        val core = engine(store, Remote())
        assertEquals(BikeyardEnvironment.LIVE, store.value.environment)
        assertNull(store.value.tokens); assertNull(store.value.pending)
        assertNull(store.value.autoConsentId); assertTrue(store.value.uploads.isEmpty())
        assertFalse(core.state.value.connected)
        assertEquals(BikeyardVisibility.PRIVATE, core.state.value.visibility)
    }

    @Test fun `existing live session and opt in survive the production migration`() {
        val original = connected().copy(autoConsentId = "consent", visibility = BikeyardVisibility.PUBLIC)
        val store = MemoryStore(original)
        val core = engine(store, Remote())
        assertEquals(original, store.value)
        assertTrue(core.state.value.connected); assertTrue(core.state.value.automatic)
        assertEquals("Test Rider", core.state.value.riderName)
    }

    @Test fun `async receipt survives process restart without another upload`() = runBlocking {
        var time = clock
        val remote = Remote().apply { receipt = BikeyardReceipt("upload", "processing") }
        val store = MemoryStore(connected())
        val first = BikeyardEngine(store, remote) { time }
        val job = queue(first)
        assertFalse(first.process(job.key, file))
        assertEquals("upload", store.value.uploads.single().uploadId)
        time += 31000
        remote.receipt = BikeyardReceipt("upload", "complete", rideId = "finished")
        val restarted = BikeyardEngine(store, remote) { time }
        assertTrue(restarted.process(job.key, File("missing-local-snapshot")))
        assertEquals(1, remote.uploads)
        assertEquals("finished", restarted.state.value.uploads.single().rideId)
    }

}
