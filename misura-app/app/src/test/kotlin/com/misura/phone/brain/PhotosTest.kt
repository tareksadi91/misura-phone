package com.misura.phone.brain

import com.misura.core.agent.Photo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PhotosTest {
    private val old = Photo("places/P1/photos/old", "Jin")

    @Test fun mediaThenPhotoUriGivesBytes() = runBlocking<Unit> {
        var base = ""
        TinyHttp { r ->
            when {
                r.path.startsWith("/v1/places/P1/photos/old/media") -> 200 to """{"name":"x","photoUri":"$base/img/1"}"""
                r.path == "/img/1" -> 200 to "PNGDATA"
                else -> 404 to "{}"
            }
        }.use { http ->
            base = http.base
            val p = Photos({ "t" }, { "test-project" }, placesBase = http.base + "/v1")
            p.bytes("P1", old, 640)!!.let { (b, ph) -> b.decodeToString() shouldBe "PNGDATA"; ph shouldBe old }
            http.requests.first().path shouldBe "/v1/places/P1/photos/old/media?maxWidthPx=640&skipHttpRedirect=true"
            http.requests.first().headers["authorization"] shouldBe "Bearer t"
        }
    }

    @Test fun expiredNameRefetchesOnce() = runBlocking<Unit> {
        var base = ""
        TinyHttp { r ->
            when {
                r.path.startsWith("/v1/places/P1/photos/old/media") -> 400 to """{"error":{"message":"expired"}}"""
                r.path == "/v1/places/P1" -> 200 to """{"photos":[{"name":"places/P1/photos/new","authorAttributions":[{"displayName":"Mina","uri":"https://m"}]}]}"""
                r.path.startsWith("/v1/places/P1/photos/new/media") -> 200 to """{"photoUri":"$base/img/2"}"""
                r.path == "/img/2" -> 200 to "FRESH"
                else -> 404 to "{}"
            }
        }.use { http ->
            base = http.base
            val p = Photos({ "t" }, { "test-project" }, placesBase = http.base + "/v1")
            p.bytes("P1", old, 640)!!.let { (b, ph) -> b.decodeToString() shouldBe "FRESH"; ph shouldBe Photo("places/P1/photos/new", "Mina", "https://m") }
            http.requests.count { it.path == "/v1/places/P1" } shouldBe 1
            http.requests.first { it.path == "/v1/places/P1" }.headers["x-goog-fieldmask"] shouldBe "photos"
        }
    }

    @Test fun anyOtherFailureIsNull() = runBlocking<Unit> {
        TinyHttp { 500 to "{}" }.use { http ->
            Photos({ "t" }, { "test-project" }, placesBase = http.base + "/v1").bytes("P1", old, 640) shouldBe null
        }
    }

    @Test fun noTokenIsNull() = runBlocking<Unit> {
        Photos({ null }, { "test-project" }).bytes("P1", old, 640) shouldBe null
    }
}
