package com.oceanguard.ai.inference.species

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
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

/**
 * Reads the sprite credits of the installed species pack. Parsed by hand, not with a Gson TypeToken: R8 strips the
 * generic signature in the release build and the map values came back as LinkedTreeMap (ClassCastException).
 */
object SpriteCredits {
    /** Credits by species key; empty when the file is missing or unreadable. */
    fun parse(json: String): Map<String, SpriteCredit> = try {
        val root = JsonParser.parseString(json)
        if (!root.isJsonObject) emptyMap() else root.asJsonObject.entrySet().mapNotNull { (key, value) ->
            (value as? JsonObject)?.let { credit(it) }?.let { key to it }
        }.toMap()
    } catch (e: JsonParseException) {
        emptyMap()
    } catch (e: IllegalStateException) {
        emptyMap()
    }

    private fun JsonObject.text(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun credit(o: JsonObject): SpriteCredit? =
        o.text("attribution").takeIf { it.isNotBlank() }?.let { SpriteCredit(it, o.text("license"), o.text("url")) }

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
