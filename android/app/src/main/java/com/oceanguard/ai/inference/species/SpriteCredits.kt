package com.oceanguard.ai.inference.species

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException

/**
 * Credit of the source image a species sprite was drawn from (`sprite_credits.json` of the pack, v5 and later).
 *
 * @param attribution Author and licence as the source gives them, e.g. "(c) Name, some rights reserved (CC BY)".
 * @param license     Licence code ("cc-by", "cc0", "public domain", "cc by 4.0"...).
 * @param url         Page of the source image.
 */
data class SpriteCredit(val attribution: String, val license: String = "", val url: String = "")

/** Reads the sprite credits of the installed species pack. */
object SpriteCredits {
    private val type = object : TypeToken<Map<String, SpriteCredit>>() {}.type

    /** Credits by species key; empty when the file is missing or unreadable. */
    fun parse(json: String): Map<String, SpriteCredit> = try {
        Gson().fromJson<Map<String, SpriteCredit>?>(json, type).orEmpty()
            .filter { (key, credit) -> key != null && credit != null && credit.attribution != null && credit.attribution.isNotBlank() }
    } catch (e: JsonParseException) {
        emptyMap()
    }

    /** Credit of [speciesKey] in the pack installed in [dir]; null when there is none. */
    fun forSpecies(dir: File, speciesKey: String): SpriteCredit? {
        val name = SpeciesPackConfig.load(dir)?.spriteCredits ?: return null
        val file = File(dir, name).takeIf { it.isFile } ?: return null
        return try {
            parse(file.readText(Charsets.UTF_8))[speciesKey]
        } catch (e: IOException) {
            null
        }
    }
}
