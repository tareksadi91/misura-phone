package com.misura.phone.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.misura.phone.BuildConfig
import com.misura.phone.HomeActivity

internal fun taskNotificationIntent(ctx: Context, taskId: String): Intent =
    (if (BuildConfig.DEBUG) Intent().setClassName(ctx, "com.misura.phone.debug.ExperimentActivity")
    else Intent(ctx, HomeActivity::class.java))
        .setData(Uri.fromParts("misura-task", taskId, null))
        .putExtra("task", taskId)
