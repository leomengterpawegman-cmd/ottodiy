package com.ottodiy.app

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

/**
 * Maneja la conexion Bluetooth Classic SPP con el ESP32.
 * El ESP32 debe usar BluetoothSerial y el UUID estandar de SPP.
 * Protocolo: una linea de texto JSON por mensaje, terminada en \n.
 */
class BluetoothLink(
    private val onLine: (String) -> Unit,
    private val onStateChange: (Boolean) -> Unit
) {
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var readThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isConnected: Boolean = false
        private set

    fun connect(device: BluetoothDevice) {
        Thread {
            try {
                val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                sock.connect()
                socket = sock
                output = sock.outputStream
                isConnected = true
                mainHandler.post { onStateChange(true) }
                listenLoop(sock)
            } catch (e: IOException) {
                isConnected = false
                mainHandler.post { onStateChange(false) }
            }
        }.start()
    }

    private fun listenLoop(sock: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(sock.inputStream))
            while (isConnected) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) {
                    mainHandler.post { onLine(line) }
                }
            }
        } catch (e: IOException) {
            // conexion perdida
        } finally {
            isConnected = false
            mainHandler.post { onStateChange(false) }
        }
    }

    /** Envia un objeto JSON como linea, agregando el salto de linea que el ESP32 espera. */
    fun sendJson(json: String) {
        val out = output ?: return
        Thread {
            try {
                out.write((json + "\n").toByteArray())
                out.flush()
            } catch (e: IOException) {
                isConnected = false
                mainHandler.post { onStateChange(false) }
            }
        }.start()
    }

    fun disconnect() {
        isConnected = false
        try {
            socket?.close()
        } catch (e: IOException) {
            // ignorar
        }
        socket = null
        output = null
    }
}
