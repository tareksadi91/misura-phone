package com.misura.phone.notify

import android.content.Context

/** All packages are readable unless explicitly blocked. Old opt-in preferences do not restrict capture. */
class NotificationAccess(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("notification_blocks", Context.MODE_PRIVATE)

    fun allows(pkg: String) = !prefs.getBoolean(pkg, false)

    fun blockedPackages(): Set<String> = prefs.all.filterValues { it == true }.keys

    fun setBlocked(pkg: String, blocked: Boolean) {
        require(pkg == "android" || pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "invalid package" }
        val edit = prefs.edit()
        if (blocked) edit.putBoolean(pkg, true) else edit.remove(pkg)
        check(edit.commit()) { "notification preference not saved" }
    }
}
