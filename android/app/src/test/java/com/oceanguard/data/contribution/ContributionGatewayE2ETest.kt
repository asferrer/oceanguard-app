package com.oceanguard.ai.data.contribution

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * End-to-end against the LOCAL Worker (`contribution-api/worker/local/serve.mjs`, the real
 * Worker code on 127.0.0.1) with synthetic photos; the NAS side is then checked with
 * `python -m app.cli pull`. Skipped unless OG_E2E_URL and OG_E2E_RECIPIENT_KEY are set
 * (see contribution-api/deploy/CHECKLIST_AUTOR.md, step 5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ContributionGatewayE2ETest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun configOrSkip(): ContributionConfig {
        val config = ContributionConfig(System.getenv("OG_E2E_URL").orEmpty(), System.getenv("OG_E2E_RECIPIENT_KEY").orEmpty())
        assumeTrue("local worker not configured", config.isUsable && config.isLoopback)
        return config
    }

    private val coco = """{"images":[{"id":1,"file_name":"e2e.jpg","width":16,"height":12,"date_captured":"2026-05-17T08:20:30Z"}],""" +
        """"annotations":[{"id":1,"image_id":1,"category_id":0,"category_name":"BOTTLE","bbox":[1,1,4,4],"area":16,"iscrowd":0,"score":0.9}],""" +
        """"categories":[{"id":0,"name":"Bottle"}]}"""

    private fun meta(id: String) = """{"v":1,"contribution_id":"$id","app_version":"e2e","annotations":$coco}"""

    @Test
    fun tenMegabyteUploadsWithAndWithoutLocationAndARetry() = runBlocking {
        val config = configOrSkip()
        val client = ContributionApiClient(config, SoftwareDeviceIdentity())
        val photo = largeSyntheticJpeg(tmp.root, "e2e.jpg") // >= 10 MiB, several parts
        val withGps = ContributionImage.prepare(photo, includeLocation = true, workDir = tmp.newFolder("a")).bytes
        val withoutGps = ContributionImage.prepare(photo, includeLocation = false, workDir = tmp.newFolder("b")).bytes
        val spki = config.recipientSpki!!

        val first = UUID.randomUUID().toString()
        assertEquals(ApiOutcome.Stored(first, false), client.upload(ContributionEnvelope.seal(spki, first, meta(first), withGps), first))
        val second = UUID.randomUUID().toString()
        assertEquals(ApiOutcome.Stored(second, false), client.upload(ContributionEnvelope.seal(spki, second, meta(second), withoutGps), second))
        // Retry of the first one: new ciphertext, same id -> duplicate, not a second copy.
        val again = client.upload(ContributionEnvelope.seal(spki, first, meta(first), withGps), first)
        assertEquals(ApiOutcome.Stored(first, true), again)
    }

    @Test
    fun withdrawalDeletesWhatThatDeviceLeftInTheBuffer() = runBlocking {
        val config = configOrSkip()
        val client = ContributionApiClient(config, SoftwareDeviceIdentity())
        val id = UUID.randomUUID().toString()
        val photo = syntheticJpeg(tmp.root, "w.jpg", withGps = false).readBytes()
        val stored = client.upload(ContributionEnvelope.seal(config.recipientSpki!!, id, meta(id), photo), id)
        assertTrue("got $stored", stored is ApiOutcome.Stored)
        assertEquals(ApiOutcome.Withdrawn(1), client.withdraw())
    }
}
