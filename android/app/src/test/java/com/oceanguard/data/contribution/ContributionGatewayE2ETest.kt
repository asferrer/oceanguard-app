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

/**
 * End-to-end against a LOCAL contribution-api (never a public server), with synthetic photos.
 * Skipped unless OG_E2E_URL, OG_E2E_KEY_ID and OG_E2E_SECRET are set, e.g.:
 *
 *   OG_KEYS=og-e2e:local-test-secret OG_APPCHECK_ENFORCE=false OG_DATA_DIR=/tmp/og \
 *     uvicorn app.main:app --port 8077
 *   OG_E2E_URL=http://127.0.0.1:8077 OG_E2E_KEY_ID=og-e2e OG_E2E_SECRET=local-test-secret \
 *     ./gradlew :app:testDebugUnitTest --tests '*ContributionGatewayE2ETest'
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ContributionGatewayE2ETest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun clientOrSkip(): ContributionApiClient {
        val url = System.getenv("OG_E2E_URL").orEmpty()
        val config = ContributionConfig(url, System.getenv("OG_E2E_KEY_ID").orEmpty(), System.getenv("OG_E2E_SECRET").orEmpty())
        assumeTrue("local gateway not configured", config.isUsable && config.url!!.host in setOf("127.0.0.1", "localhost"))
        return ContributionApiClient(config, appCheckToken = { null })
    }

    private val coco = """{"images":[{"id":1,"file_name":"e2e.jpg","width":16,"height":12,"date_captured":"2026-05-17T08:20:30Z"}],""" +
        """"annotations":[{"id":1,"image_id":1,"category_id":0,"category_name":"BOTTLE","bbox":[1,1,4,4],"area":16,"iscrowd":0,"score":0.9}],""" +
        """"categories":[{"id":0,"name":"Bottle"}]}"""

    @Test
    fun uploadWithAndWithoutLocationThenDuplicate() = runBlocking {
        val client = clientOrSkip()
        val photo = syntheticJpeg(tmp.root, "e2e.jpg")
        val withGps = ContributionImage.prepare(photo, includeLocation = true, workDir = tmp.newFolder("a"))
        val withoutGps = ContributionImage.prepare(photo, includeLocation = false, workDir = tmp.newFolder("b"))

        val first = client.upload(withGps, "e2e.jpg", coco, "e2e-client-0001", "e2e", null)
        assertTrue("got $first", first is ApiOutcome.Stored && !first.duplicate)
        val second = client.upload(withoutGps, "e2e_nogps.jpg", coco, "e2e-client-0001", "e2e", null)
        assertTrue("got $second", second is ApiOutcome.Stored && !second.duplicate)
        val again = client.upload(withGps, "e2e.jpg", coco, "e2e-client-0001", "e2e", null)
        assertEquals(ApiOutcome.Stored((first as ApiOutcome.Stored).id, duplicate = true), again)
    }

    @Test
    fun withdrawRemovesOnlyThatClient() = runBlocking {
        val client = clientOrSkip()
        val photo = syntheticJpeg(tmp.root, "w.jpg", withGps = false)
        val bytes = photo.readBytes() + byteArrayOf(0) // distinct digest from the other test
        val stored = client.upload(bytes, "w.jpg", coco, "e2e-client-0002", "e2e", null)
        assertTrue("got $stored", stored is ApiOutcome.Stored)
        assertEquals(ApiOutcome.Withdrawn(1), client.withdraw("e2e-client-0002"))
        assertEquals(ApiOutcome.Withdrawn(0), client.withdraw("e2e-client-0002"))
    }
}
