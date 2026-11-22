package com.misura.phone.hidden

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/** Binds the shell-user service through Shizuku. One service, one connection per process. */
object ShizukuBridge {
    const val PERMISSION_REQUEST = 4242

    @Volatile private var service: IShellService? = null
    private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var binds = 0

    /** Called when the service process goes away for any reason. */
    @Volatile var onDeath: () -> Unit = {}

    fun ready(): Boolean = runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun alive(): Boolean = service?.asBinder()?.isBinderAlive == true

    suspend fun bind(ctx: Context): IShellService {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        check(ready()) { "Shizuku not running or not granted" }
        val pkg = ctx.packageName
        // A reinstall changes lastUpdateTime, so Shizuku restarts the service with the new code.
        val version = ctx.packageManager.getPackageInfo(pkg, 0).lastUpdateTime.toInt()
        val a = Shizuku.UserServiceArgs(ComponentName(pkg, ShellService::class.java.name))
            .daemon(false).processNameSuffix("shell").debuggable(false).version(version)
            // Fresh tag per bind: the Shizuku client never reconnects a tag it unbound with remove=true.
            .tag("shell-" + binds++)
        return withTimeout(10_000) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val s = IShellService.Stub.asInterface(binder)
                        service = s
                        binder?.linkToDeath({ dropped() }, 0)
                        if (cont.isActive) cont.resume(s)
                    }
                    override fun onServiceDisconnected(name: ComponentName?) = dropped()
                }
                connection = conn; args = a
                Shizuku.bindUserService(a, conn)
            }
        }
    }

    /** Explicit unbind; `remove = true` stops the service process and releases its displays. */
    fun unbind() {
        val a = args; val c = connection
        if (a != null && c != null) runCatching { Shizuku.unbindUserService(a, c, true) }
        service = null; connection = null; args = null
    }

    private fun dropped() {
        service = null
        onDeath()
    }
}
