package com.misura.phone.notify

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import com.misura.core.agent.NO_RESULT_LINE
import com.misura.core.agent.PICK_NOTE
import com.misura.core.agent.SENT_NOTE
import com.misura.core.agent.Say
import com.misura.core.agent.Session
import com.misura.core.agent.SessionStatus
import com.misura.core.agent.SessionStore
import com.misura.core.agent.Turn
import com.misura.phone.HomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** What a settled session or a finished list tells the user (SYSTEM §5 M3). Navigation only: no notification approves or sends. */
sealed interface Alert {
    val title: String; val line: String
    data class Result(override val title: String, override val line: String) : Alert
    data class NeedsOk(override val line: String) : Alert { override val title get() = "Needs your OK" }
    data class NeedsInput(override val line: String) : Alert { override val title get() = "Needs your input" }
}

/** [allSent] = every message on a finished card went out. [asked] = the user's latest words (the result's title). Null = say nothing. */
fun alertFor(s: Session, asked: String?, allSent: Boolean = false): Alert? {
    val title = asked?.trim()?.takeIf { it.isNotEmpty() } ?: "Misura"
    if (allSent) return Alert.Result(title, "Sent.")
    return when (val say = s.say) {
        // A tool's own stopped tap is an approval: its label ("WhatsApp · FriendA"), never the exact words it would type.
        is Say.Question -> if (say.gate != null) Alert.NeedsOk(say.label ?: "A step is waiting for your OK") else Alert.NeedsInput(say.line)
        is Say.Approve -> Alert.NeedsOk(if (say.also.isEmpty()) "Message to ${say.name}" else "Messages to ${say.also.size + 1} people")
        is Say.Problem -> null // the existing Problems alert speaks for failures
        is Say.Answer -> if (say.line == NO_RESULT_LINE) null else Alert.Result(title, say.line)
        null -> s.places?.placeIds?.size?.takeIf { it > 0 }?.let { Alert.Result(title, "Found $it ${if (it == 1) "place" else "places"}") }
            ?: s.items?.itemIds?.size?.takeIf { it > 0 }?.let { Alert.Result(title, "Found $it ${if (it == 1) "item" else "items"}") }
    }.let { a -> if (a is Alert.Result && a.line.isBlank()) null else a }
}

/** The last thing the user said; a choice tap or a sent-note isn't speech. Same rule as Home's echo. */
fun lastAsked(turns: List<Turn>): String? = turns.lastOrNull { it.role == "user" && texts(it).let { t -> t.isNotEmpty() && PICK_NOTE !in t && SENT_NOTE !in t } }?.let { texts(it).first() }
private fun texts(t: Turn) = (t.content["parts"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content }

object SessionAlerts {
    const val RESULTS = "results"
    const val NEEDS = "needs_you"
    private const val ID = 1
    fun resultTag(sessionId: String) = "result:$sessionId"
    fun needsTag(sessionId: String) = "needs:$sessionId"

    /** HomeActivity resumed and focused. With screen on and unlocked = the user is in Misura: no alert. Written only via [front]. */
    @Volatile var homeInFront = false; private set
    /** Finished cards already announced; onSent can report the last message more than once. ponytail: in memory, a restart never replays. */
    private val announced: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** One alert decision at a time, so an older snapshot never lands after a newer one. */
    private val ordered = Mutex()
    /** Posting and Home's "came in front, clear the shade" never interleave. */
    private val lock = Any()

    /**
     * The Agent's two hooks. Each event is only a nudge: the session is read again here and must still be the one the event
     * saw (same revision and status, not dismissed, not deleted), so a stale event never posts or brings an alert back.
     * [waiting] = every listed send task still waits for its tap (a draft that went out, failed or expired needs nobody).
     */
    fun hooks(ctx: Context, store: SessionStore, scope: CoroutineScope, waiting: suspend (List<String>) -> Boolean = { true }): Pair<(Session) -> Unit, (Session) -> Unit> {
        val app = ctx.applicationContext
        fun later(seen: Session, allSent: Boolean) { scope.launch { ordered.withLock { runCatching {
            // Everything that suspends happens first; the check below and the post run without a gap.
            val asked = lastAsked(store.turns(seen.id))
            val draftWaits = (seen.say as? Say.Approve)?.let { waiting(it.all) } ?: true
            val now = store.load(seen.id)
            if (now == null || now.dismissedAt != null) { show(app, seen.id, null, dropResult = true); return@runCatching } // deleted or dismissed
            if (now.revision != seen.revision || now.status != seen.status || now.say != seen.say) return@runCatching // newer work: its own event follows
            if (allSent && (now.status != SessionStatus.IDLE || !announced.add("${seen.id}:${(seen.say as? Say.Approve)?.all}"))) return@runCatching
            val alert = when {
                now.status == SessionStatus.FAILED -> null
                !allSent && now.say is Say.Approve && !draftWaits -> null // sent, failed or expired since: not "needs your OK"
                else -> alertFor(now, asked, allSent)
            }
            show(app, seen.id, alert)
        }.onFailure { Log.w("Misura", "session alert failed: ${it.javaClass.simpleName}") } } } }
        return { s: Session -> later(s, false) } to { s: Session -> later(s, true) }
    }

    /**
     * A send failed (its Problems alert speaks): the card's "needs your OK" goes, but only while that card still holds the task,
     * and in turn with the hook events, so a decision already under way can't post after this, and a newer card keeps its alert.
     */
    fun sendFailed(ctx: Context, scope: CoroutineScope, taskId: String, sessionFor: suspend (String) -> Session?) {
        val app = ctx.applicationContext
        scope.launch { ordered.withLock { runCatching {
            val s = sessionFor(taskId) ?: return@runCatching
            if ((s.say as? Say.Approve)?.all?.contains(taskId) == true) show(app, s.id, null)
        } } }
    }

    /** Home came in front or left. Coming in clears the shade copies: everything is on Home (open tasks and Earlier). */
    fun front(ctx: Context, inFront: Boolean) = synchronized(lock) {
        if (inFront && !homeInFront) clearAll(ctx)
        homeInFront = inFront
    }

    fun userIsLooking(ctx: Context): Boolean {
        if (!homeInFront) return false
        val interactive = ctx.getSystemService(PowerManager::class.java)?.isInteractive == true
        val locked = ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false
        return interactive && !locked
    }

    /** Replaces whatever this session showed before: a new state cancels a stale "needs you"; [dropResult] also takes its result away. */
    fun show(ctx: Context, sessionId: String, alert: Alert?, dropResult: Boolean = false): Unit = synchronized(lock) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(needsTag(sessionId), ID)
        if (dropResult) nm.cancel(resultTag(sessionId), ID)
        if (alert == null || userIsLooking(ctx)) return
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED || !nm.areNotificationsEnabled()) return
        val needs = alert !is Alert.Result
        val channel = if (needs) NEEDS else RESULTS
        // Existing channel ids keep the user's settings; creating again only renames.
        nm.createNotificationChannel(if (needs) NotificationChannel(NEEDS, "Needs you", NotificationManager.IMPORTANCE_HIGH)
            else NotificationChannel(RESULTS, "Results", NotificationManager.IMPORTANCE_DEFAULT))
        val icon = if (needs) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_dialog_info
        // The lock screen's version when content is hidden there: no request, answer or names.
        val public = Notification.Builder(ctx, channel).setSmallIcon(icon)
            .setContentTitle(if (needs) "Misura needs you" else "Result ready").setContentText("Open Misura to view").build()
        val n = Notification.Builder(ctx, channel).setSmallIcon(icon)
            .setContentTitle(alert.title).setContentText(alert.line.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(alert.line.take(600)))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setCategory(if (needs) Notification.CATEGORY_REMINDER else Notification.CATEGORY_STATUS)
            .setContentIntent(open(ctx, sessionId)).setAutoCancel(true).build()
        try { nm.notify(if (needs) needsTag(sessionId) else resultTag(sessionId), ID, n) }
        catch (e: SecurityException) { Log.w("Misura", "session alert refused") }
    }

    /** Opens Home on that session. Its own data URI, so two sessions never share one PendingIntent. */
    fun open(ctx: Context, sessionId: String): PendingIntent = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, HomeActivity::class.java).setData(Uri.fromParts("misura-session", sessionId, null)).putExtra("session", sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The user came into Misura: everything is on Home (open tasks and Earlier), so the shade copies go. */
    fun clearAll(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.activeNotifications.filter { it.tag?.startsWith("result:") == true || it.tag?.startsWith("needs:") == true }.forEach { nm.cancel(it.tag, it.id) } }
    }
}
