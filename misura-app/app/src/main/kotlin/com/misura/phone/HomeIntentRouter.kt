package com.misura.phone

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Notification navigation only. A later tap or home gesture cancels a pending lookup. */
internal class HomeIntentRouter(
    private val scope: CoroutineScope,
    private val sessionForTask: suspend (String) -> String?,
    private val open: (String?) -> Unit,
) {
    private var lookup: Job? = null
    var pendingTask: String? = null
        private set

    fun follow(intent: Intent) {
        lookup?.cancel()
        pendingTask = null
        val session = intent.getStringExtra("session")?.takeIf { it.isNotBlank() && it.length <= 256 }
        val task = intent.getStringExtra("task")?.takeIf { it.isNotBlank() && it.length <= 256 }
        when {
            session != null -> open(session)
            task != null -> {
                pendingTask = task
                lookup = scope.launch {
                    val id = sessionForTask(task)
                    coroutineContext.ensureActive()
                    pendingTask = null
                    open(id)
                }
            }
            intent.hasCategory(Intent.CATEGORY_HOME) -> open(null)
        }
    }
}
