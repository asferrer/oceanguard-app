package com.oceanguard.ai.species

import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.inference.species.SpeciesPackAssets
import com.oceanguard.ai.inference.species.SpeciesPackConfig
import com.oceanguard.ai.inference.species.SpeciesPackUpdate
import com.oceanguard.ai.inference.species.SpriteCredits
import com.oceanguard.ai.ui.components.spriteFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pack v5: species sprites as optional pack files, their credits and the sprite path. */
class SpeciesSpritesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manifest(extra: String = "") = """
        {"version":5,"encoderFile":"enc.onnx","indexFile":"idx.bin","catalogFile":"cat.json",
         "encoder":{"inputName":"pixel_values","outputName":"image_features","size":224,
                    "mean":[0.5,0.5,0.5],"std":[0.5,0.5,0.5],"dim":768},
         "calibration":{"plattA":20.0,"plattB":-12.0,"tauHigh":0.9,"tauLow":0.5,"delta":0.0}$extra}
    """.trimIndent()

    private val withSprites = manifest(
        ""","sprites":["sprites/octopus_vulgaris.webp","sprites/caretta_caretta.webp"],
           "spriteCredits":"sprite_credits.json",
           "sha256":{"sprites/octopus_vulgaris.webp":"AB","enc.onnx":"cd"}""",
    )

    @Test
    fun `sprites and credits are optional files of the pack`() {
        val c = SpeciesPackConfig.parse(withSprites)!!
        assertEquals(
            listOf("sprites/octopus_vulgaris.webp", "sprites/caretta_caretta.webp", "sprite_credits.json"),
            c.optionalFiles(),
        )
        assertEquals(listOf("enc.onnx", "idx.bin", "cat.json"), c.requiredFiles())
        assertTrue(SpeciesPackAssets.optionalFor(c).all { it.second == SpeciesPackAssets.OPTIONAL_MIN_BYTES })
        assertFalse(SpeciesPackAssets.forConfig(c).any { it.first.startsWith("sprites/") })
    }

    @Test
    fun `a manifest without sprites has no optional files`() {
        val c = SpeciesPackConfig.parse(manifest())!!
        assertTrue(c.optionalFiles().isEmpty())
        assertTrue(SpeciesPackAssets.optionalFor(c).isEmpty())
    }

    @Test
    fun `sprite paths leaving the pack directory are dropped`() {
        val c = SpeciesPackConfig.parse(
            manifest(
                ""","sprites":["sprites/ok.webp","../x.webp","/abs.webp","sprites//y.webp","a\\b.webp",""],
                   "spriteCredits":"../credits.json"""",
            ),
        )!!
        assertEquals(listOf("sprites/ok.webp"), c.optionalFiles())
        assertNull(c.spriteCredits)
    }

    @Test
    fun `installed sprites count as pack files`() {
        val dir = tmp.newFolder("species")
        listOf("enc.onnx", "idx.bin", "cat.json").forEach { File(dir, it).writeText("x") }
        File(dir, SpeciesPackConfig.MANIFEST).writeText(withSprites)
        val names = SpeciesPackAssets.fileNames(dir)
        assertTrue("sprites/octopus_vulgaris.webp" in names)
        assertTrue("sprite_credits.json" in names)
    }

    @Test
    fun `update keeps an unchanged sprite and deletes the ones the new pack drops`() {
        val c = SpeciesPackConfig.parse(withSprites)!!
        val keep = SpeciesPackUpdate.canReuse("sprites/octopus_vulgaris.webp", c, true, 500, 20) { "ab" }
        val changed = SpeciesPackUpdate.canReuse("sprites/octopus_vulgaris.webp", c, true, 500, 20) { "ff" }
        val noHash = SpeciesPackUpdate.canReuse("sprites/caretta_caretta.webp", c, true, 500, 20) { "ab" }
        assertTrue(keep)
        assertFalse(changed)
        assertFalse(noHash)
        val next = c.copy(sprites = listOf("sprites/octopus_vulgaris.webp"))
        assertEquals(listOf("sprites/caretta_caretta.webp"), SpeciesPackUpdate.orphans(c, next))
        assertTrue(SpeciesPackUpdate.orphans(c, c).isEmpty())
    }

    @Test
    fun `credits are read by species key`() {
        val credits = SpriteCredits.parse(
            """{"octopus_vulgaris":{"attribution":"(c) Ana, some rights reserved (CC BY)","license":"cc-by",
                "url":"https://example.org/1"},"bad":{"license":"cc0"}}""",
        )
        assertEquals("(c) Ana, some rights reserved (CC BY)", credits["octopus_vulgaris"]?.attribution)
        assertNull(credits["bad"])
        assertTrue(SpriteCredits.parse("not json").isEmpty())
    }

    @Test
    fun `credit of the installed pack`() {
        val dir = tmp.newFolder("pack")
        listOf("enc.onnx", "idx.bin", "cat.json").forEach { File(dir, it).writeText("x") }
        File(dir, SpeciesPackConfig.MANIFEST).writeText(withSprites)
        File(dir, "sprite_credits.json").writeText("""{"caretta_caretta":{"attribution":"Bob, CC BY 4.0"}}""")
        assertEquals("Bob, CC BY 4.0", SpriteCredits.forSpecies(dir, "caretta_caretta")?.attribution)
        assertNull(SpriteCredits.forSpecies(dir, "octopus_vulgaris"))
    }

    @Test
    fun `catalogue sprite path resolves inside the species pack directory`() {
        val files = tmp.newFolder("files")
        assertEquals(
            File(files, "models/species/sprites/octopus_vulgaris.webp"),
            spriteFile(files, "sprites/octopus_vulgaris.webp"),
        )
        assertNull(spriteFile(files, "../../secret.webp"))
        assertNull(spriteFile(null, "sprites/x.webp"))
    }

    @Test
    fun `update size leaves out the files whose hash does not change`() {
        val v4 = SpeciesPackConfig.parse(manifest(""","sha256":{"enc.onnx":"aa","idx.bin":"bb","cat.json":"cc"}"""))!!
        val v5 = v4.copy(version = 5, sha256 = v4.sha256!! + ("idx.bin" to "dd"))
        val local = mapOf("enc.onnx" to 600L, "idx.bin" to 5L, "cat.json" to 3L)
        val geo = SpeciesPackAssets.GEO.sumOf { local[it.first] ?: 0L }
        // enc.onnx and cat.json keep their hash: only the index (and what the full size does not list) is counted.
        assertEquals(615L - 600L - 3L - geo, SpeciesPackUpdate.approxUpdateBytes(v4, v5, 615L) { local[it] ?: 0L })
    }

    @Test
    fun `a sprites-only update counts just the files that change`() {
        val v6 = SpeciesPackConfig.parse(withSprites)!!.let { c ->
            c.copy(sha256 = mapOf("enc.onnx" to "cd", "idx.bin" to "i", "cat.json" to "c",
                "sprites/octopus_vulgaris.webp" to "ab", "sprites/caretta_caretta.webp" to "c1", "sprite_credits.json" to "k1"))
        }
        val v7 = v6.copy(version = 7, sha256 = v6.sha256!! + mapOf("sprites/caretta_caretta.webp" to "c2", "sprite_credits.json" to "k2"))
        val local = mapOf("enc.onnx" to 600L, "idx.bin" to 5L, "cat.json" to 3L,
            "sprites/octopus_vulgaris.webp" to 3_000L, "sprites/caretta_caretta.webp" to 3_500L, "sprite_credits.json" to 40_000L)
        assertEquals(43_500L, SpeciesPackUpdate.approxUpdateBytes(v6, v7, 615L) { local[it] ?: 0L })
        // A sprite the phone does not have yet counts as about one sprite.
        assertEquals(43_500L - 3_500L + SpeciesPackUpdate.NEW_OPTIONAL_GUESS,
            SpeciesPackUpdate.approxUpdateBytes(v6, v7, 615L) { if (it == "sprites/caretta_caretta.webp") 0L else local[it] ?: 0L })
    }

    @Test
    fun `species without spritePath use the sprite named after their key`() {
        fun entry(path: String?) = SpeciesCatalogEntry("hypanus_americanus", null, "Hypanus americanus", emptyMap(), path, emptyList())
        assertEquals("sprites/hypanus_americanus.webp", entry(null).sprite)
        assertEquals("sprites/hypanus_americanus.webp", entry(" ").sprite)
        assertEquals("sprites/other.webp", entry("sprites/other.webp").sprite)
    }
}
