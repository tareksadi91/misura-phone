package com.misura.phone.hands

import android.content.Context
import android.content.Intent
import com.misura.core.agent.Tool
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.schema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Brings [pkg] to the front. True when it has something to launch. */
fun openApp(ctx: Context, pkg: String): Boolean {
    val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    com.misura.phone.store.Pins(ctx).launched(pkg)
    return true
}

/**
 * "Open Instagram": the one case an app comes to the front, because the user asked for exactly that.
 * [bringParked] runs first: an app with a parked hidden screen is moved to the front from there (true) instead of launched, see [com.misura.phone.hands.UseApp.bringParked].
 */
class OpenApp(private val ctx: Context, private val appList: () -> List<Pair<String, String>>, private val bringParked: suspend (String) -> Boolean = { false }) : Tool {
    override val spec = ToolSpec("open_app", "Bring an app to the front so the user can use it themselves. Only when they ask to open an app. app = the package name.",
        schema("app" to "string", required = listOf("app")))
    private fun pkg(args: JsonObject) = (args["app"] as? JsonPrimitive)?.content?.trim()
    override fun label(args: JsonObject) = "Opening ${appList().firstOrNull { it.second == pkg(args) }?.first ?: "the app"}"
    override fun summary(result: JsonObject): String? = null
    override fun appFor(args: JsonObject) = pkg(args)
    override suspend fun run(args: JsonObject): JsonObject = buildJsonObject {
        val p = pkg(args)
        // Before bringParked: a parked screen on a protected app must not come to the front either.
        com.misura.phone.access.AppAccess.refusal(p)?.let { put("error", "$it."); return@buildJsonObject }
        // Any package with a launch screen, not only the list read earlier: a just-installed app opens too.
        val opened = p != null && if (bringParked(p)) {
            com.misura.phone.store.Pins(ctx).launched(p)
            true
        } else {
            // bringParked can wait for the operation lock; the owner may have blocked the app meanwhile.
            com.misura.phone.access.AppAccess.refusal(p)?.let { put("error", "$it."); return@buildJsonObject }
            openApp(ctx, p)
        }
        if (opened) put("ok", true) else put("error", "no such app")
    }
}
