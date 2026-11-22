package com.misura.phone.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidVoiceTest {
    @Test fun onlyFirstAttemptLanguageErrorsPersistFallbackForThatLocale() {
        val oldLocale = Locale.getDefault()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val prefs = activity.getSharedPreferences("voice", Context.MODE_PRIVATE)
        shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(activity.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { packageName = "test"; name = "Recognizer" }
        })
        fun start(): ShadowSpeechRecognizer {
            assertTrue(AndroidVoice(activity).start({}, {}))
            shadowOf(Looper.getMainLooper()).idle()
            return shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        }
        try {
            for (error in listOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_NO_MATCH)) {
                prefs.edit().clear().commit(); Locale.setDefault(Locale.forLanguageTag("en-DE"))
                start().let { assertEquals("en-DE", it.lastRecognizerIntent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)); it.triggerOnRmsChanged(8f); it.triggerOnError(error) }
                start().let { assertEquals("en-US", it.lastRecognizerIntent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)); it.triggerOnResults(Bundle()) }
                Locale.setDefault(Locale.GERMANY)
                start().let { assertEquals("de-DE", it.lastRecognizerIntent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)); it.triggerOnResults(Bundle()) }
            }
            prefs.edit().clear().commit(); Locale.setDefault(Locale.GERMANY)
            start().triggerOnError(SpeechRecognizer.ERROR_NO_MATCH) // first try, nobody spoke: no fallback
            start().let { assertEquals("de-DE", it.lastRecognizerIntent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)); it.triggerOnResults(Bundle()) }
            prefs.edit().clear().commit()
            start().triggerOnError(SpeechRecognizer.ERROR_NETWORK)
            start().triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
            start().let { assertEquals("de-DE", it.lastRecognizerIntent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)); it.triggerOnResults(Bundle()) }
        } finally {
            prefs.edit().clear().commit(); Locale.setDefault(oldLocale); controller.pause().stop().destroy()
        }
    }
}
