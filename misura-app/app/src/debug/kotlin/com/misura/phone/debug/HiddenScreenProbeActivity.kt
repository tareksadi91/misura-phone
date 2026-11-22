package com.misura.phone.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.misura.phone.hidden.DisplaySession
import com.misura.phone.hidden.ShizukuBridge
import com.misura.phone.hidden.ShizukuHiddenScreen
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/** Debug: grant Shizuku, open a hidden display, launch the test chat on it, close it. */
class HiddenScreenProbeActivity : ComponentActivity() {
    private var status by mutableStateOf("")
    private var session: DisplaySession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = ShizukuHiddenScreen(this)
        fun run(label: String, block: suspend () -> String) = lifecycleScope.launch { status = "$label: " + runCatching { block() }.getOrElse { it.toString() } }
        setContent {
            Column(Modifier.padding(16.dp)) {
                Text("ping=${runCatching { Shizuku.pingBinder() }.getOrDefault(false)} ready=${ShizukuBridge.ready()}")
                Button({ Shizuku.requestPermission(ShizukuBridge.PERMISSION_REQUEST) }) { Text("Grant Shizuku") }
                Button({ run("open") { screen.open(720, 1280, 320).also { session = it }.toString() } }) { Text("Open hidden display") }
                Button({ run("launch") { screen.launch(session!!, "com.misura.testapp").toString() + " on " + screen.taskDisplayOf("com.misura.testapp") } }) { Text("Launch test chat") }
                Button({ run("close") { session?.let { screen.close(it) }; "closed" } }) { Text("Close") }
                Text(status)
            }
        }
    }
}
