package com.misura.core.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class ViewsTest {
    private fun j(s: String) = Json.parseToJsonElement(s).jsonObject
    private val known = setOf("A", "B", "C")

    @Test fun unknownIdsDropped() {
        parseShowPlaces(j("""{"placeIds":["A","X","B"],"pick":"B","line":"B is quieter"}"""), known) shouldBe (Places(listOf("A", "B"), "B") to "B is quieter")
    }
    @Test fun noKnownIdsIsError() { shouldThrow<ViewError> { parseShowPlaces(j("""{"placeIds":["X"]}"""), known) } }
    @Test fun missingIdsIsError() { shouldThrow<ViewError> { parseShowPlaces(j("""{}"""), known) } }
    @Test fun moreThanSixIsError() {
        shouldThrow<ViewError> { parseShowPlaces(j("""{"placeIds":["A","B","C","A","B","C","A"]}"""), known) }
    }
    @Test fun aPickThatIsNoIdOrNameFallsToTheFirstPlace() { parseShowPlaces(j("""{"placeIds":["A"],"pick":"C"}"""), known).first.pick shouldBe "A" }
    @Test fun aPickGivenByNameFindsItsPlace() {
        parseShowPlaces(j("""{"placeIds":["A","B"],"pick":"namsan berlin"}"""), known, mapOf("A" to "Mokpo", "B" to "Namsan Berlin")).first.pick shouldBe "B"
    }
    @Test fun noPickMeansTheFirstPlace() { parseShowPlaces(j("""{"placeIds":["B","A"]}"""), known).first.pick shouldBe "B" }
    @Test fun askKeepsChoices() { parseAsk(j("""{"line":"Which area?","choices":["Kreuzberg","Mitte"]}""")) shouldBe Say.Question("Which area?", listOf("Kreuzberg", "Mitte")) }
    @Test fun askLimitsChoicesToFour() { shouldThrow<ViewError> { parseAsk(j("""{"line":"?","choices":["1","2","3","4","5"]}""")) } }
    @Test fun citedAnswersUseOnlyTheRecordedTextAndValidSourceRanges() {
        val citation = Citation("Example", "https://example.org", 2, 6)
        val exact = Say.Answer("😀Fact", listOf(citation), "OpenAI")
        val id = java.util.UUID.randomUUID().toString()
        parseAnswer(j("""{"line":"paraphrase","lookup_id":"$id"}"""), mapOf(id to exact)) shouldBe exact
        shouldThrow<ViewError> { parseAnswer(j("""{"line":"claim","lookup_id":"$id"}""")) }
        citation.copy(start = 1).validFor(exact.line) shouldBe false
        citation.copy(end = 7).validFor(exact.line) shouldBe false
        citation.copy(url = "intent://example.org").validFor(exact.line) shouldBe false
        parseAnswer(j("""{"line":"ordinary"}"""), mapOf(id to exact)) shouldBe Say.Answer("ordinary")
    }
    @Test fun answerNeedsLine() { shouldThrow<ViewError> { parseAnswer(j("""{"line":"  "}""")) } }
}
