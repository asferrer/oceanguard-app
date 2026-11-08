package com.oceanguard.ai.data.contribution

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
    private val identity = SoftwareDeviceIdentity()
    private val envelope = byteArrayOf(0x4f, 0x47, 0x43, 0x31, 1, 4, 9, 9, 9)
    private val contributionId = "3f2b8c1e-5d4a-4b6f-9e8d-7c6b5a493827"

    @Before
    fun start() = server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)

    @After
    fun stop() = server.shutdown()

    private fun client(token: String? = "jwt-token") = ContributionApiClient(
        config = testConfig("http://127.0.0.1:${server.port}/"),
        identity = identity,
        appCheckToken = { token },
        nowSeconds = { NOW },
    )

    private fun upload(c: ContributionApiClient = client()) = runBlocking { c.upload(envelope, contributionId) }

    /** Verifies the request the way the Worker does: canonical string, device key, raw signature. */
    private fun assertSignedBy(request: okhttp3.mockwebserver.RecordedRequest, body: ByteArray, id: String?) {
        val sha = ContributionSigner.sha256Hex(body)
        assertEquals(sha, request.getHeader("X-OG-Payload-SHA256"))
        val spki = Base64.getUrlDecoder().decode(request.getHeader("X-OG-Device-Key"))
        val message = ContributionSigner.canonical(
            "POST", request.path!!, request.getHeader("X-OG-Timestamp")!!.toLong(), request.getHeader("X-OG-Nonce")!!, sha, id,
        )
        val raw = Base64.getUrlDecoder().decode(request.getHeader("X-OG-Signature"))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)))
        verifier.update(message)
        assertTrue(verifier.verify(der(raw)))
    }

    @Test
    fun uploadIsSignedByTheDeviceAndCarriesAppCheck() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"$contributionId","duplicate":false}"""))
        assertEquals(ApiOutcome.Stored(contributionId, duplicate = false), upload())
        val request = server.takeRequest()
        assertEquals("/v1/contributions", request.path)
        assertEquals("application/octet-stream", request.getHeader("Content-Type"))
        assertEquals(contributionId, request.getHeader("X-OG-Contribution-Id"))
        assertEquals("jwt-token", request.getHeader("X-Firebase-AppCheck"))
        assertEquals(NOW.toString(), request.getHeader("X-OG-Timestamp"))
        assertEquals(envelope.toList(), request.body.readByteArray().toList())
        assertSignedBy(request, envelope, contributionId)
        assertTrue(request.getHeader("X-OG-Nonce")!!.matches(Regex("[0-9a-f]{24}")))
    }

    @Test
    fun withoutTokenTheHeaderIsAbsent() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"attestation"}"""))
        assertEquals(ApiOutcome.Failed(ContributionSyncStatus.ATTESTATION, 401), upload(client(token = null)))
        assertNull(server.takeRequest().getHeader("X-Firebase-AppCheck"))
    }

    @Test
    fun answersAreClassified() {
        val cases = listOf(
            MockResponse().setResponseCode(200).setBody("""{"id":"x","duplicate":true}""") to ApiOutcome.Stored("x", true),
            MockResponse().setResponseCode(413).setBody("""{"error":"too_large"}""") to ApiOutcome.Rejected(413),
            MockResponse().setResponseCode(422).setBody("""{"error":"bad_envelope"}""") to ApiOutcome.Rejected(422),
            MockResponse().setResponseCode(401).setBody("""{"error":"auth"}""") to ApiOutcome.Failed(ContributionSyncStatus.AUTH, 401),
            MockResponse().setResponseCode(429).setHeader("Retry-After", "3600").setBody("""{"error":"rate_limited"}""") to
                ApiOutcome.Failed(ContributionSyncStatus.RATE_LIMITED, 429, 3600),
            MockResponse().setResponseCode(503).setHeader("Retry-After", "3600").setBody("""{"error":"quota"}""") to
                ApiOutcome.Failed(ContributionSyncStatus.QUOTA, 503, 3600),
            MockResponse().setResponseCode(503).setBody("""{"error":"server"}""") to ApiOutcome.Failed(ContributionSyncStatus.SERVER, 503),
        )
        cases.forEach { (response, expected) ->
            server.enqueue(response)
            assertEquals(expected, upload())
        }
    }

    @Test
    fun redirectsAreNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.example/"))
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
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"accepted","pending_deleted":2}"""))
        assertEquals(ApiOutcome.Withdrawn(2), runBlocking { client().withdraw() })
        val request = server.takeRequest()
        assertEquals("/v1/withdrawals", request.path)
        assertNull(request.getHeader("X-OG-Contribution-Id"))
        assertSignedBy(request, request.body.readByteArray(), null)
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
