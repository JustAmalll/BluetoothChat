package dev.amal.bluetoothchat.data.chat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import dev.amal.bluetoothchat.data.chat.mappers.toBluetoothDeviceDomain
import dev.amal.bluetoothchat.data.chat.mappers.toByteArray
import dev.amal.bluetoothchat.data.chat.receivers.BluetoothStateReceiver
import dev.amal.bluetoothchat.data.chat.receivers.FoundDeviceReceiver
import dev.amal.bluetoothchat.domain.chat.BluetoothController
import dev.amal.bluetoothchat.domain.chat.BluetoothDeviceDomain
import dev.amal.bluetoothchat.domain.chat.BluetoothMessage
import dev.amal.bluetoothchat.domain.chat.ConnectionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.*

@SuppressLint("MissingPermission")
class AndroidBluetoothController(
    private val context: Context
) : BluetoothController {

    private val bluetoothManager by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }
    private val bluetoothAdapter by lazy {
        bluetoothManager?.adapter
    }

    private var dataTransferService: BluetoothDataTransferService? = null

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean>
        get() = _isConnected.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    override val scannedDevices: StateFlow<List<BluetoothDeviceDomain>>
        get() = _scannedDevices.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    override val pairedDevices: StateFlow<List<BluetoothDeviceDomain>>
        get() = _pairedDevices.asStateFlow()

    private val _errors = MutableSharedFlow<String>()
    override val errors: SharedFlow<String>
        get() = _errors.asSharedFlow()

    private val foundDeviceReceiver = FoundDeviceReceiver { device ->
        _scannedDevices.update { devices ->
            val newDevice = device.toBluetoothDeviceDomain()
            if (newDevice in devices) devices else devices + newDevice
        }
    }

    private val bluetoothStateReceiver = BluetoothStateReceiver { isConnected, device ->
        if (bluetoothAdapter?.bondedDevices?.contains(device) == true) {
            _isConnected.update { isConnected }
        } else CoroutineScope(Dispatchers.IO).launch {
            _errors.emit("Can't connect to a non-paired device.")
        }
    }

    private var currentServerSocket: BluetoothServerSocket? = null
    private var currentClientSocket: BluetoothSocket? = null

    init {
        updatePairedDevices()
        context.registerReceiver(
            bluetoothStateReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        )
    }

    override fun startDiscovery() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return

        context.registerReceiver(
            foundDeviceReceiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND)
        )
        updatePairedDevices()

        bluetoothAdapter?.startDiscovery()
    }

    override fun stopDiscovery() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
        bluetoothAdapter?.cancelDiscovery()
    }

    override fun startBluetoothServer(): Flow<ConnectionResult> = flow {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            throw SecurityException("No BLUETOOTH_CONNECT permission")
        }
        currentServerSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(
            "chat_service", UUID.fromString(SERVICE_UUID)
        )
        var shouldLoop = true
        while (shouldLoop) {
            currentClientSocket = try {
                currentServerSocket?.accept()
            } catch (exception: IOException) {
                shouldLoop = false
                null
            }
            emit(ConnectionResult.ConnectionEstablished)
            currentClientSocket?.let { bluetoothSocket ->
                currentServerSocket?.close()

                val service = BluetoothDataTransferService(bluetoothSocket)
                dataTransferService = service

                emitAll(
                    service
                        .listenForIncomingMessages()
                        .map { message -> ConnectionResult.TransferSucceeded(message) }
                )
            }
        }
    }.onCompletion {
        closeConnection()
    }.flowOn(Dispatchers.IO)

    override fun connectToDevice(device: BluetoothDeviceDomain): Flow<ConnectionResult> = flow {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            throw SecurityException("No BLUETOOTH_CONNECT permission")
        }

        currentClientSocket = bluetoothAdapter
            ?.getRemoteDevice(device.address)
            ?.createRfcommSocketToServiceRecord(UUID.fromString(SERVICE_UUID))
        stopDiscovery()

        currentClientSocket?.let { socket ->
            try {
                socket.connect()
                emit(ConnectionResult.ConnectionEstablished)

                BluetoothDataTransferService(socket).also {
                    dataTransferService = it
                    emitAll(
                        it.listenForIncomingMessages()
                            .map { message -> ConnectionResult.TransferSucceeded(message) }
                    )
                }
            } catch (exception: IOException) {
                socket.close()
                currentClientSocket = null
                emit(ConnectionResult.Error("Connection was interrupted"))
            }
        }
    }.onCompletion {
        closeConnection()
    }.flowOn(Dispatchers.IO)

    override suspend fun trySendMessage(message: String): BluetoothMessage? {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return null
        if (dataTransferService == null) return null

        val bluetoothMessage = BluetoothMessage(
            message = message,
            senderName = bluetoothAdapter?.name ?: "Unknown name",
            isFromLocalUser = true
        )

        dataTransferService?.sendMessage(bluetoothMessage.toByteArray())

        return bluetoothMessage
    }

    override fun closeConnection() {
        currentClientSocket?.close()
        currentServerSocket?.close()
        currentClientSocket = null
        currentServerSocket = null
    }

    override fun release() {
        context.unregisterReceiver(foundDeviceReceiver)
        context.unregisterReceiver(bluetoothStateReceiver)
        closeConnection()
    }

    private fun updatePairedDevices() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            return
        }
        bluetoothAdapter
            ?.bondedDevices
            ?.map { it.toBluetoothDeviceDomain() }
            ?.also { devices -> _pairedDevices.update { devices } }
    }

    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val SERVICE_UUID = "27b7d1da-08c7-4505-a6d1-2459987e5e2d"
    }
}