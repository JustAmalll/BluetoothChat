package dev.amal.bluetoothchat.presentation

import dev.amal.bluetoothchat.domain.chat.BluetoothDevice
import dev.amal.bluetoothchat.domain.chat.BluetoothMessage

data class BluetoothUiState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val errorMessage: String? = null,
    val scannedDevices: List<BluetoothDevice> = emptyList(),
    val pairedDevices: List<BluetoothDevice> = emptyList(),
    val messages: List<BluetoothMessage> = emptyList()
)