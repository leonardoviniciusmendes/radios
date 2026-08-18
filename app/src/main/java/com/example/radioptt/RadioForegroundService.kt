package com.example.radioptt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject

class RadioForegroundService : Service() {
    private val audioPort = 50005
    private val discoveryPort = 50006
    private val sampleRate = 16_000
    private val running = AtomicBoolean(false)
    private var audioSocket: DatagramSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        startRadio()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startRadio()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.set(false)
        audioSocket?.close()
        discoverySocket?.close()
        audioTrack?.runCatching { stop() }
        audioTrack?.release()
        audioTrack = null
        super.onDestroy()
    }

    private fun startRadio() {
        if (!running.compareAndSet(false, true)) return
        startAudioReceiver()
        startDiscoverySender()
    }

    private fun buildNotification(): Notification {
        val channelId = "radio_ptt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Radio PTT",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            return Notification.Builder(this, channelId)
                .setContentTitle("Radio PTT")
                .setContentText("Recepcao ativa")
                .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
                .build()
        }

        return Notification.Builder(this)
            .setContentTitle("Radio PTT")
            .setContentText("Recepcao ativa")
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .build()
    }

    private fun startAudioReceiver() {
        thread(name = "service-udp-audio-rx") {
            try {
                val minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val player = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 4,
                    AudioTrack.MODE_STREAM
                )
                audioTrack = player
                player.play()

                val socket = DatagramSocket(audioPort)
                audioSocket = socket
                val buffer = ByteArray(2048)

                while (running.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    player.write(packet.data, packet.offset, packet.length)
                }
            } catch (_: Exception) {
            } finally {
                audioSocket?.close()
                audioSocket = null
                audioTrack?.runCatching { stop() }
                audioTrack?.release()
                audioTrack = null
            }
        }
    }

    private fun startDiscoverySender() {
        thread(name = "service-udp-discovery-tx") {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true
                discoverySocket = socket
                val address = InetAddress.getByName("255.255.255.255")
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                    ?: "radio-${Build.MODEL}"
                val name = "Radio ${Build.MODEL}"
                val model = Build.MODEL

                while (running.get()) {
                    val payload = JSONObject()
                        .put("deviceId", deviceId)
                        .put("nome", name)
                        .put("modelo", model)
                        .put("portaAudio", audioPort)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(payload, payload.size, address, discoveryPort))
                    Thread.sleep(2_000)
                }
            } catch (_: Exception) {
            } finally {
                discoverySocket?.close()
                discoverySocket = null
            }
        }
    }
}
