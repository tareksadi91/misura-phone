package com.misura.phone.brain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RedirectTest {
    @Test fun credentialsNeverFollowRedirectsButAnonymousPhotosCan() = runBlocking {
        TinyHttp { 200 to "image" }.use { destination ->
            TinyHttp(mapOf("Location" to destination.base)) { 302 to "" }.use { redirect ->
                assertEquals(302, http("GET", redirect.base, mapOf("x-api-key" to "fixture")).first)
                assertEquals(302, httpBytes(redirect.base, mapOf("Authorization" to "Bearer fixture")).first)
                assertTrue(destination.requests.isEmpty())
                assertEquals("image", httpBytes(redirect.base, emptyMap()).second.decodeToString())
                assertEquals(1, destination.requests.size)
                assertFalse(destination.requests.single().headers.containsKey("authorization"))
                assertFalse(destination.requests.single().headers.containsKey("x-api-key"))
            }
        }
    }
}
