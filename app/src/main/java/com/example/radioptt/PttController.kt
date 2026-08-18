package com.example.radioptt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object PttController {
    private const val TAG = "RadioPtt"
    private const val AUDIO_PORT = 50005
    private const val SAMPLE_RATE = 16_000

    private val lock = Any()
    private val sending = AtomicBoolean(false)

    @Volatile
    private var isTransmitting = false
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var targetIpProvider: (() -> String?)? = null
    @Volatile
    private var callbacks: Callbacks = Callbacks()

    private var txSocket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null

    data class Callbacks(
        val onPttDown: () -> Unit = {},
        val onPttUp: () -> Unit = {},
        val onTxStart: () -> Unit = {},
        val onTxStop: () -> Unit = {},
        val onError: (String) -> Unit = {}
    )

    fun configure(context: Context, targetIpProvider: () -> String?, callbacks: Callbacks = Callbacks()) {
        appContext = context.applicationContext
        this.targetIpProvider = targetIpProvider
        this.callbacks = callbacks
    }

    fun clearConfiguration() {
        callbacks = Callbacks()
        targetIpProvider = null
        appContext = null
    }

    fun startTransmission() {
        val context = appContext ?: return
        if (!hasRecordAudioPermission(context)) {
            callbacks.onError("Permissao de audio nao concedida")
            return
        }

        val targetIp = targetIpProvider?.invoke()?.takeIf { it.isNotBlank() } ?: return
        synchronized(lock) {
            if (isTransmitting) return
            if (!sending.compareAndSet(false, true)) return
            isTransmitting = true
        }

        Log.d(TAG, "PTT_DOWN")
        callbacks.onPttDown()
        Log.d(TAG, "TX_START")
        callbacks.onTxStart()

        thread(name = "udp-audio-tx") {
            try {
                val address = InetAddress.getByName(targetIp)
                val socket = DatagramSocket()
                txSocket = socket

                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = maxOf(minBuffer, 960)
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
                )
                audioRecord = recorder

                val buffer = ByteArray(960)
                recorder.startRecording()
                while (sending.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        socket.send(DatagramPacket(buffer, read, address, AUDIO_PORT))
                    }
                }
            } catch (e: Exception) {
                callbacks.onError(e.message ?: "Erro ao enviar audio")
            } finally {
                releaseTransmissionResources()
                val shouldNotifyPttUp = synchronized(lock) {
                    val wasTransmitting = isTransmitting
                    isTransmitting = false
                    wasTransmitting
                }
                if (shouldNotifyPttUp) {
                    callbacks.onPttUp()
                }
                callbacks.onTxStop()
            }
        }
    }

    fun stopTransmission() {
        val shouldNotifyPttUp = synchronized(lock) {
            if (!isTransmitting) {
                false
            } else {
                isTransmitting = false
                true
            }
        }

        if (shouldNotifyPttUp) {
            Log.d(TAG, "PTT_UP")
            callbacks.onPttUp()
        }

        if (sending.getAndSet(false)) {
            Log.d(TAG, "TX_STOP")
            callbacks.onTxStop()
        }
        audioRecord?.runCatching { stop() }
        txSocket?.close()
    }

    private fun releaseTransmissionResources() {
        audioRecord?.runCatching { stop() }
        audioRecord?.release()
        audioRecord = null
        txSocket?.close()
        txSocket = null
        sending.set(false)
    }

    private fun hasRecordAudioPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}
