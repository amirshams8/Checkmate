package com.jarvismini.voice

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import android.content.Context
import android.util.Log

/*
===== GRADLE DEPENDENCY NEEDED (apply manually) =====

Add this line inside the dependencies { } block of app/build.gradle.kts:

    implementation("ai.picovoice:porcupine-android:3.0.3")

(3.0.3 is the current latest stable release on Maven Central as of this
session — mavenCentral() is almost certainly already in your repositories
block since other dependencies in this project pull from it.)

===== VERSION COMPATIBILITY WARNING =====

Picovoice .ppn keyword files are version-locked to the Porcupine SDK/model
version they were generated with. Since this is an OLDER .ppn file, it may
fail to load against SDK 3.0.3 with a PorcupineInvalidArgumentException or
similar at build() time. This class catches that and falls back safely
(WakeWordService will drop back to RmsThresholdDetector — see below) instead
of crashing, but if you see wake word detection silently not working, this
version mismatch is the first thing to check. Fix options if it happens:
  1. Regenerate the .ppn from the current Picovoice Console (requires login,
     not the blocked signup flow — should work once you have an account).
  2. Or pin the SDK to whatever version your .ppn was originally generated
     with, if you know/remember it.
*/

/**
 * Real wake-word detector backed by Picovoice Porcupine, using your existing
 * AccessKey + .ppn keyword file.
 *
 * Buffers incoming PCM internally into exact Porcupine frameLength chunks
 * (512 samples @ 16kHz) since WakeWordService's AudioRecord read sizes won't
 * naturally align with what Porcupine's native process() call requires.
 *
 * Expects the .ppn file at app/src/main/assets/<keywordAssetPath> — Porcupine's
 * Android SDK accepts a path relative to the assets/ folder directly, no
 * manual extraction needed.
 */
class PorcupineWakeWordDetector(
    private val context: Context,
    private val accessKey: String,
    private val keywordAssetPath: String = "porcupine_jarvis.ppn",
    private val sensitivity: Float = 0.6f
) : WakeWordDetector {

    companion object {
        private const val TAG = "PorcupineDetector"
    }

    private var porcupine: Porcupine? = null
    private var accumulator: ShortArray = ShortArray(0)

    /** True once Porcupine has successfully initialized. WakeWordService checks
     *  this after start() to decide whether to fall back to RmsThresholdDetector. */
    var isReady: Boolean = false
        private set

    override fun start() {
        try {
            porcupine = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath(keywordAssetPath)
                .setSensitivity(sensitivity)
                .build(context)
            accumulator = ShortArray(0)
            isReady = true
            Log.d(
                TAG,
                "Porcupine initialized OK — frameLength=${porcupine?.frameLength}, " +
                    "sampleRate=${porcupine?.sampleRate}"
            )
        } catch (e: PorcupineException) {
            Log.e(
                TAG,
                "Porcupine failed to initialize (likely .ppn/SDK version mismatch — " +
                    "see version compatibility warning in this file's header): ${e.message}",
                e
            )
            porcupine = null
            isReady = false
        }
    }

    override fun processFrame(pcm: ShortArray, readCount: Int): Boolean {
        val p = porcupine ?: return false
        val frameLength = p.frameLength

        // Append newly read samples to leftover from the previous call.
        val combined = ShortArray(accumulator.size + readCount)
        System.arraycopy(accumulator, 0, combined, 0, accumulator.size)
        System.arraycopy(pcm, 0, combined, accumulator.size, readCount)

        var offset = 0
        var detected = false

        while (combined.size - offset >= frameLength) {
            val chunk = combined.copyOfRange(offset, offset + frameLength)
            try {
                val keywordIndex = p.process(chunk)
                if (keywordIndex >= 0) {
                    detected = true
                }
            } catch (e: PorcupineException) {
                Log.e(TAG, "process() failed: ${e.message}", e)
            }
            offset += frameLength
        }

        // Keep any leftover samples that didn't fill a full frame this round.
        accumulator = if (offset < combined.size) {
            combined.copyOfRange(offset, combined.size)
        } else {
            ShortArray(0)
        }

        return detected
    }

    override fun stop() {
        porcupine?.delete()
        porcupine = null
        accumulator = ShortArray(0)
        isReady = false
    }
}
