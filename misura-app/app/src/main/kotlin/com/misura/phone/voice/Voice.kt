package com.misura.phone.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.Locale

/** Hold-to-talk. [start] returns false when it can't listen (no permission yet, no recogniser); then nothing is heard. */
interface Voice {
    /** [onPartial] = words so far; [onFinal] = what was said, or null when nothing was understood. Called once. */
    fun start(onPartial: (String) -> Unit, onFinal: (String?) -> Unit): Boolean
    /** Letting go: stop listening; the words still arrive through onFinal. */
    fun stop()
    /** How loud the voice is right now, 0 (quiet) to 1; the Listening sign's bars follow it. A voice that can't tell stays at 0. */
    val level: Float get() = 0f
}

/**
 * Android's built-in recogniser. Must be called on the main thread.
 * ponytail: one locale, with en-US after a first-attempt language/no-match error; add language selection if switching languages matters.
 */
class AndroidVoice(private val activity: Activity) : Voice {
    private var rec: SpeechRecognizer? = null
    override var level by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set
    private var lo = Float.MAX_VALUE; private var hi = -Float.MAX_VALUE

    override fun start(onPartial: (String) -> Unit, onFinal: (String?) -> Unit): Boolean {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) // the first hold asks; the next one listens
            return false
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) return false
        rec?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(activity).also { rec = it }
        val prefs = activity.getSharedPreferences("voice", Context.MODE_PRIVATE)
        val locale = Locale.getDefault().toLanguageTag()
        val language = if (prefs.getBoolean("failed:$locale", false)) "en-US" else locale
        val first = language == locale && !prefs.getBoolean("tried:$locale", false)
        if (first) prefs.edit().putBoolean("tried:$locale", true).apply()
        var done = false
        fun finish(words: String?) { if (done) return; done = true; level = 0f
            if (hi > lo) android.util.Log.i("MisuraVoice", "level range %.1f..%.1f dB".format(lo, hi)) // for tuning QUIET_DB / LOUD_DB on a new phone; never the words
            lo = Float.MAX_VALUE; hi = -Float.MAX_VALUE
            r.destroy(); if (rec === r) rec = null; onFinal(words?.trim()?.takeIf { it.isNotEmpty() }) }
        fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) { best(b)?.takeIf { it.isNotBlank() }?.let(onPartial) }
            override fun onResults(b: Bundle?) = finish(best(b))
            override fun onError(error: Int) {
                if (done) return
                // No match counts only when a voice was heard: a first try where nobody spoke must not lock a phone out of its own language.
                // (The author's en-DE phone: no speech pack, so a clear voice came back as error 7.)
                val spoke = hi >= LOUD_DB / 2
                if (first && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || (error == SpeechRecognizer.ERROR_NO_MATCH && spoke))) {
                    prefs.edit().putBoolean("failed:$locale", true).apply()
                    android.util.Log.i("MisuraVoice", "$locale failed with error $error; using en-US from now on")
                }
                android.util.Log.i("MisuraVoice", "heard nothing: recogniser error $error"); finish(null)
            }
            override fun onReadyForSpeech(p: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(v: Float) { if (v < lo) lo = v; if (v > hi) hi = v; level = ((v - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(t: Int, p: Bundle?) = Unit
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        return true
    }

    override fun stop() { rec?.stopListening() }

    private companion object {
        // calibration: what the recogniser reports for silence and for a clear voice at arm's length. Google's recogniser gives
        // roughly -2 (quiet room) to 10 (speaking); another phone or recogniser = other numbers (see the MisuraVoice log line).
        const val QUIET_DB = 0f; const val LOUD_DB = 9f
    }
}

/**
 * One hold of the talk button. Nothing is asked before the finger lifts: the recogniser stops by itself at a pause,
 * so its words are kept and listening starts again while the button is still down.
 * [onWords] = everything heard so far (shown in the field); [onSubmit] = the whole thing, once, on release.
 */
class HoldToTalk(private val voice: Voice, private val onWords: (String) -> Unit, private val onListening: (Boolean) -> Unit, private val onSubmit: (String) -> Unit) {
    private var held = false
    private var listening = false
    private var heard = ""

    fun down() { held = true; dropped = false; heard = ""; listen() }
    fun up() { held = false; if (listening) voice.stop() else finish() }
    /** The screen went away mid-hold: nothing heard is asked or approved. Does nothing when no hold is running. */
    fun cancel() {
        if (!held && !listening) return
        held = false; dropped = true
        if (listening) voice.stop() else drop()
    }

    private var dropped = false
    private fun listen() {
        set(voice.start({ if (!dropped) onWords("$heard $it".trim()) }) { said ->
            if (dropped) return@start drop()
            if (said != null) heard = "$heard $said".trim()
            when {
                !held -> finish()
                said != null -> listen() // a pause mid-sentence: keep listening
                else -> set(false)       // nothing understood (or an error): don't spin; release still asks what was heard
            }
        })
    }
    private fun set(on: Boolean) { listening = on; onListening(on) }
    private fun drop() { dropped = false; set(false); heard = ""; onWords("") }
    private fun finish() { set(false); val t = heard; heard = ""; onWords(""); if (t.isNotEmpty()) onSubmit(t) }
}
