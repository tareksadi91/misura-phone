package com.misura.core.shortcut

import com.misura.core.agent.NOTE
import com.misura.core.agent.Step
import com.misura.core.agent.StepState
import com.misura.core.agent.WEB
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** A saved one-tap request on Home. [app] is the package it worked through (its icon), null = line icon only. */
@Serializable
data class Shortcut(
    val id: String, val title: String, val prompt: String, val app: String?, val createdAt: Long, val lastUsedAt: Long? = null,
) {
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

/** The shortcut a new one would replace: null below [Shortcut.MAX], else the least recently used (ties: first kept). */
fun replaces(kept: List<Shortcut>): Shortcut? =
    if (kept.size < Shortcut.MAX) null else kept.minByOrNull { it.lastUsedAt ?: it.createdAt }

/** Every field the Save tap approves, length-prefixed like Routine.digest(), so the tap binds to exactly what the card showed. */
fun Shortcut.digest(replacing: String?): String {
    val bytes = listOf(title, prompt, app, replacing).joinToString("") { "${it?.length ?: -1}:${it.orEmpty()}" }.toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
