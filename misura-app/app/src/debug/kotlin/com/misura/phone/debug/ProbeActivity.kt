package com.misura.phone.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.misura.phone.notify.ProbeLog

/** Debug: the last 50 content-free notification records, newest first. */
class ProbeActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        val lines = ProbeLog.tail(this)
        setContent { LazyColumn(Modifier.padding(12.dp)) { items(lines) { Text(it, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp)) } } }
    }
}
