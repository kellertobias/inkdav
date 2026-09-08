package de.tobisk.inkvault.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import de.tobisk.inkvault.InkVaultApplication
import de.tobisk.inkvault.data.VaultPath
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.concurrent.thread

class QuickRecorder(private val app: InkVaultApplication) {
    @Volatile var running = false
        private set

    @Volatile var saving = false
        private set

    @Volatile var waveform: List<Float> = emptyList()
        private set

    @Volatile var lastPath: String? = null
        private set
    var started = 0L
        private set

    @SuppressLint("MissingPermission")
    fun start(finished: (String) -> Unit) {
        check(!running && !saving)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(32768, AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialized" }
        val folder = app.store.meta("recordingsFolder") ?: "Recordings"
        val name = "Recording-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}-${UUID.randomUUID().toString().take(8)}.mp3"
        val path = VaultPath.join(folder, name)
        val temporary = File(app.filesDir, "$name.pending")
        running = true
        started = System.currentTimeMillis()
        try {
            recorder.startRecording()
        } catch (e: Exception) {
            running = false
            recorder.release()
            throw e
        }
        thread(name = "InkVault recording") {
            var result: String
            try {
                Mp3Encoder().use { encoder ->
                    temporary.outputStream().use { output ->
                        val pcm = ShortArray(4096)
                        val mp3 = ByteArray(16384)
                        while (running) {
                            val count = recorder.read(pcm, 0, pcm.size)
                            check(count > 0) { "Microphone stopped: $count" }
                            waveform = (waveform + (0 until count).maxOf { kotlin.math.abs(pcm[it].toInt()) } / 32768f).takeLast(40)
                            output.write(mp3, 0, encoder.encode(pcm, count, mp3))
                        }
                        saving = true
                        output.write(mp3, 0, encoder.finish(mp3))
                        output.fd.sync()
                    }
                }
                app.store.save(path, temporary.inputStream())
                lastPath = path
                temporary.delete()
                app.syncNow()
                result = "Locally saved · $path"
            } catch (e: Exception) {
                result = "Recording failed: ${e.message}. Recovery file: ${temporary.name}"
            } finally {
                running = false
                saving = false
                try {
                    recorder.stop()
                } finally {
                    recorder.release()
                }
            }
            finished(result)
        }
    }
    fun stop() {
        if (running) saving = true
        running = false
    }
}
