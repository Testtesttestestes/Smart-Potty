package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

@SuppressLint("MissingPermission")
class SmartPottyBleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private val _fillLevel = MutableStateFlow<Int?>(null)
    val fillLevel: StateFlow<Int?> = _fillLevel.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BleDevice?>(null)
    val connectedDevice: StateFlow<BleDevice?> = _connectedDevice.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let {
                val device = BleDevice(it.device, it.rssi)
                _scannedDevices.update { list ->
                    if (list.none { d -> d.address == device.address }) {
                        list + device
                    } else {
                        // Update RSSI
                        list.map { d -> if (d.address == device.address) device else d }
                    }
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = ConnectionState.CONNECTED
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = ConnectionState.DISCONNECTED
                _batteryLevel.value = null
                _connectedDevice.value = null
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Battery Service: 0x180F
                val batteryService = gatt.getService(UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB"))
                if (batteryService != null) {
                    val batteryChar = batteryService.getCharacteristic(UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB"))
                    if (batteryChar != null) {
                        gatt.readCharacteristic(batteryChar)
                    }
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.uuid == UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")) {
                    val level = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                    _batteryLevel.value = level
                }
            }
        }
    }

    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        if (isScanning) return
        
        _scannedDevices.value = emptyList()
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        scanner.startScan(scanCallback)
        isScanning = true

        handler.postDelayed({ stopScan() }, 10000)
    }

    fun stopScan() {
        if (!isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        scanner.stopScan(scanCallback)
        isScanning = false
    }

    fun connectToDevice(device: BleDevice) {
        stopScan()
        val btDevice = bluetoothAdapter?.getRemoteDevice(device.address) ?: return
        _connectionState.value = ConnectionState.CONNECTING
        _connectedDevice.value = device
        bluetoothGatt = btDevice.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        if (_connectedDevice.value?.address == "00:11:22:33:44") {
            _connectionState.value = ConnectionState.DISCONNECTED
            _connectedDevice.value = null
            _batteryLevel.value = null
            _fillLevel.value = null
            return
        }
        bluetoothGatt?.disconnect()
    }

    fun simulateConnectionDrop() {
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDevice.value = null
        _batteryLevel.value = null
        _fillLevel.value = null
    }

    fun simulateConnectionSuccess(device: BleDevice) {
        _connectionState.value = ConnectionState.CONNECTED
        _connectedDevice.value = device
        _batteryLevel.value = 85
        _fillLevel.value = 0
    }

    fun simulateFillLevel(level: Int) {
        _fillLevel.value = level
    }
}
