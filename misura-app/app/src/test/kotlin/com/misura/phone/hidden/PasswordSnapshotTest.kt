package com.misura.phone.hidden

import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PasswordSnapshotTest {
    @Test fun passwordTextAndDescriptionNeverReachModelSnapshot() {
        val node = AccessibilityNodeInfo.obtain().apply {
            text = "private-value"
            contentDescription = "private-description"
            isEditable = true
            isClickable = true
            isPassword = true
        }
        val snapshot = ScreenReader.snapshot(node, 0)
        assertNull(snapshot.text)
        assertNull(snapshot.desc)
        assertFalse(snapshot.editable)
        assertFalse(snapshot.clickable)
        assertTrue(snapshot.children.isEmpty())
    }
}
