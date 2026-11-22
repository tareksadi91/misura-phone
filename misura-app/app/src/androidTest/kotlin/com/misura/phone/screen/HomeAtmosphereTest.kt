package com.misura.phone.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry

class HomeAtmosphereTest {
    @get:Rule val rule = createComposeRule()

    @Test fun disabledMotionKeepsStaticAtmosphere() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val previous = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            rule.mainClock.autoAdvance = false
            val draws = AtomicInteger()
            rule.setContent { HomeAtmosphere(false, { 0f }, false, Modifier.fillMaxSize().drawWithContent { draws.incrementAndGet(); drawContent() }) }
            rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
            val first = draws.get()
            rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
            assertTrue(draws.get() > first)
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            rule.waitUntil(5_000) {
                val before = draws.get()
                rule.mainClock.advanceTimeBy(250); rule.waitForIdle()
                draws.get() == before
            }
            val stopped = draws.get()
            assertTrue(stopped > 0)
            rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
            assertEquals(stopped, draws.get())
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            rule.waitUntil(5_000) {
                rule.mainClock.advanceTimeBy(250); rule.waitForIdle()
                draws.get() > stopped
            }
        } finally {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, previous)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun leavingHomeStopsAtmosphereWorkAndResumeRestartsIt() {
        rule.mainClock.autoAdvance = false
        val draws = AtomicInteger()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HomeAtmosphere(false, { 0f }, false, Modifier.fillMaxSize().drawWithContent { draws.incrementAndGet(); drawContent() })
            }
        }
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        val running = draws.get()
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        assertTrue("Visible home must animate", draws.get() > running)
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        val stopped = draws.get()
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        assertEquals("Background home must stop drawing", stopped, draws.get())
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        assertTrue("Returning home restarts decoration", draws.get() > stopped)
    }
}
