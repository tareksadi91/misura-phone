package com.misura.core.shortcut

import com.misura.core.agent.NOTE
import com.misura.core.agent.Step
import com.misura.core.agent.StepState
import com.misura.core.agent.WEB
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Settings pages a shortcut can open; the app module maps each to its Settings action. [APP_DETAILS] needs a subject package. */
@Serializable
enum class SettingsPage { WIFI, BLUETOOTH, BATTERY_SAVER, DISPLAY, SOUND, LOCATION, NOTIFICATIONS, APP_DETAILS }

/** Reviewed link templates only: the URI is built from a fixed template and a code-validated param, never model-authored. */
@Serializable
enum class LinkTemplate { MAPS_ROUTE, SPOTIFY_PAGE }

private val SPOTIFY_PARAM = Regex("(playlist|album|artist):[A-Za-z0-9]{22}")
private val SPOTIFY_LINK = Regex("https://open\\.spotify\\.com/(playlist|album|artist)/([A-Za-z0-9]{22})(\\?[^#\\s]*)?")

/** The URI for [param], or null when the param fails this template's validation. MAPS_ROUTE: exactly `Home` or `Work`; SPOTIFY_PAGE: `<playlist|album|artist>:<22 base62>`, case kept. */
fun LinkTemplate.uri(param: String): String? = when (this) {
    LinkTemplate.MAPS_ROUTE -> if (param == "Home" || param == "Work") "https://www.google.com/maps/dir/?api=1&destination=$param" else null
    LinkTemplate.SPOTIFY_PAGE -> if (SPOTIFY_PARAM.matches(param)) "spotify:$param" else null
}

/** A link the user gave, accepted only as `https://open.spotify.com/(playlist|album|artist)/<22 base62>` (optional `?query` ignored; no userinfo, port, fragment or extra path). */
fun parseSpotifyLink(text: String): Target.Link? =
    SPOTIFY_LINK.matchEntire(text)?.let { Target.Link(LinkTemplate.SPOTIFY_PAGE, "${it.groupValues[1]}:${it.groupValues[2]}") }

/** Where a tap goes directly. All code-resolved, never model-authored strings. */
@Serializable
sealed interface Target {
    @Serializable @SerialName("app") data class App(val pkg: String) : Target
    @Serializable @SerialName("shortcut") data class AppShortcut(val pkg: String, val id: String) : Target
    @Serializable @SerialName("settings") data class Settings(val page: SettingsPage, val subjectPackage: String? = null) : Target {
        init {
            if (page == SettingsPage.APP_DETAILS) require(!subjectPackage.isNullOrEmpty()) { "APP_DETAILS needs a subjectPackage" }
            else require(subjectPackage == null) { "$page takes no subjectPackage" }
        }
    }
    @Serializable @SerialName("link") data class Link(val template: LinkTemplate, val param: String) : Target
}

/** The package whose icon a target shows; Settings has none. */
val Target.pkg: String? get() = when (this) {
    is Target.App -> pkg
    is Target.AppShortcut -> pkg
    is Target.Settings -> null
    is Target.Link -> when (template) { LinkTemplate.MAPS_ROUTE -> "com.google.android.apps.maps"; LinkTemplate.SPOTIFY_PAGE -> "com.spotify.music" }
}

private fun esc(s: String?) = s.orEmpty().replace("\\", "\\\\").replace("|", "\\|")

/** Canonical `kind|pkg|id|page|subject|template|param` (fields escaped, so no field can forge another): equality, duplicates and the digest. */
fun Target.key(): String = when (this) {
    is Target.App -> listOf("app", pkg, "", "", "", "", "")
    is Target.AppShortcut -> listOf("shortcut", pkg, id, "", "", "", "")
    is Target.Settings -> listOf("settings", "", "", page.name, subjectPackage ?: "", "", "")
    is Target.Link -> listOf("link", "", "", "", "", template.name, param)
}.joinToString("|") { esc(it) }

/**
 * A saved one-tap shortcut on Home: an optional direct [target] and/or optional saved words ([prompt]), at least one.
 * [app] is the package whose icon it shows. With a target it is DERIVED from it ([Target.pkg]; Settings has none) and any
 * supplied value is ignored, so `copy(target = ...)` can never leave a stale icon; without a target the supplied value
 * (a prompt-only record's worked app, legacy JSON included) is kept.
 */
@Serializable
data class Shortcut(
    val id: String, val title: String, val prompt: String?,
    @SerialName("app") private val suppliedApp: String?,
    val target: Target? = null, val createdAt: Long, val lastUsedAt: Long? = null,
) {
    init { require(prompt != null || target != null) { "a shortcut needs saved words or a target" } }

    val app: String? get() = if (target != null) target.pkg else suppliedApp

    companion object {
        const val MAX = 3
        const val LINE_BUDGET = 4
        const val MAX_TITLE = 60
        const val MAX_PROMPT = 500
    }
}

/** True for a real app package; [WEB] and [NOTE] stand in for work outside any app. */
fun isAppEvidence(app: String?): Boolean = app != null && app != WEB && app != NOTE

/** The app of the last DONE step with app evidence, or null if none or any later step FAILED. Only DONE counts as worked and FAILED as failed; WILL, RUNNING etc. are neither. */
fun workedSteps(steps: List<Step>): String? {
    val i = steps.indexOfLast { it.state == StepState.DONE && isAppEvidence(it.app) }
    if (i < 0) return null
    if (steps.drop(i + 1).any { it.state == StepState.FAILED }) return null
    return steps[i].app
}

/** How many Home lines each kind gets; [running] is one grouped line. */
data class HomeFit(val yours: Int, val failed: Int, val running: Boolean, val shortcuts: Int)

/** Fills min([slots], LINE_BUDGET) lines in priority yours, failed, running (one line), shortcuts (at most [Shortcut.MAX]). */
fun homeFit(yours: Int, failed: Int, running: Int, kept: Int, slots: Int): HomeFit {
    var left = minOf(slots, Shortcut.LINE_BUDGET).coerceAtLeast(0)
    val y = minOf(yours.coerceAtLeast(0), left); left -= y
    val f = minOf(failed.coerceAtLeast(0), left); left -= f
    val r = running > 0 && left > 0; if (r) left -= 1
    val s = minOf(kept.coerceAtLeast(0), Shortcut.MAX, left)
    return HomeFit(y, f, r, s)
}

/** True when Home already keeps [Shortcut.MAX]: a new one is refused until one is removed (no replacement). */
fun isFull(kept: List<Shortcut>): Boolean = kept.size >= Shortcut.MAX

/** Every field the Save tap approves, length-prefixed like Routine.digest(), so the tap binds to exactly what the card showed (including the destination and labels it displayed). */
fun Shortcut.digest(replacing: String?, shownApp: String?, shownShortcut: String?): String {
    val bytes = listOf(title, prompt, app, target?.key(), shownApp, shownShortcut, replacing).joinToString("") { "${it?.length ?: -1}:${it.orEmpty()}" }.toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
