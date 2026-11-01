package com.example.sos

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Records the phone microphone to Music/SuperSOS, visible in any file or music app. */
class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var pendingUri: Uri? = null

    val isRecording get() = recorder != null

    /** Returns the file name, null if the microphone could not be started. */
    fun start(): String? {
        if (recorder != null) return null

        val name = fileName(Date())
        val media = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioEncodingBitRate(96_000)
            media.setAudioSamplingRate(44_100)
            setOutput(media, name)
            media.prepare()
            media.start()
            recorder = media
            name
        } catch (_: Exception) {
            media.release()
            closeOutput(publish = false)
            null
        }
    }

    fun stop() {
        val media = recorder ?: return
        recorder = null
        val saved = try {
            media.stop()
            true
        } catch (_: RuntimeException) {
            // Stopped right after starting: the file holds no audio.
            false
        }
        media.release()
        closeOutput(publish = saved)
    }

    private fun setOutput(media: MediaRecorder, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/SuperSOS")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore refused the file")
            pendingUri = uri
            descriptor = context.contentResolver.openFileDescriptor(uri, "w")
            media.setOutputFile(descriptor?.fileDescriptor)
        } else {
            // Without scoped storage, the app's own Music folder needs no extra permission.
            val folder = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            media.setOutputFile(File(folder, name).absolutePath)
        }
    }

    private fun closeOutput(publish: Boolean) {
        descriptor?.close()
        descriptor = null
        val uri = pendingUri ?: return
        pendingUri = null
        if (publish) {
            val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        } else {
            context.contentResolver.delete(uri, null, null)
        }
    }

    companion object {
        fun fileName(date: Date): String {
            return "SuperSOS_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(date) + ".m4a"
        }
    }
}
