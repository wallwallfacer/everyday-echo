package com.everydayecho.memorykeeper.transcribe

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Wraps Android's [SpeechRecognizer] to provide a continuous-ish listening
 * loop: when one recognition session ends (silence / timeout / error), we
 * immediately start another.
 *
 * Spec §13: real voice fingerprinting is out of scope for MVP. The MVP
 * marks every fragment as "self?" and lets the correction loop reattribute
 * later.
 */
class ContinuousSpeechRecognizer(
    private val context: Context,
    private val onPartial: (String) -> Unit = {},
    private val onFinal: (String) -> Unit,
    private val onError: (Int, String) -> Unit = { _, _ -> },
) {

    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var restarting = false

    fun start() {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError(-1, "设备不支持系统语音识别")
            return
        }
        running = true
        main.post { beginSession() }
    }

    fun stop() {
        running = false
        main.post {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun beginSession() {
        if (!running) return
        // Recreate the recognizer each session — some OEMs leak listeners otherwise.
        recognizer?.destroy()
        val r = if (Build.VERSION.SDK_INT >= 31) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
                Log.d(TAG, "Using on-device recognizer")
            }
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (!running) return
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    // Expected when there's silence — quietly restart.
                    scheduleRestart(150)
                } else {
                    onError(error, errorText(error))
                    scheduleRestart(2000)
                }
            }

            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) onFinal(text)
                scheduleRestart(50)
            }

            override fun onPartialResults(partial: Bundle?) {
                val list = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            r.startListening(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "startListening failed", t)
            scheduleRestart(2000)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!running || restarting) return
        restarting = true
        main.postDelayed({
            restarting = false
            beginSession()
        }, delayMs)
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio error"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no mic permission"
        SpeechRecognizer.ERROR_NETWORK -> "network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        else -> "code=$code"
    }

    companion object {
        private const val TAG = "MK.SpeechRec"
    }
}
