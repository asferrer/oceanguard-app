package com.oceanguard.ai.data.contribution

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContributionApiClientTest {

    private val server = MockWebServer()
    private val image = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

    @Before
    fun start() = server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)

    @After
    fun stop() = server.shutdown()

    private fun client(token: String? = null) = ContributionApiClient(
        config = ContributionConfig("http://127.0.0.1:${server.port}/base/", KEY_ID, SECRET),
        appCheckToken = { token },
        nowSeconds = { NOW },
    )

    private fun upload(c: ContributionApiClient = client()) = runBlocking {
        c.upload(image, "img_1.jpg", """{"images":[]}""", "client-0001-aaaa", "1.2.0", null)
    }

    @Test
    fun uploadSignsTheImageDigestLikeTheGateway() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"abc","duplicate":false}"""))
        assertEquals(ApiOutcome.Stored("abc", duplicate = false), upload())

        val request = server.takeRequest()
        assertEquals("/base/v1/contributions", request.path)
        val sha = ContributionSigner.sha256Hex(image)
        assertEquals(KEY_ID, request.getHeader("X-OG-Key-Id"))
        assertEquals(NOW.toString(), request.getHeader("X-OG-Timestamp"))
        assertEquals(ContributionSigner.signature(SECRET, KEY_ID, NOW.toString(), sha), request.getHeader("X-OG-Signature"))
        val body = request.body.readUtf8()
        listOf("name=\"image\"", "name=\"annotations\"", sha, "client-0001-aaaa", "1.2.0").forEach {
            assertTrue("missing $it", body.contains(it))
        }
        assertFalse(body.contains("captured_at"))
        assertNull(request.getHeader("X-Firebase-AppCheck"))
    }

    @Test
    fun appCheckTokenIsSentWhenAvailable() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        upload(client(token = "jwt-token"))
        assertEquals("jwt-token", server.takeRequest().getHeader("X-Firebase-AppCheck"))
    }

    @Test
    fun duplicateIsSuccess() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"abc","duplicate":true}"""))
        assertEquals(ApiOutcome.Stored("abc", duplicate = true), upload())
    }

    @Test
    fun contentErrorsArePermanent() {
        listOf(413, 415, 422).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":"x"}"""))
            assertEquals(ApiOutcome.Rejected(code), upload())
        }
    }

    @Test
    fun unauthorisedSaysWhich() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"attestation"}"""))
        assertEquals(ApiOutcome.Failed(ContributionSyncStatus.ATTESTATION, 401), upload())
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"auth"}"""))
        assertEquals(ApiOutcome.Failed(ContributionSyncStatus.AUTH, 401), upload())
    }

    @Test
    fun rateLimitKeepsRetryAfter() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("""{"error":"rate_limited"}"""))
        assertEquals(ApiOutcome.Failed(ContributionSyncStatus.RATE_LIMITED, 429, 120L), upload())
    }

    @Test
    fun serverErrorIsRetryable() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("oops"))
        assertEquals(ApiOutcome.Failed(ContributionSyncStatus.SERVER, 503), upload())
    }

    @Test
    fun networkFailureIsRetryable() {
        val c = client()
        server.shutdown()
        assertEquals(ApiOutcome.Failed(ContributionSyncStatus.NETWORK, 0), upload(c))
    }

    @Test
    fun withdrawSignsTheRawBody() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"withdrawn":3}"""))
        val outcome = runBlocking { client().withdraw("client-0001-aaaa") }
        assertEquals(ApiOutcome.Withdrawn(3), outcome)

        val request: RecordedRequest = server.takeRequest()
        assertEquals("/base/v1/withdrawals", request.path)
        val raw = request.body.readByteArray()
        assertEquals("""{"client_id":"client-0001-aaaa"}""", String(raw))
        val expected = ContributionSigner.signature(SECRET, KEY_ID, NOW.toString(), ContributionSigner.sha256Hex(raw))
        assertEquals(expected, request.getHeader("X-OG-Signature"))
    }

    @Test
    fun withdrawOneIncludesItsId() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"withdrawn":1}"""))
        runBlocking { client().withdraw("client-0001-aaaa", "id-1234-5678") }
        assertTrue(server.takeRequest().body.readUtf8().contains("\"contribution_id\":\"id-1234-5678\""))
    }

    private companion object {
        const val KEY_ID = "og-test-2026"
        const val SECRET = "super-secret-test-value"
        const val NOW = 1_767_225_600L
    }
}
