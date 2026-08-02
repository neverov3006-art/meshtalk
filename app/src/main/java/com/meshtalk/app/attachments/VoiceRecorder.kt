package com.meshtalk.app.attachments

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID

data class RecordingResult(val file: File, val durationMs: Int, val mimeType: String)

/**
 * Records a voice message to a temporary AAC/M4A file. One recorder instance is used
 * for a single start/stop cycle — create a fresh VoiceRecorder per recording rather
 * than reusing one across multiple messages.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0

    fun start() {
        val file = File(context.cacheDir, "voice_${UUID.randomUUID()}.m4a")
        outputFile = file

        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = mediaRecorder
        startedAtMs = System.currentTimeMillis()
    }

    /** Stops recording and returns the file + measured duration, or null if nothing was recorded. */
    fun stop(): RecordingResult? {
        val mediaRecorder = recorder ?: return null
        val file = outputFile ?: return null
        val durationMs = (System.currentTimeMillis() - startedAtMs).toInt()

        return runCatching {
            mediaRecorder.stop()
            mediaRecorder.release()
            recorder = null
            RecordingResult(file, durationMs, mimeType = "audio/mp4")
        }.getOrElse {
            // Very short recordings (tap-tap) can throw on stop() with nothing captured — discard cleanly.
            runCatching { mediaRecorder.release() }
            file.delete()
            null
        }
    }

    /** Discards an in-progress recording without saving (e.g. user cancels). */
    fun cancel() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}
