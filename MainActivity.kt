package com.ottodiy.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONException
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var link: BluetoothLink
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }

    private lateinit var tvConnStatus: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvHum: TextView
    private lateinit var tvDist: TextView
    private lateinit var tvRobotState: TextView
    private lateinit var lvDevices: ListView

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var lastPongMillis = 0L
    private var cmdCounter = 0

    private val permissionsNeeded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvConnStatus = findViewById(R.id.tvConnStatus)
        tvBattery = findViewById(R.id.tvBattery)
        tvTemp = findViewById(R.id.tvTemp)
        tvHum = findViewById(R.id.tvHum)
        tvDist = findViewById(R.id.tvDist)
        tvRobotState = findViewById(R.id.tvRobotState)
        lvDevices = findViewById(R.id.lvDevices)

        link = BluetoothLink(onLine = ::handleIncomingLine, onStateChange = ::handleConnectionState)

        requestPermissionsIfNeeded()
        setupNavigation()
        setupBluetoothScreen()
        setupControlButtons()
    }

    // ---------- Navegacion entre las 3 pantallas ----------
    private fun setupNavigation() {
        val screenDatos = findViewById<android.view.View>(R.id.screenDatos)
        val screenBt = findViewById<android.view.View>(R.id.screenBt)
        val screenControl = findViewById<android.view.View>(R.id.screenControl)
        val navDatos = findViewById<Button>(R.id.navDatos)
        val navBt = findViewById<Button>(R.id.navBt)
        val navControl = findViewById<Button>(R.id.navControl)

        val screens = listOf(screenDatos, screenBt, screenControl)
        fun show(target: android.view.View) {
            screens.forEach { it.visibility = if (it == target) android.view.View.VISIBLE else android.view.View.GONE }
        }
        navDatos.setOnClickListener { show(screenDatos) }
        navBt.setOnClickListener { show(screenBt) }
        navControl.setOnClickListener { show(screenControl) }
    }

    // ---------- Pantalla Bluetooth: listar y conectar a dispositivos emparejados ----------
    private fun setupBluetoothScreen() {
        findViewById<Button>(R.id.btnScan).setOnClickListener { loadPairedDevices() }
    }

    private fun loadPairedDevices() {
        val bt = adapter
        if (bt == null) {
            Toast.makeText(this, "Este dispositivo no tiene Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasBtPermission()) {
            requestPermissionsIfNeeded()
            return
        }
        val paired: Set<BluetoothDevice> = bt.bondedDevices ?: emptySet()
        val names = paired.map { "${it.name}\n${it.address}" }
        val devicesList = paired.toList()

        lvDevices.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        lvDevices.setOnItemClickListener { _, _, position, _ ->
            connectTo(devicesList[position])
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        tvConnStatus.text = "conectando a ${device.name}..."
        link.connect(device)
    }

    // ---------- Estado de conexion ----------
    private fun handleConnectionState(connected: Boolean) {
        if (connected) {
            tvConnStatus.text = "conectado:esp32"
            lastPongMillis = System.currentTimeMillis()
            startHeartbeat()
        } else {
            tvConnStatus.text = "desconectado"
            heartbeatHandler.removeCallbacksAndMessages(null)
            tvRobotState.text = "Estado: --"
        }
    }

    // ---------- Heartbeat: ping cada 2s, si no hay pong en 5s se asume perdida de enlace ----------
    private fun startHeartbeat() {
        val pingRunnable = object : Runnable {
            override fun run() {
                if (!link.isConnected) return
                link.sendJson("""{"type":"ping"}""")
                if (System.currentTimeMillis() - lastPongMillis > 5000) {
                    tvConnStatus.text = "enlace inestable"
                }
                heartbeatHandler.postDelayed(this, 2000)
            }
        }
        heartbeatHandler.post(pingRunnable)
    }

    // ---------- Parseo de mensajes JSON entrantes ----------
    private fun handleIncomingLine(line: String) {
        try {
            val obj = JSONObject(line)
            when (obj.optString("type")) {
                "telemetry" -> {
                    val sensors = obj.optJSONObject("sensors")
                    val robot = obj.optJSONObject("robot")
                    if (sensors != null) {
                        tvTemp.text = "Temperatura: ${sensors.optDouble("temp_c")} C"
                        tvHum.text = "Humedad: ${sensors.optDouble("hum_pct")} %"
                        tvDist.text = "Distancia: ${sensors.optDouble("dist_cm")} cm"
                    }
                    if (robot != null) {
                        tvRobotState.text = "Estado: ${robot.optString("state")}"
                        tvBattery.text = "bateria: ${robot.optInt("battery_pct")}%"
                    }
                }
                "pong" -> {
                    lastPongMillis = System.currentTimeMillis()
                }
                "ack" -> {
                    // comando confirmado por el ESP32, no requiere accion visual obligatoria
                }
                "error" -> {
                    val msg = obj.optString("msg")
                    Toast.makeText(this, "Error robot: $msg", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: JSONException) {
            // linea corrupta o incompleta, se ignora
        }
    }

    // ---------- Pantalla Controlador ----------
    private fun setupControlButtons() {
        findViewById<Button>(R.id.btnForward).setOnClickListener {
            sendCommand("MOVE_FORWARD", speed = 80, durationMs = 500)
        }
        findViewById<Button>(R.id.btnBack).setOnClickListener {
            sendCommand("MOVE_BACK", speed = 80, durationMs = 500)
        }
        findViewById<Button>(R.id.btnLeft).setOnClickListener {
            sendCommand("TURN_LEFT", speed = 60, durationMs = 300)
        }
        findViewById<Button>(R.id.btnRight).setOnClickListener {
            sendCommand("TURN_RIGHT", speed = 60, durationMs = 300)
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            sendCommand("STOP", speed = 0, durationMs = 0)
        }
    }

    private fun sendCommand(action: String, speed: Int, durationMs: Int) {
        if (!link.isConnected) {
            Toast.makeText(this, "No conectado al ESP32", Toast.LENGTH_SHORT).show()
            return
        }
        cmdCounter++
        val json = JSONObject().apply {
            put("type", "cmd")
            put("id", "c-$cmdCounter")
            put("action", action)
            put("params", JSONObject().apply {
                put("speed", speed)
                put("duration_ms", durationMs)
            })
        }
        link.sendJson(json.toString())
    }

    // ---------- Permisos ----------
    private fun hasBtPermission(): Boolean {
        return permissionsNeeded.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (!hasBtPermission()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded, 100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatHandler.removeCallbacksAndMessages(null)
        link.disconnect()
    }
}
