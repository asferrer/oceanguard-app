package com.oceanguard.ai.data.contribution

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class ContributionApiClientTest {

    private val server = MockWebServer()
    private val worker = WorkerLikeDispatcher()
    private val identity = SoftwareDeviceIdentity()
    private val envelope = byteArrayOf(0x4f, 0x47, 0x43, 0x31, 1, 4, 9, 9, 9)
    private val contributionId = "3f2b8c1e-5d4a-4b6f-9e8d-7c6b5a493827"

    @Before
    fun start() {
        server.dispatcher = worker
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() = server.shutdown()

    private fun client(token: String? = "jwt-token", partBytes: Int = 4) = ContributionApiClient(
        config = testConfig("http://127.0.0.1:${server.port}/"),
        identity = identity,
        appCheckToken = { token },
        nowSeconds = { NOW },
        partBytes = partBytes,
    )

    private fun upload(c: ContributionApiClient = client(), body: ByteArray = envelope) =
        runBlocking { c.upload(body, contributionId) }

    /** Verifies a request the way the Worker does: canonical string over path+query, raw signature. */
    private fun assertSignedBy(request: RecordedRequest, payloadSha: String, id: String?) {
        assertEquals(payloadSha, request.getHeader("X-OG-Payload-SHA256"))
        val spki = Base64.getUrlDecoder().decode(request.getHeader("X-OG-Device-Key"))
        val message = ContributionSigner.canonical(
            "POST", request.path!!, request.getHeader("X-OG-Timestamp")!!.toLong(), request.getHeader("X-OG-Nonce")!!, payloadSha, id,
        )
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)))
        verifier.update(message)
        assertTrue(verifier.verify(der(Base64.getUrlDecoder().decode(request.getHeader("X-OG-Signature")))))
    }

    @Test
    fun uploadGoesInSignedPartsThenCommit() {
        worker.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"$contributionId","duplicate":false}"""))
        assertEquals(ApiOutcome.Stored(contributionId, duplicate = false), upload())
        val sha = ContributionSigner.sha256Hex(envelope)
        val requests = (1..server.requestCount).map { server.takeRequest() }
        assertEquals(
            listOf(
                "/v1/contributions/$contributionId/parts/0?parts=3&bytes=9",
                "/v1/contributions/$contributionId/parts/1?parts=3&bytes=9",
                "/v1/contributions/$contributionId/parts/2?parts=3&bytes=9",
                "/v1/contributions/$contributionId/commit?parts=3&bytes=9",
            ),
            requests.map { it.path },
        )
        assertEquals(envelope.toList(), requests.dropLast(1).flatMap { it.body.readByteArray().toList() })
        requests.forEach {
            assertSignedBy(it, sha, contributionId)
            assertEquals("jwt-token", it.getHeader("X-Firebase-AppCheck"))
            assertEquals("application/octet-stream", it.getHeader("Content-Type"))
        }
    }

    @Test
    fun tenMegabyteEnvelopeUsesOneD1RowPerPart() {
        val big = ByteArray(10 * 1024 * 1024) { (it % 251).toByte() }
        worker.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        assertTrue(upload(client(partBytes = ContributionApiClient.PART_BYTES), big) is ApiOutcome.Stored)
        val requests = (1..server.requestCount).map { server.takeRequest() }
        val parts = requests.dropLast(1)
        assertEquals(6, parts.size)
        assertTrue(parts.all { it.bodySize <= 2_000_000 })
        assertEquals(big.size.toLong(), parts.sumOf { it.bodySize })
        assertTrue(requests.last().path!!.endsWith("/commit?parts=6&bytes=${big.size}"))
    }

    @Test
    fun tooLargeIsNotSentAtAll() {
        val outcome = upload(body = ByteArray(ContributionApiClient.MAX_ENVELOPE_BYTES + 1))
        assertEquals(ApiOutcome.Rejected(413), outcome)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun duplicateIsSuccess() {
        worker.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"$contributionId","duplicate":true}"""))
        assertEquals(ApiOutcome.Stored(contributionId, true), upload(client(partBytes = 100)))
    }

    @Test
    fun refusalOnAPartStopsTheUpload() {
        worker.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"attestation"}"""))
        assertEquals(ApiOutcome.Failed(ContributionSyncStatus.ATTESTATION, 401), upload(client(token = null)))
        assertEquals(1, server.requestCount)
        assertNull(server.takeRequest().getHeader("X-Firebase-AppCheck"))
    }

    @Test
    fun withoutAppCheckTokenNothingLeavesThePhone() {
        val remote = ContributionApiClient(
            config = testConfig("https://oceanguard-contrib.example.workers.dev/"),
            identity = identity,
            appCheckToken = { null },
        )
        assertEquals(ContributionApiClient.NO_TOKEN, runBlocking { remote.upload(envelope, contributionId) })
        assertEquals(ContributionApiClient.NO_TOKEN, runBlocking { remote.withdraw() })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun answersAreClassified() {
        val cases = listOf(
            MockResponse().setResponseCode(413).setBody("""{"error":"too_large"}""") to ApiOutcome.Rejected(413),
            MockResponse().setResponseCode(422).setBody("""{"error":"bad_envelope"}""") to ApiOutcome.Rejected(422),
            MockResponse().setResponseCode(401).setBody("""{"error":"auth"}""") to ApiOutcome.Failed(ContributionSyncStatus.AUTH, 401),
            MockResponse().setResponseCode(429).setHeader("Retry-After", "3600").setBody("""{"error":"rate_limited"}""") to
                ApiOutcome.Failed(ContributionSyncStatus.RATE_LIMITED, 429, 3600),
            MockResponse().setResponseCode(503).setHeader("Retry-After", "3600").setBody("""{"error":"quota"}""") to
                ApiOutcome.Failed(ContributionSyncStatus.QUOTA, 503, 3600),
            MockResponse().setResponseCode(409).setBody("""{"error":"conflict"}""") to ApiOutcome.Failed(ContributionSyncStatus.SERVER, 409),
            MockResponse().setResponseCode(503).setBody("""{"error":"server"}""") to ApiOutcome.Failed(ContributionSyncStatus.SERVER, 503),
        )
        cases.forEach { (response, expected) ->
            worker.enqueue(response)
            assertEquals(expected, upload())
        }
    }

    @Test
    fun redirectsAreNotFollowed() {
        worker.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.example/"))
        assertEquals(ApiOutcome.Failed(ContributionSyncStatus.SERVER, 302), upload())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun networkFailureIsRetryable() {
        val c = client()
        server.shutdown()
        assertEquals(ApiOutcome.Failed(ContributionSyncStatus.NETWORK, 0), upload(c))
    }

    @Test
    fun withdrawalIsSignedOverItsBody() {
        worker.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"accepted","pending_deleted":2}"""))
        assertEquals(ApiOutcome.Withdrawn(2), runBlocking { client().withdraw() })
        val request = server.takeRequest()
        assertEquals("/v1/withdrawals", request.path)
        assertSignedBy(request, ContributionSigner.sha256Hex(request.body.readByteArray()), null)
    }

    private fun der(raw: ByteArray): ByteArray {
        fun integer(bytes: ByteArray): ByteArray {
            val value = java.math.BigInteger(1, bytes).toByteArray()
            return byteArrayOf(0x02, value.size.toByte()) + value
        }
        val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private companion object {
        const val NOW = 1_767_225_600L
    }
}
