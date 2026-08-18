package com.example.radioptt

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
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
    private val logTag = "RadioPtt"
    private val port = 50005
    private val discoveryPort = 50006
    private val sampleRate = 16_000
    private val onlineTimeoutMs = 6_000L
    private val sending = AtomicBoolean(false)
    private val receiving = AtomicBoolean(true)
    private val discovering = AtomicBoolean(true)

    private lateinit var radiosList: LinearLayout
    private lateinit var talkButton: Button
    private lateinit var localDeviceId: String
    private lateinit var localName: String
    private lateinit var localModel: String
    private val discoveredRadios = LinkedHashMap<String, RadioDevice>()
    private var selectedDeviceId: String? = null
    private var txSocket: DatagramSocket? = null
    private var rxSocket: DatagramSocket? = null
    private var discoveryRxSocket: DatagramSocket? = null
    private var discoveryTxSocket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isTransmitting = false
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

        startReceiver()
        startDiscovery()
        registerPttBroadcastReceiver()
    }

    private data class RadioDevice(
        val deviceId: String,
        val name: String,
        val model: String,
        val ip: String,
        val audioPort: Int,
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

    private fun startTransmission() {
        if (isTransmitting) return
        isTransmitting = true
        Log.d(logTag, "PTT_DOWN")
        talkButton.text = "TRANSMITINDO..."
        startTalk()
    }

    private fun stopTransmission() {
        if (!isTransmitting) return
        isTransmitting = false
        Log.d(logTag, "PTT_UP")
        talkButton.text = "SEGURE PARA FALAR"
        stopTalk()
    }

    private fun startTalk() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }

        val targetIp = selectedDeviceId?.let { discoveredRadios[it]?.ip } ?: ""
        if (targetIp.isEmpty() || !sending.compareAndSet(false, true)) {
            isTransmitting = false
            talkButton.text = "SEGURE PARA FALAR"
            return
        }

        Log.d(logTag, "TX_START")

        thread(name = "udp-audio-tx") {
            try {
                val address = InetAddress.getByName(targetIp)
                val socket = DatagramSocket()
                txSocket = socket

                val minBuffer = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = maxOf(minBuffer, 960)
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
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
                        socket.send(DatagramPacket(buffer, read, address, port))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.message ?: "Erro ao enviar audio", Toast.LENGTH_SHORT).show() }
            } finally {
                audioRecord?.runCatching { stop() }
                audioRecord?.release()
                audioRecord = null
                txSocket?.close()
                txSocket = null
                sending.set(false)
                runOnUiThread {
                    isTransmitting = false
                    talkButton.text = "SEGURE PARA FALAR"
                }
            }
        }
    }

    private fun stopTalk() {
        if (sending.getAndSet(false)) {
            Log.d(logTag, "TX_STOP")
        }
        audioRecord?.runCatching { stop() }
        txSocket?.close()
    }

    private fun startReceiver() {
        thread(name = "udp-audio-rx") {
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

                val socket = DatagramSocket(port)
                rxSocket = socket
                val buffer = ByteArray(2048)

                while (receiving.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    player.write(packet.data, packet.offset, packet.length)
                }
            } catch (_: Exception) {
            } finally {
                rxSocket?.close()
                rxSocket = null
                audioTrack?.runCatching { stop() }
                audioTrack?.release()
                audioTrack = null
            }
        }
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
                name = json.optString("nome", "Radio"),
                model = json.optString("modelo", ""),
                ip = packet.address.hostAddress ?: return,
                audioPort = json.optInt("portaAudio", port),
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
        receiving.set(false)
        stopTalk()
        rxSocket?.close()
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
}
