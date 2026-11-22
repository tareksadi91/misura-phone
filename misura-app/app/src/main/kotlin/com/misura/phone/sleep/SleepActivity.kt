package com.misura.phone.sleep

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.LinearLayout
import android.widget.TextView
import com.misura.phone.MisuraGraph
import com.misura.phone.hidden.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Date

/**
 * The sleep screen draws above the lock without Android's notification privacy policy, so no task-derived line
 * (recipient, state, "needs you") goes on it. Only a content-free Shizuku outage line, since hidden sends stop
 * without it. ponytail: private rows that honor the lock-screen setting come after launch (SYSTEM §5).
 */
fun sleepCards(shizukuUp: Boolean = true): List<String> =
    if (shizukuUp) emptyList() else listOf("Hidden sends paused: Shizuku is off")

/** What is on screen now, for both variants. */
object SleepScreen {
    @Volatile var visible = false; private set
    @Volatile var shownAt = 0L; private set
    /** Every card line the last built screen had (it shows the first four). */
    @Volatile var cards: List<String> = emptyList(); private set
    /** Screens built so far, and the newest one that finished drawing with the lines it drew (tests wait for this wake's screen). */
    @Volatile var built = 0; private set
    @Volatile var drawn: Pair<Int, List<String>> = 0 to emptyList(); private set
    private var activity = WeakReference<Activity>(null)

    internal fun shown(how: String, a: Activity? = null) {
        a?.let { activity = WeakReference(it) }
        shownAt = System.currentTimeMillis(); visible = true
        Log.i("MisuraSleep", "shown via $how")
    }
    internal fun gone() { visible = false }

    fun showActivity(ctx: Context) {
        // Background launch: allowed only under an exception (overlay permission). A denial shows in logcat as "Background activity launch blocked".
        runCatching { ctx.startActivity(Intent(ctx, SleepActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
            .onFailure { if (com.misura.phone.BuildConfig.DEBUG) Log.w("MisuraSleep", "activity launch failed", it) else Log.w("MisuraSleep", "activity launch failed: ${it.javaClass.simpleName}") }
    }
    fun showOverlay(ctx: Context) = SleepOverlay.show(ctx)

    fun hide() {
        activity.get()?.finish(); activity = WeakReference(null)
        SleepOverlay.hide()
        visible = false
    }

    /** The sleep screen itself: dark, the agent as a slow orange pulse, the time, live cards. Tap to reach the system unlock. */
    fun view(ctx: Context, onTap: () -> Unit): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.rgb(0x0E, 0x0F, 0x10)); setPadding(64, 220, 64, 64)
            setOnClickListener { onTap() }
        }
        // ponytail: a pulsing dot stands in for the particle-sun agent until the real one is ported from phone-viewer.js.
        col.addView(View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(0xFF, 0x7A, 0x1A)) }
            layoutParams = LinearLayout.LayoutParams(140, 140).apply { bottomMargin = 64 }
            startAnimation(ScaleAnimation(0.85f, 1.1f, 0.85f, 1.1f, Animation.RELATIVE_TO_SELF, .5f, Animation.RELATIVE_TO_SELF, .5f)
                .apply { duration = 1800; repeatCount = Animation.INFINITE; repeatMode = Animation.REVERSE })
        })
        col.addView(TextView(ctx).apply {
            text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()); textSize = 56f; setTextColor(Color.rgb(0xED, 0xED, 0xED)); gravity = Gravity.CENTER
        })
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 96, 0, 0) }
        col.addView(list)
        MisuraGraph.init(ctx)
        val gen = ++built
        MisuraGraph.scope.launch {
            val lines = runCatching { sleepCards(ShizukuBridge.ready()) }.getOrDefault(emptyList())
            this@SleepScreen.cards = lines
            withContext(Dispatchers.Main) {
                // ponytail: four cards fit one glance; a "more" line comes with the real card system.
                lines.take(4).forEach { line ->
                    list.addView(TextView(ctx).apply {
                        text = line; textSize = 18f; setPadding(40, 32, 40, 32)
                        setTextColor(if (line.startsWith("Needs you")) Color.rgb(0xFF, 0x7A, 0x1A) else Color.rgb(0xC8, 0xC8, 0xC8))
                        background = GradientDrawable().apply { cornerRadius = 36f; setColor(Color.rgb(0x1A, 0x1B, 0x1D)) }
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 24 }
                    })
                }
                drawn = gen to (0 until list.childCount).map { (list.getChildAt(it) as TextView).text.toString() }
            }
        }
        return col
    }
}

/** Variant A: an activity shown over the lock screen. It never turns the screen on itself. */
class SleepActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
    }
    override fun onResume() {
        super.onResume()
        val keyguard = getSystemService(KeyguardManager::class.java)
        // A launch that lands after a fast unlock (or after the mode changed) must not cover the phone.
        if (!keyguard.isKeyguardLocked || SleepMode.get(this) != Variant.ACTIVITY) { finish(); return }
        // Rebuilt on every wake, so time and cards are never from an earlier one.
        setContentView(SleepScreen.view(this) { keyguard.requestDismissKeyguard(this, null) })
        SleepScreen.shown("activity", this)
    }
    override fun onPause() { super.onPause(); SleepScreen.gone() }
}
