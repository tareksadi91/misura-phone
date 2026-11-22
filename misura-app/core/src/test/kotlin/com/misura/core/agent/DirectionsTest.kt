package com.misura.core.agent

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DirectionsTest {
    @Test fun encodesDestinationAndLeavesModeToMaps() {
        directionsUrl(PlaceFact("id&1", "Café Müller & Söhne")) shouldBe
            "https://www.google.com/maps/dir/?api=1&destination=Caf%C3%A9%20M%C3%BCller%20%26%20S%C3%B6hne&destination_place_id=id%261"
    }
    @Test fun missingIdKeepsMapsPage() {
        directionsUrl(PlaceFact("", "Place", mapsUri = "https://maps.example/place")) shouldBe "https://maps.example/place"
        directionsUrl(PlaceFact("", "Place")) shouldBe null
    }
}
