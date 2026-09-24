package com.nakvali.core.recording.bikeyard

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Serialized OAuth/session transitions and a durable, account-bound upload ledger. */
internal class BikeyardEngine(
    private val store: BikeyardStore,
    private val remote: BikeyardRemote,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val restored = store.read()
    val retiredSandbox = restored.environment == BikeyardEnvironment.SANDBOX
    @Volatile private var data = restored.let {
        when {
            it.environment != BikeyardEnvironment.LIVE -> BikeyardStoredState(
                message = "Connect your BIKEYARD account. The test connection has been removed",
            )
            it.tokens?.refreshInFlight == true -> it.copy(tokens = null, autoConsentId = null,
                message = "Token refresh was interrupted. Connect BIKEYARD again")
            else -> it
        }
    }
    init {
        // Persist retirement before any queued worker can make a network request.
        // Existing live tokens, receipts and consent are retained unchanged.
        if (data != restored) store.write(data)
    }
    private val _state = MutableStateFlow(data.ui())
    val state = _state.asStateFlow()

    private fun save(next: BikeyardStoredState) {
        store.write(next)
        data = next
        _state.value = next.ui()
    }

    suspend fun beginConnect(): String = mutex.withLock {
        check(data.tokens == null) { "Disconnect the current BIKEYARD account first" }
        val pending = BikeyardPending(BikeyardPkce.random(), BikeyardPkce.random(), now())
        save(data.copy(pending = pending, message = null))
        BikeyardApi.authorizeUrl(data.environment, pending)
    }

    suspend fun cancelConnect() = mutex.withLock { save(data.copy(pending = null, message = null)) }

    suspend fun finishConnect(callback: String) = mutex.withLock {
        val url = callback.toHttpUrlOrNull() ?: return@withLock
        val expected = BikeyardPkce.REDIRECT_URI.toHttpUrlOrNull()!!
        if (url.scheme != expected.scheme || url.host != expected.host || url.port != expected.port ||
            url.encodedPath != expected.encodedPath || url.fragment != null || url.username.isNotEmpty() || url.password.isNotEmpty()
        ) return@withLock
        val pending = data.pending ?: return@withLock
        val states = url.queryParameterValues("state")
        if (states.size != 1 || !MessageDigest.isEqual(states.single().orEmpty().toByteArray(), pending.state.toByteArray())) {
            save(data.copy(message = "This connection response is invalid. Try connecting again"))
            return@withLock
        }
        // Consume before network IO: never replay a possibly redeemed code.
        save(data.copy(pending = null, message = null))
        if (now() - pending.createdAtMs !in 0..600000) {
            save(data.copy(message = "Connection expired. Try again")); return@withLock
        }
        if (url.queryParameter("error") != null) {
            save(data.copy(message = "BIKEYARD connection was not approved")); return@withLock
        }
        val codes = url.queryParameterValues("code")
        if (codes.size != 1 || codes.single().isNullOrBlank()) {
            save(data.copy(message = "BIKEYARD did not return a connection code")); return@withLock
        }
        _state.value = data.ui().copy(connecting = true)
        try {
            val tokens = remote.exchange(data.environment, codes.single()!!, pending.verifier).tokens(now())
            // Persist the rotating token before an optional profile request can fail.
            save(data.copy(tokens = tokens, autoConsentId = null))
            val rider = remote.rider(data.environment, tokens.access)
            if (rider.id != tokens.riderId) throw BikeyardFailure(BikeyardFailure.Kind.AUTH, "BIKEYARD account mismatch. Reconnect")
            save(data.copy(tokens = tokens.copy(riderName = rider.name)))
        } catch (error: CancellationException) { throw error }
        catch (error: BikeyardFailure) {
            if (error.kind == BikeyardFailure.Kind.AUTH) save(data.copy(tokens = null, autoConsentId = null, message = error.message))
            else save(data.copy(message = if (data.tokens != null) "Connected. Rider name is temporarily unavailable" else "Could not connect. Please try again"))
        } finally {
            _state.value = data.ui()
        }
    }

    suspend fun settings(automatic: Boolean, visibility: BikeyardVisibility) = mutex.withLock {
        check(!automatic || data.tokens != null) { "Connect BIKEYARD first" }
        val consent = if (automatic) data.autoConsentId ?: BikeyardPkce.random() else null
        val keepMetrics = automatic && visibility == BikeyardVisibility.PRIVATE && data.autoMetrics
        save(data.copy(autoConsentId = consent, autoMetrics = keepMetrics,
            visibility = visibility, message = null,
            uploads = data.uploads.map { job ->
                val stopTrack = !automatic && job.automatic && job.status in activeStatuses
                val stopMetrics = !keepMetrics && job.metricsAutomatic &&
                    job.metricsStatus in activeMetricsStatuses
                job.copy(
                    status = if (stopTrack) BikeyardUploadStatus.CANCELLED else job.status,
                    error = if (stopTrack) "Automatic uploads turned off" else job.error,
                    metricsStatus = if (stopMetrics) BikeyardMetricsStatus.CANCELLED else job.metricsStatus,
                    metricsError = if (stopMetrics) "Automatic airtime sync turned off" else job.metricsError,
                )
            }))
    }

    suspend fun metricsSettings(enabled: Boolean, mounting: BikeyardMounting) = mutex.withLock {
        check(!enabled || (data.tokens != null && data.autoConsentId != null &&
            data.visibility == BikeyardVisibility.PRIVATE)) {
            "Enable private automatic ride uploads first"
        }
        save(data.copy(autoMetrics = enabled, metricsMounting = mounting,
            uploads = data.uploads.map { job ->
                if (!enabled && job.metricsAutomatic && job.metricsStatus in activeMetricsStatuses) {
                    job.copy(metricsStatus = BikeyardMetricsStatus.CANCELLED,
                        metricsError = "Automatic airtime sync turned off")
                } else job
            }))
    }

    // Recording must never wait for the network mutex held by an upload/refresh.
    // An immutable, atomically published snapshot captures consent at save time.
    fun automaticRequest(): BikeyardAutoRequest? {
        val snapshot = data
        val account = snapshot.accountKey ?: return null
        val consent = snapshot.autoConsentId ?: return null
        return BikeyardAutoRequest(account, consent, snapshot.visibility,
            snapshot.autoMetrics && snapshot.visibility == BikeyardVisibility.PRIVATE,
            snapshot.metricsMounting)
    }

    suspend fun enqueue(recordingId: String, title: String, description: String, bikeType: String,
        automatic: BikeyardAutoRequest? = null): BikeyardUpload? = mutex.withLock {
        val account = data.accountKey ?: return@withLock null
        if (automatic != null && (automatic.accountKey != account || automatic.consentId != data.autoConsentId)) return@withLock null
        val existing = data.uploads.firstOrNull { it.recordingId == recordingId && it.accountKey == account }
        if (existing != null) {
            if (automatic != null || existing.status == BikeyardUploadStatus.UPLOADED || existing.status == BikeyardUploadStatus.DUPLICATE) return@withLock existing
            val retry = existing.copy(status = BikeyardUploadStatus.QUEUED, automatic = false, error = null)
            update(retry); return@withLock retry
        }
        val job = BikeyardUpload(
            key = BikeyardPkce.random(), recordingId = recordingId, accountKey = account,
            visibility = automatic?.visibility ?: data.visibility, automatic = automatic != null,
            metricsAutomatic = automatic?.sensorMetrics == true,
            metricsMounting = automatic?.mounting ?: BikeyardMounting.UNKNOWN,
            externalId = "nakvali-$recordingId", name = title.take(120), description = description.take(2000), bikeType = bikeType,
        )
        require(job.externalId.length <= 128 && job.externalId.all { it.code in 33..126 })
        save(data.copy(uploads = data.uploads + job, message = null))
        job
    }

    suspend fun prepared(key: String, sensorScopes: List<BikeyardSensorScope> = emptyList()): Boolean = mutex.withLock {
        val job = data.uploads.firstOrNull { it.key == key && it.status in activeStatuses } ?: return@withLock false
        update(job.copy(prepared = true, sensorScopes = sensorScopes)); true
    }

    suspend fun fail(key: String, message: String) = mutex.withLock {
        data.uploads.firstOrNull { it.key == key && it.status in activeStatuses }?.let {
            update(it.copy(status = BikeyardUploadStatus.FAILED, error = message))
        }
    }

    suspend fun cancelRecording(recordingId: String) = mutex.withLock {
        save(data.copy(uploads = data.uploads.filterNot { it.recordingId == recordingId }))
    }

    suspend fun disconnect() = mutex.withLock {
        val previous = data
        // Clear locally even offline; a failed revocation is visible, never disguised.
        save(BikeyardStoredState(environment = previous.environment))
        previous.tokens?.let { tokens ->
            try { remote.revoke(previous.environment, tokens.refresh) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                save(data.copy(message = "Disconnected on this phone. Revoke Nakvali in BIKEYARD’s connected apps to finish disconnecting"))
            }
        }
    }

    private suspend fun token(): String {
        val current = data.tokens ?: throw BikeyardFailure(BikeyardFailure.Kind.AUTH, "Connect BIKEYARD again")
        if (current.expiresAtMs > now() + 60000) return current.access
        save(data.copy(tokens = current.copy(refreshInFlight = true)))
        try {
            val next = remote.refresh(data.environment, current.refresh).tokens(now())
            if (next.riderId != current.riderId) throw BikeyardFailure(BikeyardFailure.Kind.AUTH, "BIKEYARD account changed. Reconnect")
            save(data.copy(tokens = next.copy(riderName = current.riderName)))
            return next.access
        } catch (error: Exception) {
            // An ambiguous response may already have spent the refresh token.
            save(data.copy(tokens = null, autoConsentId = null, message = "Token refresh failed. Connect BIKEYARD again"))
            if (error is CancellationException) throw error
            throw BikeyardFailure(BikeyardFailure.Kind.AUTH, "Connect BIKEYARD again")
        }
    }

    suspend fun process(key: String, file: File): Boolean = mutex.withLock {
        var job = data.uploads.firstOrNull { it.key == key } ?: return@withLock true
        if (job.status !in activeStatuses) return@withLock true
        if (job.automatic && data.autoConsentId == null) {
            update(job.copy(status = BikeyardUploadStatus.CANCELLED, error = "Automatic uploads are off. Retry manually when ready"))
            return@withLock true
        }
        if (job.retryAtMs > now()) return@withLock false
        if (job.accountKey != data.accountKey) {
            update(job.copy(status = BikeyardUploadStatus.NEEDS_AUTH, error = "Reconnect the original BIKEYARD account, then retry"))
            return@withLock true
        }
        job = job.copy(status = BikeyardUploadStatus.UPLOADING, error = null)
        update(job)
        try {
            val access = token()
            val receipt = job.uploadId?.let { remote.receipt(data.environment, access, it) }
                ?: remote.upload(data.environment, access, job, file)
            val result = when (receipt.status) {
                "complete" -> if (receipt.rideId != null) job.copy(status = BikeyardUploadStatus.UPLOADED, rideId = receipt.rideId) else
                    throw BikeyardFailure(BikeyardFailure.Kind.RETRY, "Waiting for BIKEYARD’s ride receipt")
                "duplicate" -> if (receipt.duplicateOf != null) job.copy(status = BikeyardUploadStatus.UPLOADED, rideId = receipt.duplicateOf) else
                    job.copy(status = BikeyardUploadStatus.DUPLICATE, error = "BIKEYARD found this track elsewhere. No ride in your account was confirmed")
                "failed" -> job.copy(status = BikeyardUploadStatus.FAILED, error = "BIKEYARD could not process this track")
                "processing" -> job.copy(status = BikeyardUploadStatus.QUEUED, retryAtMs = now() + 30000, error = "BIKEYARD is processing the upload")
                else -> throw BikeyardFailure(BikeyardFailure.Kind.RETRY, "Waiting for BIKEYARD’s upload status")
            }
            update(result.copy(uploadId = receipt.id))
            result.status !in activeStatuses
        } catch (error: BikeyardFailure) {
            if (error.kind == BikeyardFailure.Kind.AUTH) save(data.copy(tokens = null, autoConsentId = null, message = error.message))
            val status = when (error.kind) {
                BikeyardFailure.Kind.AUTH -> BikeyardUploadStatus.NEEDS_AUTH
                BikeyardFailure.Kind.RETRY -> BikeyardUploadStatus.QUEUED
                BikeyardFailure.Kind.PERMANENT -> BikeyardUploadStatus.FAILED
            }
            update(job.copy(status = status, error = error.message, retryAtMs = error.retryAtMs))
            status !in activeStatuses
        }
    }

    suspend fun queueMetrics(key: String, manual: Boolean = false): Boolean = mutex.withLock {
        val job = data.uploads.firstOrNull { it.key == key } ?: return@withLock false
        if (job.status != BikeyardUploadStatus.UPLOADED || job.rideId == null || job.sensorScopes.isEmpty() ||
            job.visibility != BikeyardVisibility.PRIVATE ||
            job.accountKey != data.accountKey || data.tokens == null) return@withLock false
        if (!manual && (!job.metricsAutomatic || !data.autoMetrics)) return@withLock false
        if (job.metricsStatus in activeMetricsStatuses) return@withLock true
        update(job.copy(metricsStatus = BikeyardMetricsStatus.QUEUED,
            metricsAutomatic = !manual && job.metricsAutomatic,
            metricsError = null, metricsRetryAtMs = 0))
        true
    }

    suspend fun failMetrics(key: String, message: String) = mutex.withLock {
        data.uploads.firstOrNull { it.key == key && it.status == BikeyardUploadStatus.UPLOADED }
            ?.let { update(it.copy(metricsStatus = BikeyardMetricsStatus.FAILED, metricsError = message)) }
    }

    suspend fun processMetrics(key: String, file: File): Boolean = mutex.withLock {
        var job = data.uploads.firstOrNull { it.key == key } ?: return@withLock true
        if (job.metricsStatus !in activeMetricsStatuses || job.status != BikeyardUploadStatus.UPLOADED) {
            return@withLock true
        }
        if (job.metricsAutomatic && !data.autoMetrics) {
            update(job.copy(metricsStatus = BikeyardMetricsStatus.CANCELLED,
                metricsError = "Automatic airtime sync turned off"))
            return@withLock true
        }
        if (job.metricsRetryAtMs > now()) return@withLock false
        val rideId = job.rideId
        if (job.accountKey != data.accountKey || rideId == null) {
            update(job.copy(metricsStatus = BikeyardMetricsStatus.NEEDS_AUTH,
                metricsError = "Reconnect the original BIKEYARD account"))
            return@withLock true
        }
        job = job.copy(metricsStatus = BikeyardMetricsStatus.UPLOADING, metricsError = null)
        update(job)
        try {
            val receipt = remote.putMetrics(data.environment, token(), rideId, file)
            if (receipt.rideId != rideId || receipt.revision < 1) {
                throw BikeyardFailure(BikeyardFailure.Kind.RETRY, "BIKEYARD returned an incomplete metrics receipt")
            }
            update(job.copy(metricsStatus = BikeyardMetricsStatus.UPLOADED,
                metricsRevision = receipt.revision))
            true
        } catch (error: BikeyardFailure) {
            if (error.kind == BikeyardFailure.Kind.AUTH) {
                save(data.copy(tokens = null, autoConsentId = null, message = error.message))
            }
            val status = when (error.kind) {
                BikeyardFailure.Kind.AUTH -> BikeyardMetricsStatus.NEEDS_AUTH
                BikeyardFailure.Kind.RETRY -> BikeyardMetricsStatus.QUEUED
                BikeyardFailure.Kind.PERMANENT -> BikeyardMetricsStatus.FAILED
            }
            update(job.copy(metricsStatus = status, metricsError = error.message,
                metricsRetryAtMs = error.retryAtMs))
            status !in activeMetricsStatuses
        }
    }

    private fun update(job: BikeyardUpload) = save(data.copy(uploads = data.uploads.map { if (it.key == job.key) job else it }))

    companion object {
        val activeStatuses = setOf(BikeyardUploadStatus.QUEUED, BikeyardUploadStatus.UPLOADING)
        val activeMetricsStatuses = setOf(BikeyardMetricsStatus.QUEUED, BikeyardMetricsStatus.UPLOADING)
    }
}
