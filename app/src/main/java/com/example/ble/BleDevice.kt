package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int
) {
    @SuppressLint("MissingPermission")
    constructor(device: BluetoothDevice, rssi: Int) : this(
        address = device.address,
        name = device.name,
        rssi = rssi
    )
}
