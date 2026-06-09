package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.ble.BleDevice
import com.example.ble.SmartPottyBleManager

class BleViewModel(application: Application) : AndroidViewModel(application) {
    private val bleManager = SmartPottyBleManager(application)

    val scannedDevices = bleManager.scannedDevices
    val connectionState = bleManager.connectionState
    val batteryLevel = bleManager.batteryLevel
    val fillLevel = bleManager.fillLevel
    val connectedDevice = bleManager.connectedDevice

    fun startScan() {
        bleManager.startScan()
    }

    fun stopScan() {
        bleManager.stopScan()
    }

    fun connect(device: BleDevice) {
        if (device.address == "00:11:22:33:44") {
            // Simulate connection
            // We use reflection or just mutable flows to simulate
            simulateConnection(device)
            return
        }
        bleManager.connectToDevice(device)
    }

    private fun simulateConnection(device: BleDevice) {
        bleManager.simulateConnectionSuccess(device)
    }

    fun setSimulatedFillLevel(level: Int) {
        bleManager.simulateFillLevel(level)
    }

    fun disconnect() {
        bleManager.disconnect()
    }
}

class BleViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BleViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BleViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
