package com.chema.proyectofinalapkbluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothService(private val handler: Handler) {

    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null
    private var state = STATE_NONE

    companion object {
        const val STATE_NONE = 0       // we're doing nothing
        const val STATE_CONNECTING = 1 // now initiating an outgoing connection
        const val STATE_CONNECTED = 2  // now connected to a remote device
        
        const val MESSAGE_STATE_CHANGE = 1
        const val MESSAGE_READ = 2
        const val MESSAGE_WRITE = 3
        const val MESSAGE_DEVICE_NAME = 4
        const val MESSAGE_TOAST = 5
        
        const val TOAST = "toast"
        const val DEVICE_NAME = "device_name"

        // UUID estándar para SPP (Serial Port Profile)
        // Esto funciona con la mayoría de módulos Bluetooth como HC-05, HC-06, etc.
        private val MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "BluetoothService"
    }

    @Synchronized
    fun getState(): Int {
        return state
    }

    @Synchronized
    private fun setState(state: Int) {
        Log.d(TAG, "setState() $this.state -> $state")
        this.state = state
        handler.obtainMessage(MESSAGE_STATE_CHANGE, state, -1).sendToTarget()
    }

    @Synchronized
    fun start() {
        cancelThreads()
        setState(STATE_NONE)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "connect to: $device")

        // Cancelar cualquier hilo intentando conectar
        if (state == STATE_CONNECTING) {
            connectThread?.cancel()
            connectThread = null
        }

        // Cancelar cualquier hilo actualmente conectado
        if (connectedThread != null) {
            connectedThread?.cancel()
            connectedThread = null
        }

        // Iniciar el hilo para conectar con el dispositivo
        connectThread = ConnectThread(device)
        connectThread?.start()
        setState(STATE_CONNECTING)
    }

    @Synchronized
    fun connected(socket: BluetoothSocket, device: BluetoothDevice) {
        Log.d(TAG, "connected")

        // Cancelar el hilo que completó la conexión
        if (connectThread != null) {
            connectThread?.cancel()
            connectThread = null
        }

        // Cancelar cualquier hilo conectado anteriormente
        if (connectedThread != null) {
            connectedThread?.cancel()
            connectedThread = null
        }

        // Iniciar el hilo para administrar la conexión y realizar transmisiones
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()

        // Enviar el nombre del dispositivo conectado a la Actividad UI
        val msg = handler.obtainMessage(MESSAGE_DEVICE_NAME)
        val bundle = Bundle()
        @SuppressLint("MissingPermission")
        val name = device.name ?: "Dispositivo"
        bundle.putString(DEVICE_NAME, name)
        msg.data = bundle
        handler.sendMessage(msg)

        setState(STATE_CONNECTED)
    }

    @Synchronized
    fun stop() {
        Log.d(TAG, "stop")
        cancelThreads()
        setState(STATE_NONE)
    }
    
    fun write(out: ByteArray) {
        // Crear objeto temporal sincronizado
        var r: ConnectedThread?
        synchronized(this) {
            if (state != STATE_CONNECTED) return
            r = connectedThread
        }
        // Realizar la escritura sin bloqueo (unsynchronized)
        r?.write(out)
    }

    private fun cancelThreads() {
        if (connectThread != null) {
            connectThread?.cancel()
            connectThread = null
        }
        if (connectedThread != null) {
            connectedThread?.cancel()
            connectedThread = null
        }
    }

    @SuppressLint("MissingPermission")
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val socket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(MY_UUID)
        }

        override fun run() {
            Log.i(TAG, "BEGIN mConnectThread")
            // Cancelar descubrimiento porque ralentiza la conexión
            BluetoothAdapter.getDefaultAdapter().cancelDiscovery()

            try {
                // Conectar
                socket?.connect()
            } catch (e: IOException) {
                Log.e(TAG, "Socket connect failed", e)
                try {
                    socket?.close()
                } catch (e2: IOException) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2)
                }
                connectionFailed()
                return
            }

            // Resetear el hilo de conexión ya que hemos terminado
            synchronized(this@BluetoothService) {
                connectThread = null
            }

            // Iniciar el hilo conectado
            socket?.let { connected(it, device) }
        }

        fun cancel() {
            try {
                socket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "close() of connect socket failed", e)
            }
        }
    }

    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val inputStream: InputStream = socket.inputStream
        private val outputStream: OutputStream = socket.outputStream
        private val buffer: ByteArray = ByteArray(1024)

        override fun run() {
            Log.i(TAG, "BEGIN mConnectedThread")
            while (state == STATE_CONNECTED) {
                try {
                    // Leer desde el InputStream
                    val bytes = inputStream.read(buffer)
                    // Enviar los bytes obtenidos a la Actividad UI
                    handler.obtainMessage(MESSAGE_READ, bytes, -1, buffer).sendToTarget()
                } catch (e: IOException) {
                    Log.e(TAG, "disconnected", e)
                    connectionLost()
                    break
                }
            }
        }

        fun write(bytes: ByteArray) {
            try {
                outputStream.write(bytes)
                // Compartir el mensaje enviado con la UI Activity
                handler.obtainMessage(MESSAGE_WRITE, -1, -1, bytes).sendToTarget()
            } catch (e: IOException) {
                Log.e(TAG, "Exception during write", e)
            }
        }

        fun cancel() {
            try {
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "close() of connect socket failed", e)
            }
        }
    }

    private fun connectionFailed() {
        val msg = handler.obtainMessage(MESSAGE_TOAST)
        val bundle = Bundle()
        bundle.putString(TOAST, "No se pudo conectar al dispositivo")
        msg.data = bundle
        handler.sendMessage(msg)
        setState(STATE_NONE)
    }

    private fun connectionLost() {
        val msg = handler.obtainMessage(MESSAGE_TOAST)
        val bundle = Bundle()
        bundle.putString(TOAST, "Conexión perdida")
        msg.data = bundle
        handler.sendMessage(msg)
        setState(STATE_NONE)
    }
}