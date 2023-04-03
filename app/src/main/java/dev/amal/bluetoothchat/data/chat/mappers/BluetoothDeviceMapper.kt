package dev.amal.bluetoothchat.data.chat.mappers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import dev.amal.bluetoothchat.domain.chat.BluetoothDeviceDomain

@SuppressLint("MissingPermission")
fun BluetoothDevice.toBluetoothDeviceDomain() : BluetoothDeviceDomain {
    return BluetoothDeviceDomain(name = name, address = address)
}