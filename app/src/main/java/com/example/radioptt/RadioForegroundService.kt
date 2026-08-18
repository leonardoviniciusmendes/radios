package com.example.radioptt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
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
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject

class RadioForegroundService : Service() {
    private val audioPort = 50005
    private val discoveryPort = 50006
    private val httpPort = 50080
    private val sampleRate = 16_000
    private val running = AtomicBoolean(false)
    private val audioReceiverRunning = AtomicBoolean(false)
    private val discoveryRunning = AtomicBoolean(false)
    private val httpServerRunning = AtomicBoolean(false)
    private var audioSocket: DatagramSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private var httpServerSocket: ServerSocket? = null
    private var audioTrack: AudioTrack? = null
    private var networkReceiverRegistered = false
    private var lastWifiStateLog: String? = null
    private var lastConnected: Boolean? = null
    private val configPreferences: SharedPreferences by lazy {
        getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE)
    }
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
        httpServerSocket?.close()
        audioTrack?.runCatching { stop() }
        audioTrack?.release()
        audioTrack = null
        super.onDestroy()
    }

    private fun startRadio() {
        running.compareAndSet(false, true)
        startAudioReceiver()
        startDiscoverySender()
        startHttpServer()
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

    private fun startHttpServer() {
        if (!httpServerRunning.compareAndSet(false, true)) return
        Log.i(TAG, "HTTP_SERVER_START port=$httpPort")
        thread(name = "service-http-admin") {
            try {
                val serverSocket = ServerSocket()
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress(httpPort))
                httpServerSocket = serverSocket

                while (running.get()) {
                    val client = serverSocket.accept()
                    thread(name = "service-http-admin-client") {
                        handleHttpClient(client)
                    }
                }
            } catch (_: Exception) {
            } finally {
                httpServerSocket?.close()
                httpServerSocket = null
                httpServerRunning.set(false)
                Log.i(TAG, "HTTP_SERVER_STOP")
            }
        }
    }

    private fun handleHttpClient(client: Socket) {
        try {
            client.soTimeout = 2_000
            val input = client.getInputStream()
            val requestLine = readHttpLine(input) ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val path = parts.getOrNull(1)?.substringBefore("?") ?: ""
            val headers = readHttpHeaders(input)
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

            if (method == "GET" && path == "/api/device") {
                Log.i(TAG, "HTTP_REQUEST GET /api/device")
                writeHttpResponse(client, 200, "OK", buildDeviceJson().toString())
                return
            }

            if (method == "PUT" && path == "/api/device/config") {
                val body = readHttpBody(input, contentLength)
                val result = saveDeviceConfig(body)
                if (result == null) {
                    writeHttpResponse(client, 200, "OK", buildDeviceJson().toString())
                } else {
                    writeHttpResponse(client, 400, "Bad Request", JSONObject().put("error", result).toString())
                }
                return
            }

            if (method == "OPTIONS") {
                writeHttpResponse(client, 200, "OK", "{}")
                return
            }

            if (path == "/api/device" || path == "/api/device/config") {
                writeHttpResponse(client, 405, "Method Not Allowed", JSONObject().put("error", "method_not_allowed").toString())
                return
            }

            writeHttpResponse(client, 404, "Not Found", JSONObject().put("error", "not_found").toString())
        } catch (_: Exception) {
            runCatching {
                writeHttpResponse(client, 500, "Internal Server Error", JSONObject().put("error", "internal_error").toString())
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readHttpLine(input: java.io.InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val current = input.read()
            if (current == -1) {
                break
            }
            if (previous == '\r'.code && current == '\n'.code) {
                val bytes = buffer.toByteArray()
                return String(bytes, 0, maxOf(bytes.size - 1, 0), Charsets.UTF_8)
            }
            buffer.write(current)
            previous = current
        }
        if (buffer.size() == 0) return null
        return String(buffer.toByteArray(), Charsets.UTF_8)
    }

    private fun readHttpHeaders(input: java.io.InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readHttpLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                val name = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                headers[name] = value
            }
        }
        return headers
    }

    private fun readHttpBody(input: java.io.InputStream, contentLength: Int): String {
        if (contentLength <= 0) return ""
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(body, offset, contentLength - offset)
            if (read == -1) break
            offset += read
        }
        return String(body, 0, offset, Charsets.UTF_8)
    }

    private fun writeHttpResponse(client: Socket, statusCode: Int, statusText: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = StringBuilder()
            .append("HTTP/1.1 ").append(statusCode).append(' ').append(statusText).append("\r\n")
            .append("Content-Type: application/json; charset=utf-8\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("Access-Control-Allow-Methods: GET, PUT, OPTIONS\r\n")
            .append("Access-Control-Allow-Headers: Content-Type\r\n")
            .append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            .append("Connection: close\r\n")
            .append("\r\n")
            .toString()
            .toByteArray(Charsets.UTF_8)

        val output = client.getOutputStream()
        output.write(headers)
        output.write(bodyBytes)
        output.flush()
    }

    private fun buildDeviceJson(): JSONObject {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiInfo = wifiManager?.connectionInfo
        val config = getDeviceConfig()
        return JSONObject()
            .put("deviceId", getRadioDeviceId())
            .put("name", config.name)
            .put("model", Build.MODEL ?: "unknown")
            .put("ip", getWifiIpAddress(wifiInfo?.ipAddress ?: 0))
            .put("mac", getAvailableMac(wifiInfo?.macAddress))
            .put("online", true)
            .put("channel", config.channel)
            .put("enabled", config.enabled)
            .put("appVersion", getAppVersion())
            .put("wifi", getWifiSsid(wifiInfo?.ssid))
            .put("signal", getWifiSignal(wifiManager, wifiInfo?.rssi))
            .put("battery", getBatteryPercent())
    }

    private data class DeviceConfig(
        val name: String,
        val channel: String,
        val enabled: Boolean
    )

    private fun getDeviceConfig(): DeviceConfig {
        return DeviceConfig(
            name = configPreferences.getString(CONFIG_NAME, null)?.takeIf { it.isNotBlank() }
                ?: "Radio ${Build.MODEL}",
            channel = configPreferences.getString(CONFIG_CHANNEL, null)?.takeIf { it.isNotBlank() }
                ?: "Geral",
            enabled = configPreferences.getBoolean(CONFIG_ENABLED, true)
        )
    }

    private fun saveDeviceConfig(body: String): String? {
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            return "invalid_json"
        }

        val name = json.optString("name", "").trim()
        val channel = json.optString("channel", "").trim()

        if (name.isEmpty()) return "name_required"
        if (channel.isEmpty()) return "channel_required"
        if (!json.has("enabled")) return "enabled_required"
        val enabledValue = json.opt("enabled")
        if (enabledValue !is Boolean) return "enabled_must_be_boolean"

        configPreferences.edit()
            .putString(CONFIG_NAME, name)
            .putString(CONFIG_CHANNEL, channel)
            .putBoolean(CONFIG_ENABLED, enabledValue)
            .apply()

        return null
    }

    private fun getRadioDeviceId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "radio-${Build.MODEL}"
    }

    private fun getWifiIpAddress(ipAddress: Int): String {
        if (ipAddress == 0) return "unavailable"
        return "${ipAddress and 0xff}.${ipAddress shr 8 and 0xff}.${ipAddress shr 16 and 0xff}.${ipAddress shr 24 and 0xff}"
    }

    private fun getAvailableMac(macAddress: String?): String {
        val mac = macAddress?.trim().orEmpty()
        if (mac.isEmpty() || mac == "02:00:00:00:00:00") return "unavailable"
        return mac
    }

    private fun getAppVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unavailable"
        } catch (_: Exception) {
            "unavailable"
        }
    }

    private fun getWifiSsid(ssid: String?): String {
        val value = ssid?.trim().orEmpty()
        if (value.isEmpty() || value == "<unknown ssid>") return "unavailable"
        return value.trim('"')
    }

    private fun getWifiSignal(wifiManager: WifiManager?, rssi: Int?): Int {
        val value = rssi ?: return 0
        return runCatching { WifiManager.calculateSignalLevel(value, 101) }.getOrDefault(0)
    }

    private fun getBatteryPercent(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return 0
        return level * 100 / scale
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
        private const val CONFIG_PREFS_NAME = "radio_device_config"
        private const val CONFIG_NAME = "name"
        private const val CONFIG_CHANNEL = "channel"
        private const val CONFIG_ENABLED = "enabled"
    }
}
