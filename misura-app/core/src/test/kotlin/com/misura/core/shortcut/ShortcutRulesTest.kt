package com.misura.core.shortcut

import com.misura.core.agent.NOTE
import com.misura.core.agent.Step
import com.misura.core.agent.StepState
import com.misura.core.agent.WEB
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class ShortcutRulesTest {
    private fun step(state: StepState, app: String?) = Step("s", 0, "x", state, startedAt = 0, app = app)
    private fun sc(id: String, created: Long, used: Long? = null) = Shortcut(id, "t$id", "p$id", "com.example.club", createdAt = created, lastUsedAt = used)

    @Test fun webAndNoteAreNotAppEvidence() {
        isAppEvidence(WEB) shouldBe false
        isAppEvidence(NOTE) shouldBe false
        isAppEvidence(null) shouldBe false
        isAppEvidence("com.example.club") shouldBe true
    }

    @Test fun workedStepsIsLastDoneApp() {
        workedSteps(listOf(step(StepState.DONE, "com.example.maps"), step(StepState.DONE, "com.example.usc"), step(StepState.DONE, NOTE))) shouldBe "com.example.usc"
    }

    @Test fun laterFailureCancels() {
        workedSteps(listOf(step(StepState.DONE, "com.example.usc"), step(StepState.FAILED, null))) shouldBe null
    }

    @Test fun noAppEvidenceIsNull() {
        workedSteps(listOf(step(StepState.DONE, WEB), step(StepState.DONE, NOTE))) shouldBe null
    }

    @Test fun willAndRunningAreNeitherDoneNorFailed() {
        workedSteps(listOf(step(StepState.DONE, "a"), step(StepState.WILL, "b"), step(StepState.RUNNING, "c"))) shouldBe "a"
    }

    @Test fun budgetCapsTasksToo() {
        homeFit(3, 2, 1, 3, 9) shouldBe HomeFit(3, 1, false, 0)
    }

    @Test fun shortcutsFillRemainder() {
        homeFit(1, 0, 0, 3, 9) shouldBe HomeFit(1, 0, false, 3)
    }

    @Test fun measuredSlotsWin() {
        homeFit(0, 0, 0, 3, 2) shouldBe HomeFit(0, 0, false, 2)
    }

    @Test fun runningTakesOneLine() {
        homeFit(1, 0, 3, 3, 9) shouldBe HomeFit(1, 0, true, 2)
    }

    @Test fun isFullAtMax() {
        isFull(listOf(sc("a", 1), sc("b", 2))) shouldBe false
        isFull(listOf(sc("a", 1), sc("b", 2), sc("c", 3))) shouldBe true
    }

    private val club = "com.example.club"
    private val id22 = "37i9dQZF1DXcBWIGoYBM5M"

    @Test fun digestBindsTargetAndShownLabels() {
        val base = Shortcut("a", "t", "p", club, createdAt = 1)
        val d = base.digest(null, "Club", null)
        d shouldBe base.digest(null, "Club", null)
        d shouldNotBe base.digest("b", "Club", null)
        d shouldNotBe base.digest(null, "Other", null)
        d shouldNotBe base.digest(null, "Club", "Book")
        val withTarget = base.copy(target = Target.App(club))
        withTarget.digest(null, "Club", null) shouldNotBe d
        base.copy(target = Target.AppShortcut(club, "x")).digest(null, "Club", null) shouldNotBe withTarget.digest(null, "Club", null)
        // length-prefixed: shifting a boundary between fields changes the digest
        Shortcut("a", "ab", "c", null, createdAt = 1).digest(null, null, null) shouldNotBe Shortcut("a", "a", "bc", null, createdAt = 1).digest(null, null, null)
    }

    @Test fun promptOrTargetRequired() {
        shouldThrow<IllegalArgumentException> { Shortcut("a", "t", null, null, createdAt = 1) }
        Shortcut("a", "t", null, club, target = Target.App(club), createdAt = 1).prompt shouldBe null
        Shortcut("a", "t", "p", null, createdAt = 1).target shouldBe null
    }

    @Test fun appIsDerivedFromTheTarget() {
        val maps = "com.google.android.apps.maps"
        // Construction: supplied value (null, or even a different package) never wins over the target.
        Shortcut("a", "t", null, null, target = Target.App(club), createdAt = 1).app shouldBe club
        Shortcut("a", "t", null, "com.other", target = Target.App(club), createdAt = 1).app shouldBe club
        Shortcut("a", "t", null, null, target = Target.AppShortcut(club, "x"), createdAt = 1).app shouldBe club
        Shortcut("a", "t", null, club, target = Target.Settings(SettingsPage.WIFI), createdAt = 1).app shouldBe null
        Shortcut("a", "t", null, null, target = Target.Settings(SettingsPage.APP_DETAILS, club), createdAt = 1).app shouldBe null
        Shortcut("a", "t", null, null, target = Target.Link(LinkTemplate.MAPS_ROUTE, "Home"), createdAt = 1).app shouldBe maps
        Shortcut("a", "t", null, null, target = Target.Link(LinkTemplate.SPOTIFY_PAGE, "playlist:$id22"), createdAt = 1).app shouldBe "com.spotify.music"
        // Changing the target through copy() re-derives the icon.
        val first = Shortcut("a", "t", "p", club, target = Target.App(club), createdAt = 1)
        first.copy(target = Target.App("com.example.other")).app shouldBe "com.example.other"
        first.copy(target = Target.Settings(SettingsPage.SOUND)).app shouldBe null
        first.copy(target = Target.Link(LinkTemplate.MAPS_ROUTE, "Work")).app shouldBe maps
        // Without a target the supplied app is kept (prompt-only records).
        Shortcut("a", "t", "p", club, createdAt = 1).app shouldBe club
        first.copy(target = null).app shouldBe club
        // A target makes the shortcut's identity the target's: same target, same app regardless of what was supplied.
        Shortcut("a", "t", null, null, target = Target.App(club), createdAt = 1).app shouldBe Shortcut("a", "t", null, "x", target = Target.App(club), createdAt = 1).app
    }

    @Test fun legacyJsonWithoutTargetReads() {
        val json = Json { ignoreUnknownKeys = true }
        val s = json.decodeFromString(Shortcut.serializer(), """{"id":"a","title":"t","prompt":"p","app":"$club","createdAt":5}""")
        s.target shouldBe null
        s.prompt shouldBe "p"
        val round = json.decodeFromString(Shortcut.serializer(), json.encodeToString(Shortcut.serializer(),
            Shortcut("b", "t", null, null, target = Target.Settings(SettingsPage.APP_DETAILS, club), createdAt = 1)))
        round.target shouldBe Target.Settings(SettingsPage.APP_DETAILS, club)
    }

    @Test fun appDetailsNeedsSubject() {
        shouldThrow<IllegalArgumentException> { Target.Settings(SettingsPage.APP_DETAILS) }
        shouldThrow<IllegalArgumentException> { Target.Settings(SettingsPage.APP_DETAILS, "") }
        Target.Settings(SettingsPage.APP_DETAILS, club).subjectPackage shouldBe club
    }

    @Test fun otherPagesRejectSubject() {
        SettingsPage.entries.filter { it != SettingsPage.APP_DETAILS }.forEach { page ->
            shouldThrow<IllegalArgumentException> { Target.Settings(page, club) }
            Target.Settings(page).subjectPackage shouldBe null
        }
    }

    @Test fun targetKeyEquality() {
        Target.App(club).key() shouldBe Target.App(club).key()
        Target.App(club).key() shouldBe "app|$club|||||"
        Target.AppShortcut(club, "x").key() shouldBe "shortcut|$club|x||||"
        Target.Settings(SettingsPage.WIFI).key() shouldBe "settings|||WIFI|||"
        Target.Settings(SettingsPage.APP_DETAILS, club).key() shouldBe "settings|||APP_DETAILS|$club||"
        Target.Link(LinkTemplate.MAPS_ROUTE, "Home").key() shouldBe "link|||||MAPS_ROUTE|Home"
        Target.Link(LinkTemplate.SPOTIFY_PAGE, "playlist:$id22").key() shouldBe "link|||||SPOTIFY_PAGE|playlist:$id22"
        Target.App(club).key() shouldNotBe Target.AppShortcut(club, "").key()
        // separators inside a field cannot forge another target
        Target.AppShortcut("a|b", "c").key() shouldNotBe Target.AppShortcut("a", "b|c").key()
    }

    @Test fun homeAndWorkDiffer() {
        Target.Link(LinkTemplate.MAPS_ROUTE, "Home").key() shouldNotBe Target.Link(LinkTemplate.MAPS_ROUTE, "Work").key()
    }

    @Test fun spotifyKindsAndIdsDiffer() {
        val k = { kind: String, id: String -> Target.Link(LinkTemplate.SPOTIFY_PAGE, "$kind:$id").key() }
        k("playlist", id22) shouldNotBe k("album", id22)
        k("playlist", id22) shouldNotBe k("playlist", id22.dropLast(1) + "N")
        k("playlist", id22) shouldNotBe k("playlist", id22.uppercase()) // case is kept
    }

    @Test fun idsSharingSuffixDiffer() {
        Target.AppShortcut(club, "book").key() shouldNotBe Target.AppShortcut(club, "xbook").key()
        Target.AppShortcut(club, "a").key() shouldNotBe Target.AppShortcut(club, "aa").key()
    }

    @Test fun linkTemplatesBuildExactUris() {
        LinkTemplate.MAPS_ROUTE.uri("Home") shouldBe "https://www.google.com/maps/dir/?api=1&destination=Home"
        LinkTemplate.MAPS_ROUTE.uri("Work") shouldBe "https://www.google.com/maps/dir/?api=1&destination=Work"
        listOf("home", "HOME", " Home", "Home ", "Home&x=1", "", "Elsewhere", "Home/../x").forEach { LinkTemplate.MAPS_ROUTE.uri(it) shouldBe null }
        LinkTemplate.SPOTIFY_PAGE.uri("playlist:$id22") shouldBe "spotify:playlist:$id22"
        LinkTemplate.SPOTIFY_PAGE.uri("album:$id22") shouldBe "spotify:album:$id22"
        LinkTemplate.SPOTIFY_PAGE.uri("artist:$id22") shouldBe "spotify:artist:$id22"
        listOf("track:$id22", "playlist:${id22.dropLast(1)}", "playlist:${id22}x", "playlist:", "playlist:${id22.dropLast(1)}!", "Playlist:$id22", "playlist:$id22:play", "playlist", "").forEach {
            LinkTemplate.SPOTIFY_PAGE.uri(it) shouldBe null
        }
        // MAPS_ROUTE with a Spotify-shaped param and vice versa
        LinkTemplate.MAPS_ROUTE.uri("playlist:$id22") shouldBe null
        LinkTemplate.SPOTIFY_PAGE.uri("Home") shouldBe null
    }

    @Test fun spotifyParserAcceptsPlaylistAlbumArtist() {
        parseSpotifyLink("https://open.spotify.com/playlist/$id22") shouldBe Target.Link(LinkTemplate.SPOTIFY_PAGE, "playlist:$id22")
        parseSpotifyLink("https://open.spotify.com/album/$id22?si=abc123&x=y") shouldBe Target.Link(LinkTemplate.SPOTIFY_PAGE, "album:$id22")
        parseSpotifyLink("https://open.spotify.com/artist/$id22") shouldBe Target.Link(LinkTemplate.SPOTIFY_PAGE, "artist:$id22")
    }

    @Test fun spotifyParserRejectsOtherHostsKindsAndIds() {
        listOf(
            "https://open.spotify.com/playlist/%33${id22.drop(1)}",
            "https://open.spotify.com/playlist/${id22.dropLast(2)}%41",
            "https://open.spotify.com/playl%69st/$id22",
            "https://open.spotify.com/playlist/${id22.dropLast(1)}\u00e9",
            "https://open.spotify.com/playlist/${id22.dropLast(1)}\u0663",
            "https://open.spotify.com/playlist/${id22.dropLast(1)}\uff21",
            "https://open.spotify.com/playlist/\u0661${id22.drop(1)}",
            "http://open.spotify.com/playlist/$id22",
            "https://open.spotify.com.evil.com/playlist/$id22",
            "https://evil.com/open.spotify.com/playlist/$id22",
            "https://user@open.spotify.com/playlist/$id22",
            "https://user:pw@open.spotify.com/playlist/$id22",
            "https://open.spotify.com:443/playlist/$id22",
            "https://open.spotify.com:8080/playlist/$id22",
            "https://play.spotify.com/playlist/$id22",
            "https://spotify.com/playlist/$id22",
            "https://OPEN.spotify.com/playlist/$id22",
            "https://open.spotify.com/track/$id22",
            "https://open.spotify.com/show/$id22",
            "https://open.spotify.com/Playlist/$id22",
            "https://open.spotify.com/playlist/${id22.dropLast(1)}",
            "https://open.spotify.com/playlist/${id22}x",
            "https://open.spotify.com/playlist/${id22.dropLast(1)}_",
            "https://open.spotify.com/playlist/$id22/extra",
            "https://open.spotify.com/playlist/$id22/",
            "https://open.spotify.com/intl-de/playlist/$id22",
            "https://open.spotify.com/user/x/playlist/$id22",
            "https://open.spotify.com/playlist/$id22#frag",
            "https://open.spotify.com/playlist/$id22 ",
            " https://open.spotify.com/playlist/$id22",
            "spotify:playlist:$id22",
            "open.spotify.com/playlist/$id22",
            "see https://open.spotify.com/playlist/$id22",
            "",
        ).forEach { parseSpotifyLink(it) shouldBe null }
    }
}
