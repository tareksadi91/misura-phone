# Misura interaction-model refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace "one model-painted card per session" with a step stream on the screen plus app-rendered decision cards (place carousel + map, detail, question, problem, docked), up to the decision in Run 1.

**Architecture:** `:core` gets the new session model (steps + places + say), step lifecycle in `Agent`, four model tools (`find_places`, `show_places`, `ask`, `answer`), place lookup from stored tool results, nav stack and home lines, all JVM-tested. `:app` gets enriched `FindPlaces`, new tokens/type, Compose screen pieces and cards ported from `docs/design/design-system.html`, and a rewritten `HomeActivity`.

**Tech Stack:** Kotlin, Jetpack Compose (BOM in `libs`), kotlinx.serialization, coroutines, Room (JSON rows), MapLibre 13.6.1, JUnit 5 + kotest matchers (core), Compose UI tests on emulator.

**Spec:** `agent-phone/docs/superpowers/specs/2026-11-11-misura-interaction-refactor-design.md` (under `docs/SYSTEM.md` and `docs/design/design-system.md`).

## Global Constraints

- Run all Gradle from `agent-phone/misura-app` with `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_SERIAL=emulator-5554`, `--console=plain` (never `-q`). Gradle hang → `pkill -f GradleDaemon; ./gradlew --stop`.
- Full suite (end of every UI task): `./gradlew :core:test :app:testDebugUnitTest :testapp:installDebug :app:connectedDebugAndroidTest --console=plain`.
- Agent limits unchanged: ≤ 8 tool calls, 30 s per model call, 90 s per turn, per-session ordered queue.
- Logs (`onStep`) carry tool names, counts and ids only — never user text, step labels, or docked-context notes.
- Every place fact on screen (name, why, hours, rating, price, photos, coordinates, Maps link) comes from `find_places` results; the model supplies only place ids, the pick id, and short lines.
- Places content older than 30 days (session `createdAt`) renders as name + why only.
- Colours: screen #080706, surface #32302e, surface-sunk #1c1a19, text #efeeec, text-2 #adaaa8, paper #efeeec, ink #110f0e, orange #f47b30 (only orange), outline #82807e, you #4285f4, star #fbbc04.
- Type (Hanken Grotesk, sp): said 19/300, echo 16/300 text-2, outcome 34/300, step 13.5/400, nav 12/600 text-2, clock 46/300; card label 11.5/600 text-2, body 14/400 lh 1.35, title 17/700, big 28/800 −0.045em, button 14/700.
- Corners (3a): screen 20 → card 14 → tile 8; card inset 6; screen-text gutter 16; touch targets ≥ 48 dp.
- No new dependencies unless a task says so (none planned).
- Controller, routes, sleep screen, experiment screen, `cards/Card.kt` + `CommitTapTest` untouched.

## Review Focus

1. Model passes `show_places` ids that aren't in any result (or none valid) → error back to the model once, then a Problem; never an empty carousel. → Task 3 test `showPlacesWithUnknownIdsIsRejectedThenProblem`.
2. A tool returns `{"error": …}` → its step is ✕, not ✓. → Task 2 test `errorResultClosesStepAsFailed`.
3. Process killed mid-search → on restart the RUNNING step is ✕ "Interrupted" and the session FAILED. → Task 2 test `recoverFailsRunningSteps`.
4. Places without coordinates or photos → carousel renders; map strip hidden when no place has coordinates. → Task 7 test `carouselWithoutCoordinatesHidesMap`.
5. A session row written by the current build (with `"card"`) decodes after upgrade without crashing and shows its text. → Task 1 test `legacyTextCardBecomesAnswer` + Task 9 emulator test `oldRowSurvivesUpgrade`.

---

### Task 1: Session model (steps, places, say)

**Files:**
- Modify: `core/src/main/kotlin/com/misura/core/agent/Model.kt` (`Session` gains fields; `card: CardSpec?` stays until Task 3)
- Create: `core/src/main/kotlin/com/misura/core/agent/Session.kt`
- Test: `core/src/test/kotlin/com/misura/core/agent/SessionModelTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable enum class StepState { RUNNING, DONE, FAILED }
  @Serializable data class Step(val id: String, val turn: Int, val label: String, val state: StepState, val result: String? = null, val startedAt: Long, val endedAt: Long? = null)
  @Serializable data class Places(val placeIds: List<String>, val pick: String? = null)
  @Serializable sealed interface Say {
    @Serializable @SerialName("answer") data class Answer(val line: String) : Say
    @Serializable @SerialName("question") data class Question(val line: String, val choices: List<String> = emptyList()) : Say
    @Serializable @SerialName("problem") data class Problem(val line: String) : Say
  }
  // Session keeps its current fields (id, status, card, revision, createdAt) and gains, with defaults:
  //   val steps: List<Step> = emptyList(), val places: Places? = null, val say: Say? = null
  ```

- [ ] **Step 1: Write failing tests** in `SessionModelTest`: `newSessionRoundTrips` (steps + places + Question survive encode/decode); `rowWithoutNewFieldsDecodes` (`{"id":"s","status":"IDLE","card":null,"revision":1,"createdAt":0}` → steps empty, places/say null).
- [ ] **Step 2: Run** `./gradlew :core:test --tests '*SessionModelTest*' --console=plain` → FAIL (unresolved `Step`).
- [ ] **Step 3: Implement** `Session.kt` with the types above and add the three fields to `Session`.
- [ ] **Step 4: Run** `./gradlew :core:test --console=plain` → PASS (existing tests untouched).
- [ ] **Step 5: Commit** `git commit -m "Misura: session model with steps, places and say"`

### Task 2: Step lifecycle in the agent

**Files:**
- Modify: `core/src/main/kotlin/com/misura/core/agent/Model.kt` (`Tool` interface), `core/src/main/kotlin/com/misura/core/agent/Agent.kt` (`loop`, `turn`, `fail`, `recover`)
- Modify: `core/src/test/kotlin/com/misura/core/agent/AgentFakes.kt` (`FakeTool` gets `label`/`summary` params), `app/src/main/kotlin/com/misura/phone/brain/VertexBrain.kt` (`BrainError` codes)
- Test: `core/src/test/kotlin/com/misura/core/agent/StepTest.kt`

**Interfaces:**
- Consumes: `Step`, `StepState`, `Session.steps` (Task 1).
- Produces:
  ```kotlin
  interface Tool { val spec: ToolSpec; suspend fun run(args: JsonObject): JsonObject
      fun label(args: JsonObject): String; fun summary(result: JsonObject): String? }
  class FakeTool(spec, label: String = "Working on it", summary: (JsonObject) -> String? = { null }, result: suspend (JsonObject) -> JsonObject)
  ```
- `BrainError` gains `val code: String = "error"` (VertexBrain sets the HTTP status, "network", "unreadable" or "empty"). Agent failure lines, step results, `onStep` and `onProblem` use `code` only, never `message` (backend errors can echo the user's words).
- Rules: a step per external tool call, appended RUNNING before `run`, saved; closed DONE with `summary(result)` or FAILED when `run` throws, times out, or the result has an `"error"` key (result = the error text shortened to one line). `turn` index = count of user text turns so far. Turn failures (model error, timeout, too many steps, card errors) close RUNNING steps FAILED and append one synthetic FAILED step whose label is the failure line. `recover()` closes RUNNING steps as FAILED with result "Interrupted". Step saves bump nothing (revision only tracks `places`/`say`).

- [ ] **Step 1: Write failing tests** in `StepTest`: `toolCallMakesRunningThenDoneStep` (label "Searching x", summary "2 places found", state DONE, endedAt set); `errorResultClosesStepAsFailed`; `toolExceptionClosesStepAsFailed`; `timeoutFailsOpenStepAndAddsSyntheticStep` (slow tool, 90 s turn → steps `[FAILED Searching…, FAILED "Misura took too long…"]`); `recoverFailsRunningSteps` (store a session THINKING with a RUNNING step → `recover()` → step FAILED, result "Interrupted", status FAILED); `stepsCarryTurnIndex` (follow-up's steps have turn 2); `stepLabelsNeverLogged` (label contains the user's query; captured `onStep` lines don't contain it); `backendErrorTextNeverShownOrLogged` (brain throws `BrainError("400 bibimbap in Kreuzberg is invalid", code = "400")` → Problem line, steps, `onStep`, `onProblem` contain "400" but not "bibimbap").
- [ ] **Step 2: Run** `./gradlew :core:test --tests '*StepTest*' --console=plain` → FAIL.
- [ ] **Step 3: Implement** in `Agent.loop`/`turn`/`fail`/`recover`; `FindPlaces` gets a placeholder `label = "Searching places"`, `summary = null` so `:app` compiles (Task 5 makes them real).
- [ ] **Step 4: Run** `./gradlew :core:test :app:testDebugUnitTest --console=plain` → PASS.
- [ ] **Step 5: Commit** `git commit -m "Misura: agent records a step per tool call"`

### Task 3: Model tools show_places / ask / answer (show_card and finish go)

**Files:**
- Create: `core/src/main/kotlin/com/misura/core/agent/Views.kt` (validation)
- Create: `core/src/main/kotlin/com/misura/core/agent/PlaceFacts.kt`
- Modify: `core/src/main/kotlin/com/misura/core/agent/Agent.kt` (tools, `SYSTEM_PROMPT`, `pick` → `choose`, remove links/`withLinks`), `Model.kt` (delete `CardSpec`, `Option`, `Pin`, `parseCard`, `MAX_OPTIONS`, `CardError` → `ViewError`)
- Delete: `SpecCard.kt` and `SpecCardTest.kt`; `HomeActivity` renders plain `Text(say.line)` until Task 9
- Modify (compile): `app/src/test/kotlin/com/misura/phone/brain/LiveEval.kt` and `app/src/androidTest/kotlin/com/misura/phone/store/SessionStoreTest.kt` switch from `CardSpec` to `places`/`say` (LiveEval's real assertions are rewritten in Task 10)
- Test: `core/src/test/kotlin/com/misura/core/agent/ViewsTest.kt`, `PlaceFactsTest.kt`; adapt `AgentTest.kt`

**Interfaces:**
- Consumes: `Session.places/say` (Task 1), step lifecycle (Task 2; these three tools make no step).
- Produces:
  ```kotlin
  @Serializable data class PlaceFact(val id: String, val label: String, val why: String? = null, val hours: String? = null,
      val week: List<String> = emptyList(), val lat: Double? = null, val lng: Double? = null, val mapsUri: String? = null,
      val rating: Double? = null, val ratingCount: Int? = null, val price: Int? = null, val kind: String? = null,
      val photos: List<Photo> = emptyList())
  @Serializable data class Photo(val name: String, val author: String? = null, val authorUri: String? = null)
  fun placeFacts(turns: List<Turn>): Map<String, PlaceFact>   // from every find_places functionResponse; later results win; places without placeId skipped
  fun PlaceFact.stale(): PlaceFact                            // keeps id, label, why only
  // Session: `card: CardSpec?` becomes `@SerialName("card") val legacyCard: JsonObject? = null`
  fun Session.upgraded(): Session   // legacyCard → say, then legacyCard = null. text → Answer(line); problem → Problem(line);
                                    // choices/map → Answer(title); other → Answer("Older answer"). New sessions unchanged.
  // InMemorySessionStore and RoomSessionStore.load/recent return .upgraded()
  class ViewError(message: String) : Exception(message)
  fun parseShowPlaces(args: JsonObject, known: Set<String>): Pair<Places, String?>   // (places, line); unknown ids dropped; 0 left or >6 → ViewError; pick not in ids → null
  fun parseAsk(args: JsonObject): Say.Question     // line required, choices ≤ 4 non-blank
  fun parseAnswer(args: JsonObject): Say.Answer    // line required
  // Agent members (replace pick):
  fun choose(sessionId: String, index: Int, seenRevision: Int): Deferred<Boolean>   // Question choice → "I picked <label>" turn; refused on revision change or non-question
  fun retry(sessionId: String): Job          // re-appends the last user text turn verbatim (all its text parts, incl. a "Looking at" note) as a new turn
  ```
- Tool specs: `show_places(placeIds: array<string> required, pick: string, line: string)`, `ask(line: string required, choices: array<string>)`, `answer(line: string required)`. Each ends the turn (status IDLE) after every call in the same model reply has its response appended (keeps the existing all-calls-answered guard, `parallelCallsAllGetResponsesInOrder`). Facts for validation = `placeFacts(stored turns)` plus results produced earlier in the same reply, so `find_places` + `show_places` in one reply works. `show_places` sets `places`, and `say = line?.let(::Answer)`. `ask`/`answer` set `say` only (places stay). Plain-text model reply = `answer`. Bad args: error back to the model once, second time → Problem (as `cardErrors` today).
- `SYSTEM_PROMPT`: replace the `show_card`/`finish` paragraph with: use `find_places` for facts; to show places call `show_places` with their placeIds in your order of preference, `pick` = your recommendation, `line` = one short sentence why the pick; to answer call `answer` with one or two short sentences; to ask call `ask` with the question and up to 4 short choices; when the user is "Looking at" a place, "it" means that place. Keep everything from "Keep notes short" through the taste block, minus the show_card sentences.

- [ ] **Step 1: Write failing tests.** `SessionModelTest` adds: `legacyTextCardBecomesAnswer` (decode `{"id":"s","status":"IDLE","revision":1,"createdAt":0,"card":{"type":"text","line":"Closes at 12"}}` → `upgraded().say shouldBe Say.Answer("Closes at 12")`, `legacyCard shouldBe null`), `legacyMapCardBecomesAnswerWithTitle`, `legacyProblemStaysProblem`. `PlaceFactsTest`: `factsComeFromFindPlacesResponses`, `laterResultWins`, `placesWithoutIdSkipped`, `staleKeepsNameAndWhyOnly`. `ViewsTest`: `unknownIdsDropped`, `noKnownIdsIsError`, `moreThanSixIsError`, `pickOutsideIdsIgnored`, `askLimitsChoicesToFour`, `answerNeedsLine`. `AgentTest` (rewrite the card-based ones, keep the rest): `plainTextEndsAsAnswer`, `findThenShowPlacesSetsPlacesAndLine`, `answerAfterShowPlacesKeepsPlaces`, `showPlacesWithUnknownIdsIsRejectedThenProblem`, `askSetsQuestion`, `chooseAgainstOldRevisionIsRefused`, `chooseAddsTheLabelAsAUserTurn`, `retryResendsLastUserText`; `findAndShowInOneReplyWorks`; `terminalToolStillAnswersEveryCallInTheReply`. Delete `mapsLinksComeFromToolResultsNotTheModel`, `mapWithoutCoordinatesRendersAsChoices`, `mapsLinksSurviveIntoLaterTurns` (links now come only from `placeFacts`, covered by `PlaceFactsTest`).
- [ ] **Step 2: Run** `./gradlew :core:test --console=plain` → FAIL.
- [ ] **Step 3: Implement** `PlaceFacts.kt`, `Views.kt`, agent changes, prompt; stub `HomeActivity` rendering to `say` text so `:app` compiles.
- [ ] **Step 4: Run** `./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --console=plain` → PASS; `grep -rn 'CardSpec\|show_card' core app/src` → no hits.
- [ ] **Step 5: Commit** `git commit -m "Misura: model shows places, asks and answers; app draws the cards"`

### Task 4: Follow-up target (docked context)

**Files:**
- Modify: `core/src/main/kotlin/com/misura/core/agent/Agent.kt` (`followUp`)
- Test: `core/src/test/kotlin/com/misura/core/agent/FollowUpTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class Target(val sessionId: String, val revision: Int, val placeId: String?, val placeLabel: String?)
  fun followUp(sessionId: String, text: String, lookingAt: PlaceFact? = null): Job   // Agent member, replaces followUp(id, text)
  ```
- With `lookingAt`, the user turn has two text parts: the user's text, then `"(Looking at: <label>, place id <id>)"`. No revision check (nothing commits). `Target` is UI-held (Task 9) and only passes `lookingAt` through.

- [ ] **Step 1: Write failing tests**: `lookingAtAddsANotePart` (second part text equals `(Looking at: Namsan, place id P1)`); `noTargetNoNote`; `notesNeverLogged`; `retryReplaysTheNote` (failed follow-up with lookingAt → `retry` → new user turn has both parts).
- [ ] **Step 2: Run** `./gradlew :core:test --tests '*FollowUpTest*' --console=plain` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :core:test --console=plain` → PASS.
- [ ] **Step 5: Commit** `git commit -m "Misura: follow-ups carry the place you're looking at"`

### Task 5: FindPlaces enrichment + readable label

**Files:**
- Modify: `app/src/main/kotlin/com/misura/phone/brain/FindPlaces.kt`
- Test: `app/src/test/kotlin/com/misura/phone/brain/FindPlacesTest.kt`

**Interfaces:**
- Consumes: `Tool.label/summary` (Task 2), `PlaceFact`/`Photo` field names (Task 3) — output keys must match: `rating`, `ratingCount`, `price`, `kind`, `photos` (objects `{name, author, authorUri}`).
- Field mask: `displayName,location,regularOpeningHours.weekdayDescriptions,rating,userRatingCount,priceLevel,primaryTypeDisplayName,photos`. `price` maps `PRICE_LEVEL_INEXPENSIVE..VERY_EXPENSIVE` → 1..4 (FREE/unspecified → absent). `kind` = `primaryTypeDisplayName.text`. `photos` = first 3, each `name` + first `authorAttributions[]` `displayName`/`uri`.
- `label(args)` = `"Searching ${query}${near?.let { " near $it" } ?: ""}"` (query as typed by the model, trimmed to 40 chars). `summary(result)` = `"${n} places found"` / `"1 place found"` / `"No places found"`.

- [ ] **Step 1: Write failing tests**: `detailsAddRatingPriceKindAndPhotos` (fixture with rating 4.6, userRatingCount 812, `PRICE_LEVEL_MODERATE`, primaryTypeDisplayName "Korean restaurant", 4 photos with authorAttributions → rating 4.6, ratingCount 812, price 2, kind "Korean restaurant", 3 photos with names and authors); `missingEnrichmentFieldsAreAbsent`; `labelUsesQueryAndNear`; `summaryCountsPlaces`; update the field-mask assertion in `placesDetailsAddCoordinates` (FindPlacesTest.kt:48) to the new mask; other existing cases stay green.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*FindPlacesTest*' --console=plain` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `git commit -m "Misura: places carry rating, price and photos"`

### Task 6: Tokens, type, and screen pieces (echo, steps, nav line, answer, question/problem card)

**Files:**
- Modify: `app/src/main/kotlin/com/misura/phone/cards/CardViews.kt` (colours, font weights 300–800, replace `K` scaling, split `T` into screen and card roles; keep names used by `Card.kt`/experiment screen compiling)
- Create: `app/src/main/kotlin/com/misura/phone/screen/ScreenPieces.kt` (`Echo`, `Steps`, `NavLine`, `AnswerText`)
- Create: `app/src/main/kotlin/com/misura/phone/screen/QuestionCard.kt` (question + problem variants)
- Create: `app/src/main/kotlin/com/misura/phone/screen/Icons.kt` (line icons: pin, star, bowl, chat; 16 dp, 1.4 stroke, text-2)
- Test: `app/src/androidTest/kotlin/com/misura/phone/screen/ScreenPiecesTest.kt`

**Interfaces:**
- Consumes: `Step`, `Say` (Task 1).
- Produces (Compose):
  ```kotlin
  @Composable fun Echo(text: String)
  @Composable fun Steps(steps: List<Step>, now: Long)          // RUNNING: orange dot, "· 12 s" after 5 s; DONE: ✓ + result; FAILED: ✕ (orange) + result
  @Composable fun NavLine(back: String?, here: String, stepCount: Int, onBack: () -> Unit, onSteps: () -> Unit)
  @Composable fun AnswerText(line: String)                      // outcome style ≤ 24 chars, else said style
  @Composable fun QuestionCard(q: Say.Question, onChoice: (Int) -> Unit)
  @Composable fun ProblemCard(p: Say.Problem, onRetry: () -> Unit)   // label "Didn't work", body = line, Forward button "Try again"
  ```
- Motion: steps fade in, cards rise, 150–250 ms ease-out; with system animations off (reduced motion) fades only, no movement.
- Scale: pick the dp/sp factor so an emulator screenshot of the Working screen matches `design-system.html` (3a frame) at the same physical width; record the factor as one constant with a comment naming the comparison.

- [ ] **Step 1: Write failing emulator tests**: `stepsAreAPoliteLiveRegion` (semantics `LiveRegionMode.Polite`, no focus request); `runningStepShowsDotAndElapsedAfterFiveSeconds`; `doneStepShowsCheckAndResult`; `failedStepShowsCrossAndResult`; `stepRowsHaveTalkBackText` (content description "Searching bibimbap, done, 6 places found"); `navBackIs48dp`; `questionChoiceTapReportsIndex`; `problemCardOffersTryAgain`.
- [ ] **Step 2: Run** `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.misura.phone.screen.ScreenPiecesTest --console=plain` → FAIL.
- [ ] **Step 3: Implement**; render a preview screen and screenshot (`adb exec-out screencap -p`), compare with `design-system.html` Working frame side by side; adjust until it matches.
- [ ] **Step 4: Run** the full suite → PASS (`CommitTapTest`, `ExperimentFlowTest` still green with the new orange).
- [ ] **Step 5: Commit** `git commit -m "Misura: one orange, screen type roles, step stream pieces"`

### Task 7: Place carousel, reactive map, photos, place detail

**Files:**
- Create: `app/src/main/kotlin/com/misura/phone/screen/PlaceCards.kt` (`PlaceCarousel`, `PlaceCard`, `PlaceDetail`)
- Create: `app/src/main/kotlin/com/misura/phone/screen/PlaceMap.kt` (moved from `SpecCard.kt`, made reactive)
- Create: `app/src/main/kotlin/com/misura/phone/brain/Photos.kt`
- Test: `app/src/androidTest/kotlin/com/misura/phone/screen/PlaceCardsTest.kt`

**Interfaces:**
- Consumes: `PlaceFact`, `Photo`, `PlaceFact.stale()` (Task 3), tokens (Task 6), `googleHeaders` + new `httpBytes` in `brain/Vertex.kt`, `TokenProvider`.
- Produces:
  ```kotlin
  @Composable fun PlaceCarousel(places: List<PlaceFact>, pick: String?, index: Int, onIndex: (Int) -> Unit, onOpen: (String) -> Unit)
  @Composable fun PlaceMap(places: List<PlaceFact>, selected: String?, full: Boolean, onTap: () -> Unit)   // hidden by caller when no place has lat/lng
  @Composable fun PlaceDetail(p: PlaceFact)        // mosaic, title + ★ rating (count), price "€€", hours today, week expandable, Directions (Secondary → mapsUri)
  suspend fun httpBytes(url: String, headers: Map<String, String>, timeoutMs: Int = 30_000): Pair<Int, ByteArray>   // in Vertex.kt, same cancellation contract as http()
  class Photos(tokens: TokenProvider, placesBase: String = "https://places.googleapis.com/v1") {
      suspend fun bitmap(placeId: String, photo: Photo, widthPx: Int): ImageBitmap? }
  // GET {placesBase}/{photo.name}/media?maxWidthPx=…&skipHttpRedirect=true → photoUri → httpBytes → decode. Names expire: on 4xx, refetch
  // details (mask `photos`) for placeId once and use the same index. In-memory LruCache only; null on any other failure. MisuraGraph holds one Photos.
  ```
- Attribution: under the mosaic, `label` style text-2 "Photo: <author>" for each shown photo's author (Places terms), tappable to `authorUri` when present.
- Map: an `AndroidView` whose `update` block re-applies pins and colours whenever `places` or `selected` changes; changes that arrive before the style is loaded are applied once it loads. Semantics: one custom action "Select <name>" per place (calls the same select path as a swipe) and `stateDescription` = selected place name. Pins selected pin orange, others paper; camera fits all pins (single pin zoom 15). Carousel swipe calls `onIndex`, which the caller maps to `selected`. Accessible actions on carousel: "Next place", "Previous place", "Open". PICK tag on `pick`. Missing photos → no mosaic; missing rating/price → omitted.

- [ ] **Step 1: Write failing emulator tests**: `swipeChangesMapSelection` (map `stateDescription` follows the carousel); `mapOffersSelectActionsPerPlace`; `photoAttributionShown`; `carouselShowsPickTagOnPick`; `swipeChangesIndex`; `carouselHasNextPreviousActions`; `tapOpensDetail`; `carouselWithoutCoordinatesHidesMap` (caller logic via a small `@Composable fun DecisionScreen` in `PlaceCards.kt`); `cardWithoutPhotosHasNoMosaic`; `detailShowsRatingAndCount` ("4.6 (812)").
- [ ] **Step 2: Run** the class → FAIL.
- [ ] **Step 3: Implement**; add `Photos` unit test `expiredNameRefetchesOnce` (TinyHttp: media 400 → details → media 200 PNG → non-null bitmap) in `app/src/test/.../brain/PhotosTest.kt`; screenshot Decision and Detail from the test host with fixture facts, compare side by side with `design-system.html`; adjust. (Live screenshots come in Task 9.)
- [ ] **Step 4: Run** the full suite → PASS.
- [ ] **Step 5: Commit** `git commit -m "Misura: place carousel, live map and place detail"`

### Task 8: Nav stack and home lines (pure logic)

**Files:**
- Create: `core/src/main/kotlin/com/misura/core/agent/Nav.kt`
- Test: `core/src/test/kotlin/com/misura/core/agent/NavTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface Screen { data object Root : Screen; data class Detail(val placeId: String) : Screen; data object FullMap : Screen; data object History : Screen }
  data class Nav(val stack: List<Screen> = listOf(Screen.Root), val index: Int = 0) {   // immutable: the UI holds it in mutableStateOf
      val top: Screen; fun open(s: Screen): Nav; fun back(): Nav? /* null = at root → go home */; fun select(i: Int): Nav; fun reset(): Nav }
  fun homeLine(s: Session, asked: String): String   // "<asked> · 6 places" | "<asked> · <answer line, 40 chars>" | "<asked> · Failed" | "<asked> · Working"
  fun backLabel(s: Session, facts: Map<String, PlaceFact>, nav: Nav): String?   // Detail → "‹ 6 places"; Root → null
  ```

- [ ] **Step 1: Write failing tests**: `backFromDetailReturnsToRootKeepingIndex`; `backAtRootReturnsNull`; `resetClearsStack`; `homeLineForPlaces`, `homeLineForFailed`, `homeLineForWorking`; `backLabelCountsPlaces`.
- [ ] **Step 2: Run** `./gradlew :core:test --tests '*NavTest*' --console=plain` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `git commit -m "Misura: nav stack and home lines"`

### Task 9: HomeActivity: home, task screens, docked card, back

**Files:**
- Modify: `app/src/main/kotlin/com/misura/phone/HomeActivity.kt` (rewrite)
- Create: `app/src/main/kotlin/com/misura/phone/screen/Docked.kt`
- Modify: `app/src/androidTest/kotlin/com/misura/phone/store/SessionStoreTest.kt`
- Test: `app/src/androidTest/kotlin/com/misura/phone/HomeFlowTest.kt` (scripted `Brain` + fake `find_places` injected via a test hook on `MisuraGraph`)

**Interfaces:**
- Consumes: everything above. `MisuraGraph` gains `fun agentForTest(brain: Brain, tools: List<Tool>)` (debug/test only) replacing `agent`.
- Precedence: `nav.top` picks the main area (Root → Decision if `places` else Echo/steps/answer; Detail; FullMap; History). A `say` Question/Problem card always renders at the bottom over whichever main area is showing (it never navigates away from Detail); a `say` Answer renders as screen text above the carousel or under the detail title. Steps of a running turn show above the docked card on any main area.
- Notification and restore: keep today's contract — `onNewIntent`/launch `"session"` extra opens that task (nav reset), and the open session id + `Nav` survive recreation via saved state.
- Behaviour: no open task → Home (clock, `homeLine` rows for `recent(5)`, rows tappable to open the task — flagged proposal). Open task: `NavLine` + by state: THINKING → Echo + `Steps` (current turn) with docked card if a place card was in front; `places` → Decision (map + `AnswerText(say)` + carousel); `Detail` → `PlaceDetail`; `say` Question/Problem → card at the bottom; Answer without places → Echo + ✓ steps + `AnswerText`. "n steps ›" → History (all steps grouped by turn). System back → `nav.back()`; false → Home. Field focus captures `Target` (session, revision, place in front) → `Docked` card at the bottom (thumb, label, name; ✕ clears target); submit → `followUp(id, text, lookingAt)` or `start(text)` when no target. Send disabled while THINKING. Sessions with `now - createdAt > 30 days` render `PlaceFact.stale()` (no map, photos, rating, price) and replace a `say` Answer with "Older answer. Ask again for current details." (old answers can carry old hours). Layout frozen while a pointer is down on a card or a card has accessibility focus (no reordering or replacement of the carousel list; updates apply on release/focus loss).

- [ ] **Step 1: Write failing emulator tests**: `runOneToDecision` (scripted: find_places → show_places with pick → Decision shows map, 3 cards, PICK; steps ✓ "3 places found"); `tapPlaceOpensDetailAndBackRestoresIndex`; `backAtRootGoesHome`; `homeListsTheTask`; `focusingFieldDocksFrontCardAndFollowUpCarriesPlace` (scripted brain sees "(Looking at: …)"); `answerKeepsCarousel`; `problemTryAgainResends`; `oldPlacesRenderStale` (session createdAt 31 days ago → no map, names + why only, answer replaced); `askAfterShowPlacesShowsQuestionOverCarousel`; `failedFollowUpShowsProblemOverDetail`; `notificationOpensItsSession`; `recreateKeepsOpenTaskAndDetail`; `oldRowSurvivesUpgrade` (insert a `SessionRow` with the current-format JSON `"card":{"type":"text","line":"Hi"}` → Home shows "· Hi", opening shows it).
- [ ] **Step 2: Run** the class → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the full suite → PASS. Then live: push token (`scripts/push-token.sh`), run Run 1 to the decision on the emulator, screenshot each screen, compare with `design-system.html`.
- [ ] **Step 5: Commit** `git commit -m "Misura: home, step stream, decision cards and back on the emulator"`

### Task 10: Live eval, docs, republish, backup

**Files:**
- Modify: `app/src/test/kotlin/com/misura/phone/brain/LiveEval.kt`
- Modify: `docs/SYSTEM.md`, `docs/design/design-system.md` (§11 open questions), `docs/demo-script.md` (M2 progress)

**Interfaces:**
- Consumes: real `VertexBrain` + `FindPlaces`.

- [ ] **Step 1: Update LiveEval**: `bibimbapShowsPlacesWithAPick` (session ends with `places` of ≥ 3 ids, `pick` in them, steps DONE with a "found" summary); `hoursFollowUpNeedsNoSecondSearch` (follow-up with `lookingAt` → `say` Answer, no new `find_places` step).
- [ ] **Step 2: Run** `MISURA_TOKEN=$(gcloud auth application-default print-access-token) ./gradlew :app:testDebugUnitTest --tests '*LiveEval*' --console=plain` → PASS (needs manual mode for the token).
- [ ] **Step 3: Update SYSTEM.md**: §2 rule 4 (back at root = home); §4 tools (`find_places` with rating/price/photos, `show_places`, `ask`, `answer`; `show_card`/`finish` gone; remove "decided, not built" for what shipped; keep approve/*will* as not built); §5 mark 2a done; §6 decision log entries for 11 Nov calls (answers keep the cards, reason = tool `why`, 30-day render hide, home rows tappable = proposal pending his yes, walk time cut); §7 remove "back at root" and "readable step labels"; §8 index: this spec Current, brain spec §3.3 superseded. design-system.md §11 same removals.
- [ ] **Step 4: Republish**: `python3 scripts/publish-system.py`, publish `build/system.html` with `design-spec.html`, `design-system.html`, `flow-mockup.html`.
- [ ] **Step 5: Commit**: `git commit -m "Misura: SYSTEM.md for the step-stream refactor"`.
