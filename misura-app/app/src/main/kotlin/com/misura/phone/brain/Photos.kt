package com.misura.phone.brain

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.misura.core.agent.Photo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Place photos from the Places photo media endpoint, with the same OAuth token as the rest of the brain.
 * Kept in memory only (Places terms). Photo names expire: on a 4xx the place's photos are fetched again once.
 */
class Photos(private val tokens: TokenProvider, private val project: () -> String, private val placesBase: String = "https://places.googleapis.com/v1") {
    // ponytail: ~8 MB of decoded bitmaps; enough for one carousel + detail.
    private val cache = object : LruCache<String, Shown>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Shown) = value.bitmap.width * value.bitmap.height * 4
    }

    /** The decoded photo with the credit of the photo actually shown, or null on any failure (never a broken image). */
    suspend fun bitmap(placeId: String, photo: Photo, widthPx: Int): Shown? {
        val key = "${photo.name}@$widthPx"
        cache.get(key)?.let { return it }
        val (bytes, used) = bytes(placeId, photo, widthPx) ?: return null
        val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() ?: return null
        return Shown(bmp, used).also { cache.put(key, it) }
    }

    /** Raw image bytes + the photo they belong to: media lookup (photoUri), then the image; one refresh of the name on a 4xx. */
    suspend fun bytes(placeId: String, photo: Photo, widthPx: Int): Pair<ByteArray, Photo>? = runCatching {
        // Off the main thread: the screen calls this, and the phone's own login may have to fetch a new token first (Codex P1).
        val token = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tokens.token() } ?: return null
        val headers = googleHeaders(token, project())
        suspend fun media(name: String) = http("GET", "$placesBase/$name/media?maxWidthPx=$widthPx&skipHttpRedirect=true", headers)
        var used = photo
        var (code, text) = media(photo.name)
        if (code in 400..499) {
            val fresh = freshPhoto(placeId, headers) ?: return null
            if (fresh.name == photo.name) return null
            used = fresh // its own author: credit always matches the image shown
            media(fresh.name).let { code = it.first; text = it.second }
        }
        if (code >= 400) return null
        val uri = (Json.parseToJsonElement(text).jsonObject["photoUri"] as? JsonPrimitive)?.content ?: return null
        val (c, b) = httpBytes(uri, emptyMap())
        b.takeIf { c < 400 && it.isNotEmpty() }?.let { it to used }
    }.getOrNull()

    // ponytail: takes the place's first photo as the replacement; good enough while cards show one main photo.
    private suspend fun freshPhoto(placeId: String, headers: Map<String, String>): Photo? {
        val (code, text) = http("GET", "$placesBase/places/$placeId", headers + ("X-Goog-FieldMask" to "photos"))
        if (code >= 400) return null
        val ph = (Json.parseToJsonElement(text).jsonObject["photos"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val a = (ph["authorAttributions"] as? JsonArray)?.firstOrNull() as? JsonObject
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content
        return ph.s("name")?.let { Photo(it, a?.s("displayName"), a?.s("uri")) }
    }
}

/** A loaded photo and the photo record it came from (for the credit line). */
data class Shown(val bitmap: ImageBitmap, val photo: Photo)
