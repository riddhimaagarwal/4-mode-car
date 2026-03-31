package com.example.btvoicecarcntrl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.UUID

class BluetoothController(
    private val adapter: BluetoothAdapter?
) {
    companion object {
        // Standard SPP UUID for HC-05 / HC-06
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "BtCar"
    }

    private var socket: BluetoothSocket? = null
    private var device: BluetoothDevice? = null

    val isConnected: Boolean
        get() = socket?.isConnected == true

    fun connectToPairedDeviceByName(deviceName: String, onStatus: (String) -> Unit) {
        if (adapter == null) {
            onStatus("Bluetooth not available")
            return
        }

        val bonded = adapter.bondedDevices
        device = bonded.firstOrNull { it.name == deviceName }

        if (device == null) {
            onStatus("Device $deviceName not found in paired devices")
            return
        }

        Thread {
            try {
                onStatus("Connecting to $deviceName…")
                adapter.cancelDiscovery()
                val d = device!!
                val uuid = d.uuids?.firstOrNull()?.uuid ?: SPP_UUID
                socket = d.createRfcommSocketToServiceRecord(uuid)
                socket?.connect()
                onStatus("Connected to $deviceName")
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed", e)
                onStatus("Connection failed: ${e.message}")
                try {
                    socket?.close()
                } catch (_: IOException) {
                }
                socket = null
            }
        }.start()
    }

    fun send(text: String) {
        Thread {
            try {
                socket?.outputStream?.write(text.toByteArray())
            } catch (e: IOException) {
                Log.e(TAG, "Send failed", e)
            }
        }.start()
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }
}
