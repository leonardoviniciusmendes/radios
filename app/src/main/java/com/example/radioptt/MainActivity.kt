package com.example.radioptt

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject

class MainActivity : Activity() {
    private val port = 50005
    private val discoveryPort = 50006
    private val onlineTimeoutMs = 6_000L
    private val configPreferences by lazy {
        getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val discovering = AtomicBoolean(true)

    private lateinit var radiosList: LinearLayout
    private lateinit var talkButton: Button
    private lateinit var localDeviceId: String
    private lateinit var localName: String
    private lateinit var localModel: String
    private val discoveredRadios = LinkedHashMap<String, RadioDevice>()
    private var selectedDeviceId: String? = null
    private var discoveryRxSocket: DatagramSocket? = null
    private var discoveryTxSocket: DatagramSocket? = null
    private var pttBroadcastReceiverRegistered = false
    private val pttBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "android.intent.action.PTT.down" -> startTransmission()
                "android.intent.action.PTT.up" -> stopTransmission()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RadioServiceStarter.startFromActivity(this)

        localDeviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "radio-${Build.MODEL}"
        localName = "Radio ${Build.MODEL}"
        localModel = Build.MODEL

        radiosList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        talkButton = Button(this).apply {
            text = "SEGURE PARA FALAR"
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startTransmission()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        stopTransmission()
                        true
                    }
                    else -> false
                }
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 48, 32, 32)
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Rádios disponíveis"
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    radiosList,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    talkButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        startDiscovery()
        configurePttController()
        registerPttBroadcastReceiver()
    }

    private data class RadioDevice(
        val deviceId: String,
        val name: String,
        val model: String,
        val ip: String,
        val audioPort: Int,
        val channel: String,
        val lastSeen: Long
    )

    private fun registerPttBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction("android.intent.action.PTT.down")
            addAction("android.intent.action.PTT.up")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pttBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(pttBroadcastReceiver, filter)
            }
            pttBroadcastReceiverRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun configurePttController() {
        PttController.configure(
            context = this,
            targetIpsProvider = {
                val currentChannel = normalizeChannel(getCurrentChannel())
                val targets = discoveredRadios.values
                    .filter { normalizeChannel(it.channel) == currentChannel }
                    .map { it.ip }
                    .filter { it.isNotBlank() }
                    .distinct()
                Log.i(TAG, "TX_CHANNEL channel=${getCurrentChannel()} targets=${targets.size}")
                targets
            },
            callbacks = PttController.Callbacks(
                onPttDown = {
                    runOnUiThread {
                        talkButton.text = "TRANSMITINDO..."
                    }
                },
                onPttUp = {
                    runOnUiThread {
                        talkButton.text = "SEGURE PARA FALAR"
                    }
                },
                onTxStop = {
                    runOnUiThread {
                        talkButton.text = "SEGURE PARA FALAR"
                    }
                },
                onError = { message ->
                    runOnUiThread {
                        talkButton.text = "SEGURE PARA FALAR"
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        )
    }

    private fun getCurrentChannel(): String {
        return configPreferences.getString(CONFIG_CHANNEL, null)?.takeIf { it.isNotBlank() } ?: "Geral"
    }

    private fun normalizeChannel(channel: String): String {
        return channel.trim().lowercase()
    }

    private fun startTransmission() {
        PttController.startTransmission()
    }

    private fun stopTransmission() {
        PttController.stopTransmission()
    }

    private fun startDiscovery() {
        startDiscoverySender()
        startDiscoveryReceiver()
        renderRadios()
    }

    private fun startDiscoverySender() {
        thread(name = "udp-discovery-tx") {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true
                discoveryTxSocket = socket
                val address = InetAddress.getByName("255.255.255.255")

                while (discovering.get()) {
                    val payload = JSONObject()
                        .put("deviceId", localDeviceId)
                        .put("name", localName)
                        .put("model", localModel)
                        .put("httpPort", 50080)
                        .put("channel", getCurrentChannel())
                        .put("nome", localName)
                        .put("modelo", localModel)
                        .put("portaAudio", port)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(payload, payload.size, address, discoveryPort))
                    Thread.sleep(2_000)
                }
            } catch (_: Exception) {
            } finally {
                discoveryTxSocket?.close()
                discoveryTxSocket = null
            }
        }
    }

    private fun startDiscoveryReceiver() {
        thread(name = "udp-discovery-rx") {
            try {
                val socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.soTimeout = 1_000
                socket.bind(InetSocketAddress(discoveryPort))
                discoveryRxSocket = socket
                val buffer = ByteArray(1024)

                while (discovering.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        handleDiscoveryPacket(packet)
                    } catch (_: SocketTimeoutException) {
                    }
                    removeStaleRadios()
                }
            } catch (_: Exception) {
            } finally {
                discoveryRxSocket?.close()
                discoveryRxSocket = null
            }
        }
    }

    private fun handleDiscoveryPacket(packet: DatagramPacket) {
        try {
            val json = JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
            val deviceId = json.optString("deviceId")
            if (deviceId.isEmpty() || deviceId == localDeviceId) return

            val radio = RadioDevice(
                deviceId = deviceId,
                name = json.optString("name", json.optString("nome", "Radio")),
                model = json.optString("model", json.optString("modelo", "")),
                ip = packet.address.hostAddress ?: return,
                audioPort = json.optInt("portaAudio", port),
                channel = json.optString("channel", "Geral").trim().ifEmpty { "Geral" },
                lastSeen = System.currentTimeMillis()
            )
            discoveredRadios[deviceId] = radio
            if (selectedDeviceId == null) {
                selectedDeviceId = deviceId
            }
            runOnUiThread { renderRadios() }
        } catch (_: Exception) {
        }
    }

    private fun removeStaleRadios() {
        val now = System.currentTimeMillis()
        val removedSelected = discoveredRadios.entries.removeAll {
            now - it.value.lastSeen > onlineTimeoutMs
        }
        if (removedSelected && selectedDeviceId !in discoveredRadios.keys) {
            selectedDeviceId = discoveredRadios.keys.firstOrNull()
        }
        runOnUiThread { renderRadios() }
    }

    private fun renderRadios() {
        radiosList.removeAllViews()
        if (discoveredRadios.isEmpty()) {
            radiosList.addView(TextView(this).apply {
                text = "Nenhum radio encontrado"
            })
            return
        }

        for (radio in discoveredRadios.values) {
            radiosList.addView(Button(this).apply {
                val selected = radio.deviceId == selectedDeviceId
                text = "${if (selected) "> " else ""}${radio.name} - ${radio.model} (${radio.ip})"
                setOnClickListener {
                    selectedDeviceId = radio.deviceId
                    renderRadios()
                }
            })
        }
    }

    override fun onDestroy() {
        discovering.set(false)
        PttController.stopTransmission()
        PttController.clearConfiguration()
        discoveryRxSocket?.close()
        discoveryTxSocket?.close()
        if (pttBroadcastReceiverRegistered) {
            unregisterReceiver(pttBroadcastReceiver)
            pttBroadcastReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_F1 || event.keyCode == 229) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        startTransmission()
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    stopTransmission()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val TAG = "RadioPtt"
        private const val CONFIG_PREFS_NAME = "radio_device_config"
        private const val CONFIG_CHANNEL = "channel"
    }
}
