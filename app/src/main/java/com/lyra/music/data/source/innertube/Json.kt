package com.lyra.music.data.source.innertube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Utilidades para leer las respuestas de InnerTube sin depender de rutas
 * exactas: YouTube cambia a menudo la forma de envolver los datos, pero los
 * "renderers" de dentro (musicResponsiveListItemRenderer, etc.) se mantienen.
 * Por eso casi todo se busca recorriendo el árbol en vez de con rutas fijas.
 */

internal fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

internal fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

internal fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.content

internal fun JsonElement?.at(vararg path: String): JsonElement? {
    var current: JsonElement? = this
    for (key in path) current = (current as? JsonObject)?.get(key) ?: return null
    return current
}

internal fun JsonElement?.atStr(vararg path: String): String? = (at(*path) as? JsonPrimitive)?.content

/** Todos los objetos que cuelgan de [key], en orden de documento. No entra en [skip]. */
internal fun JsonElement.findAll(
    key: String,
    skip: Set<String> = emptySet(),
    out: MutableList<JsonObject> = mutableListOf(),
): List<JsonObject> {
    when (this) {
        is JsonObject -> for ((k, v) in this) {
            if (k in skip) continue
            if (k == key && v is JsonObject) out += v
            v.findAll(key, skip, out)
        }
        is JsonArray -> for (v in this) v.findAll(key, skip, out)
        else -> Unit
    }
    return out
}

internal fun JsonElement.findFirst(key: String): JsonObject? {
    when (this) {
        is JsonObject -> for ((k, v) in this) {
            if (k == key && v is JsonObject) return v
            v.findFirst(key)?.let { return it }
        }
        is JsonArray -> for (v in this) v.findFirst(key)?.let { return it }
        else -> Unit
    }
    return null
}

internal fun JsonElement.findFirstString(key: String): String? {
    when (this) {
        is JsonObject -> for ((k, v) in this) {
            if (k == key && v is JsonPrimitive && v.isString) return v.content
            v.findFirstString(key)?.let { return it }
        }
        is JsonArray -> for (v in this) v.findFirstString(key)?.let { return it }
        else -> Unit
    }
    return null
}

/** Un trozo de texto de YouTube, con el enlace que lleve (artista, álbum, vídeo…). */
internal data class Run(
    val text: String,
    val browseId: String? = null,
    val pageType: String? = null,
    val videoId: String? = null,
    val playlistId: String? = null,
    val videoType: String? = null,
) {
    val isSeparator: Boolean get() = text.trim() == "•"
}

internal fun JsonElement?.runs(): List<Run> {
    val obj = this as? JsonObject ?: return emptyList()
    obj.str("simpleText")?.let { return listOf(Run(it)) }
    val runs = obj.arr("runs") ?: return emptyList()
    return runs.mapNotNull { run ->
        val text = run.str("text") ?: return@mapNotNull null
        val nav = run.obj("navigationEndpoint")
        Run(
            text = text,
            browseId = nav.atStr("browseEndpoint", "browseId"),
            pageType = nav.atStr(
                "browseEndpoint", "browseEndpointContextSupportedConfigs",
                "browseEndpointContextMusicConfig", "pageType",
            ),
            videoId = nav.atStr("watchEndpoint", "videoId"),
            playlistId = nav.atStr("watchEndpoint", "playlistId"),
            videoType = nav.atStr(
                "watchEndpoint", "watchEndpointMusicSupportedConfigs",
                "watchEndpointMusicConfig", "musicVideoType",
            ),
        )
    }
}

internal fun List<Run>.joinText(): String = joinToString("") { it.text }.trim()

/** Parte "Canción • Artista • Álbum" en grupos separados por " • ". */
internal fun List<Run>.groups(): List<List<Run>> {
    val result = mutableListOf<MutableList<Run>>(mutableListOf())
    for (run in this) {
        if (run.isSeparator) result += mutableListOf<Run>() else result.last() += run
    }
    return result.filter { group -> group.any { it.text.isNotBlank() } }
}

/** La miniatura más grande que haya debajo de [this]. */
internal fun JsonElement?.bestThumbnail(): String? {
    val element = this ?: return null
    val list = (element as? JsonObject)?.get("thumbnails") as? JsonArray
        ?: element.findAll("thumbnail").firstNotNullOfOrNull { it["thumbnails"] as? JsonArray }
        ?: return null
    val best = list.maxByOrNull { (it.str("width")?.toIntOrNull() ?: 0) } ?: return null
    return best.str("url")?.let(::normalizeThumbnail)
}

private val SIZE_SUFFIX = Regex("=w\\d+-h\\d+[^/]*$")
private val S_SUFFIX = Regex("=s\\d+[^/]*$")

/** Las carátulas de googleusercontent se pueden pedir al tamaño que queramos. */
internal fun normalizeThumbnail(url: String): String {
    val clean = if (url.startsWith("//")) "https:$url" else url
    return when {
        SIZE_SUFFIX.containsMatchIn(clean) -> clean.replace(SIZE_SUFFIX, "=w544-h544-l90-rj")
        S_SUFFIX.containsMatchIn(clean) -> clean.replace(S_SUFFIX, "=s544")
        else -> clean
    }
}

/** Versión en alta resolución de una carátula (para la pantalla de reproducción). */
fun hiResArtwork(url: String?, size: Int = 1080): String? {
    url ?: return null
    return when {
        SIZE_SUFFIX.containsMatchIn(url) -> url.replace(SIZE_SUFFIX, "=w$size-h$size-l90-rj")
        S_SUFFIX.containsMatchIn(url) -> url.replace(S_SUFFIX, "=s$size")
        url.contains("sndcdn.com") -> url.replace(Regex("-(large|t\\d+x\\d+)\\."), "-t500x500.")
        else -> url
    }
}
