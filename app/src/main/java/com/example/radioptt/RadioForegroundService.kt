package com.example.radioptt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
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
    private val audioReceiverRunning = AtomicBoolean(false)
    private val discoveryRunning = AtomicBoolean(false)
    private var audioSocket: DatagramSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null
    private var networkReceiverRegistered = false
    private var lastWifiStateLog: String? = null
    private var lastConnected: Boolean? = null
    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                logWifiState(wifiStateName(state))
            }
            val connected = isWifiConnected()
            logWifiConnection(connected)
            if (connected) {
                restartDiscoverySender()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SERVICE_START")
        startForeground(1, buildNotification())
        registerNetworkReceiver()
        ensureWifiEnabledIfPossible()
        startRadio()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startRadio()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "SERVICE_DESTROYED")
        running.set(false)
        unregisterNetworkReceiver()
        audioSocket?.close()
        discoverySocket?.close()
        audioTrack?.runCatching { stop() }
        audioTrack?.release()
        audioTrack = null
        super.onDestroy()
    }

    private fun startRadio() {
        running.compareAndSet(false, true)
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
        if (!audioReceiverRunning.compareAndSet(false, true)) return
        Log.i(TAG, "AUDIO_RECEIVER_START")
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
                audioReceiverRunning.set(false)
            }
        }
    }

    private fun startDiscoverySender() {
        if (!discoveryRunning.compareAndSet(false, true)) return
        Log.i(TAG, "DISCOVERY_START")
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
                discoveryRunning.set(false)
            }
        }
    }

    private fun restartDiscoverySender() {
        if (discoveryRunning.get()) return
        Log.i(TAG, "DISCOVERY_RESTART")
        startDiscoverySender()
    }

    private fun registerNetworkReceiver() {
        if (networkReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        registerReceiver(networkReceiver, filter)
        networkReceiverRegistered = true
    }

    private fun unregisterNetworkReceiver() {
        if (!networkReceiverRegistered) return
        runCatching { unregisterReceiver(networkReceiver) }
        networkReceiverRegistered = false
    }

    private fun ensureWifiEnabledIfPossible() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return
        val state = wifiStateName(wifiManager.wifiState)
        logWifiState(state)
        if (!wifiManager.isWifiEnabled) {
            Log.i(TAG, "WIFI_ENABLE_ATTEMPT")
            runCatching { wifiManager.isWifiEnabled = true }
        }
        logWifiConnection(isWifiConnected())
    }

    private fun isWifiConnected(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val networkInfo: NetworkInfo? = connectivityManager.activeNetworkInfo
        return networkInfo?.type == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected
    }

    private fun logWifiState(state: String) {
        if (lastWifiStateLog == state) return
        lastWifiStateLog = state
        Log.i(TAG, "WIFI_STATE=$state")
    }

    private fun logWifiConnection(connected: Boolean) {
        if (lastConnected == connected) return
        lastConnected = connected
        Log.i(TAG, if (connected) "WIFI_CONNECTED" else "WIFI_DISCONNECTED")
    }

    private fun wifiStateName(state: Int): String = when (state) {
        WifiManager.WIFI_STATE_DISABLED -> "DISABLED"
        WifiManager.WIFI_STATE_DISABLING -> "DISABLING"
        WifiManager.WIFI_STATE_ENABLED -> "ENABLED"
        WifiManager.WIFI_STATE_ENABLING -> "ENABLING"
        else -> "UNKNOWN"
    }

    companion object {
        private const val TAG = "RadioService"
    }
}
