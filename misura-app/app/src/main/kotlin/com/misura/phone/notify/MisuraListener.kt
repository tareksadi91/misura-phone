package com.misura.phone.notify

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.misura.phone.access.AppAccess

/** Reads notifications unless their exact package is blocked here or protected from the agent ([AppAccess]). */
class MisuraListener : NotificationListenerService() {
    private val access by lazy { NotificationAccess(this) }
    override fun onListenerConnected() {
        AppAccess.init(this)
        instance = this
        runCatching { ProbeLog.clean(this) }
        // While the system holds this binding, a foreground service start is allowed; after boot it is the reliable moment.
        if (com.misura.phone.sleep.SleepMode.get(this) != com.misura.phone.sleep.Variant.OFF) com.misura.phone.work.WorkingService.ensure(this)
    }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || !access.allows(sbn.packageName) || !AppAccess.allows(sbn.packageName)) return
        val c = runCatching { extract(sbn) }.getOrNull() ?: return
        runCatching { ProbeLog.append(this, sbn.packageName, c) }
        onCaptured?.invoke(sbn, c)
    }

    fun chatsAll(): List<Chat> = liveChats().flatMap { (pkg, captures) ->
        chatsIn(captures, nameKeyPrefix = if (pkg == "com.misura.testapp") null else "$pkg:")
    }
    fun chatPackages(): List<String> = liveChats().keys.toList()
    private fun liveChats(): Map<String, List<Captured>> = runCatching { activeNotifications.toList() }.getOrDefault(emptyList())
        .filter { it.packageName != packageName && access.allows(it.packageName) && AppAccess.allows(it.packageName) }
        .mapNotNull { sbn -> runCatching { extract(sbn) }.getOrNull()?.takeIf { it.isChatNotification() }?.let { sbn.packageName to it } }
        .groupBy({ it.first }, { it.second })

    // shortcut: only testapp may use title-keyed chats; support other apps when their ids can be matched safely.
    fun chats(pkg: String): List<Chat> = chatsIn(liveFrom(pkg).mapNotNull { runCatching { extract(it) }.getOrNull() }, nameKeyPrefix = if (pkg == "com.misura.testapp") null else "$pkg:")
    fun liveFrom(pkg: String): List<StatusBarNotification> =
        if (pkg == packageName || !access.allows(pkg) || !AppAccess.allows(pkg)) emptyList()
        else runCatching { activeNotifications.filter { it.packageName == pkg } }.getOrDefault(emptyList())

    companion object {
        @Volatile var instance: MisuraListener? = null
        /** Set by the app graph (Task 7/10) to feed the router. */
        @Volatile var onCaptured: ((StatusBarNotification, Captured) -> Unit)? = null
    }
}
