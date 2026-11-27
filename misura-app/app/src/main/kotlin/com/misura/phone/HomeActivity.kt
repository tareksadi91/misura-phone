package com.misura.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home is the agent. A notification for a task opens that task (and again on every new tap). */
class HomeActivity : ComponentActivity() {
    private var request by mutableStateOf<OpenRequest?>(null)
    /** Bumped by the system home gesture: Misura is the launcher, so "home" means close the task, show the clock. */
    private var homeTap by mutableStateOf(0L)
    private val navigation by lazy {
        HomeIntentRouter(lifecycleScope, { task ->
            withContext(Dispatchers.IO) { MisuraGraph.sessions.sessionForTask(task)?.id }
        }) { session ->
            if (session != null) request = OpenRequest(session, System.nanoTime())
            else { request = null; homeTap = System.nanoTime() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        navigation.follow(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore unfinished lookups; completed navigation belongs to the saved screen state.
        val pendingTask = savedInstanceState?.getString("pending-task")
        if (pendingTask != null) navigation.follow(Intent().putExtra("task", pendingTask))
        else if (savedInstanceState == null) navigation.follow(intent)
        if (savedInstanceState == null && !com.misura.phone.setup.CredentialSettings(getSharedPreferences("brain", MODE_PRIVATE)).configured()) {
            startActivity(Intent(this, com.misura.phone.setup.SetupActivity::class.java))
        }
        val g = MisuraGraph
        setContent {
            HomeScreen(g.agent, g.sessions, g.photoLoader, request, Sends(g.tasks, g.controller, g.scope), homeTap,
                com.misura.phone.voice.AndroidVoice(this), appsProvider = { g.apps }, shortcutStore = g.shortcuts, shortcutKeeper = g.shortcutKeeper,
                sheetHome = com.misura.phone.screen.SheetHome(g.shortcutKeeper, g.shortcuts, g.shortcutTargets),
                openTarget = { t -> runCatching { g.openTarget(this@HomeActivity, t) }.getOrDefault(false) },
                onOpenSettings = { startActivity(Intent(this, com.misura.phone.setup.SetupActivity::class.java)) }) { pkg ->
                g.scope.launch { g.openTarget(this@HomeActivity, com.misura.core.shortcut.Target.App(pkg)) }
            }
        }
    }

    // In front = resumed and focused: alerts stay quiet and the shade copies go (everything is on Home).
    private var resumed = false
    // Location simply there when a near-me request comes (owner 26 Nov): one background fix, held in memory, when Misura comes to the front.
    override fun onResume() { super.onResume(); resumed = true; front(hasWindowFocus()); com.misura.phone.brain.PhoneLocation.warm(this, MisuraGraph.scope) }
    override fun onPause() { resumed = false; front(false); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); front(hasFocus) }
    private fun front(focused: Boolean) = com.misura.phone.notify.SessionAlerts.front(this, resumed && focused)

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending-task", navigation.pendingTask)
        super.onSaveInstanceState(outState)
    }
}
