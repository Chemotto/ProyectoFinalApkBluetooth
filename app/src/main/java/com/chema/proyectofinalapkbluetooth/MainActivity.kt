package com.chema.proyectofinalapkbluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var tvStatus: TextView
    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var rvDevices: RecyclerView
    private lateinit var layoutControls: ScrollView
    private lateinit var tvLog: TextView
    
    // Vistas de control
    private lateinit var colorWheel: ColorWheelView
    private lateinit var viewSelectedColor: View
    private lateinit var rgProtocol: RadioGroup
    private lateinit var btnOn: Button
    private lateinit var btnOff: Button
    
    private lateinit var deviceAdapter: BluetoothDeviceAdapter
    private val deviceList = ArrayList<BluetoothDevice>()

    // Servicio de Bluetooth Clásico
    private var bluetoothService: BluetoothService? = null
    
    // Variables para Bluetooth LE (BLE)
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var isBleConnected = false

    private var connectedDeviceName: String? = null
    private val stringBuffer = StringBuilder()

    companion object {
        private const val TAG = "MainActivity"
    }

    // Handler para recibir mensajes del BluetoothService (Clásico)
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                BluetoothService.MESSAGE_STATE_CHANGE -> {
                    when (msg.arg1) {
                        BluetoothService.STATE_CONNECTED -> {
                            tvStatus.text = "Estado: Conectado a $connectedDeviceName (Clásico)"
                            deviceAdapter.notifyDataSetChanged()
                            showControls(true)
                        }
                        BluetoothService.STATE_CONNECTING -> {
                            tvStatus.text = "Estado: Conectando..."
                        }
                        BluetoothService.STATE_NONE -> {
                            if (!isBleConnected) {
                                tvStatus.text = "Estado: Inactivo"
                                showControls(false)
                            }
                        }
                    }
                }
                BluetoothService.MESSAGE_READ -> {
                    val readBuf = msg.obj as ByteArray
                    val readMessage = String(readBuf, 0, msg.arg1)
                    logMessage("RX: $readMessage")
                }
                BluetoothService.MESSAGE_DEVICE_NAME -> {
                    connectedDeviceName = msg.data.getString(BluetoothService.DEVICE_NAME)
                    Toast.makeText(applicationContext, "Conectado a $connectedDeviceName", Toast.LENGTH_SHORT).show()
                }
                BluetoothService.MESSAGE_TOAST -> {
                    if (!isBleConnected) { 
                        Toast.makeText(applicationContext, msg.data.getString(BluetoothService.TOAST), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Callbacks para Bluetooth LE (BLE)
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isBleConnected = true
                connectedDeviceName = gatt.device.name ?: "Dispositivo BLE"
                runOnUiThread {
                    tvStatus.text = "Conectado a $connectedDeviceName. Buscando servicios..."
                }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isBleConnected = false
                runOnUiThread {
                    tvStatus.text = "Desconectado (BLE)"
                    showControls(false)
                    Toast.makeText(this@MainActivity, "Desconectado", Toast.LENGTH_SHORT).show()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                bleWriteCharacteristic = findWriteCharacteristic(gatt)
                runOnUiThread {
                    if (bleWriteCharacteristic != null) {
                        tvStatus.text = "Conectado y listo (BLE)"
                        showControls(true)
                        logMessage("Servicio de escritura encontrado: ${bleWriteCharacteristic?.uuid}")
                    } else {
                        tvStatus.text = "Conectado (Sin escritura)"
                        logMessage("Error: No se encontró característica de escritura")
                    }
                }
            } else {
                Log.w(TAG, "onServicesDiscovered recibió: $status")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                 runOnUiThread { logMessage("TX (BLE): Datos enviados") }
            } else {
                 runOnUiThread { logMessage("Error al enviar BLE: $status") }
            }
        }
    }

    // Receiver para detectar dispositivos encontrados
    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        val typeStr = when(it.type) {
                            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Clásico"
                            BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                            BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
                            else -> "Desconocido"
                        }
                        Log.d(TAG, "Encontrado: ${it.name} [${it.address}] Tipo: $typeStr")
                        if (deviceList.none { d -> d.address == it.address }) {
                            deviceList.add(it)
                            deviceAdapter.notifyItemInserted(deviceList.size - 1)
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    tvStatus.text = "Estado: Escaneando..."
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (bluetoothService?.getState() != BluetoothService.STATE_CONNECTED && !isBleConnected) {
                         if (deviceList.isEmpty()) {
                            tvStatus.text = "Estado: Escaneo finalizado (Sin resultados)"
                        } else {
                            tvStatus.text = "Estado: Escaneo finalizado (${deviceList.size} disp.)"
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar vistas
        tvStatus = findViewById(R.id.tvStatus)
        btnScan = findViewById(R.id.btnScan)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        rvDevices = findViewById(R.id.rvDevices)
        layoutControls = findViewById(R.id.layoutControls)
        tvLog = findViewById(R.id.tvLog)
        
        colorWheel = findViewById(R.id.colorWheel)
        viewSelectedColor = findViewById(R.id.viewSelectedColor)
        rgProtocol = findViewById(R.id.rgProtocol)
        btnOn = findViewById(R.id.btnOn)
        btnOff = findViewById(R.id.btnOff)

        // Configurar RecyclerView
        deviceAdapter = BluetoothDeviceAdapter(deviceList) { device ->
            connectToDevice(device)
        }
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        // Inicializar BluetoothAdapter
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Inicializar BluetoothService (Clásico)
        bluetoothService = BluetoothService(this, handler)

        // Configurar Listeners de UI
        btnScan.setOnClickListener {
            if (checkPermissions()) {
                startScanning()
            }
        }
        
        btnDisconnect.setOnClickListener {
            disconnect()
        }
        
        colorWheel.onColorChangedListener = { color ->
            viewSelectedColor.setBackgroundColor(color)
            sendColor(color)
        }
        
        btnOn.setOnClickListener { sendPowerCommand(true) }
        btnOff.setOnClickListener { sendPowerCommand(false) }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(receiver, filter)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        if (bluetoothService == null) {
            bluetoothService = BluetoothService(this, handler)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        bluetoothService?.stop()
        disconnectBle()
    }
    
    private fun showControls(connected: Boolean) {
        if (connected) {
            rvDevices.visibility = View.GONE
            layoutControls.visibility = View.VISIBLE
            btnScan.isEnabled = false
            btnDisconnect.isEnabled = true
            stringBuffer.clear() 
            tvLog.text = ""
        } else {
            rvDevices.visibility = View.VISIBLE
            layoutControls.visibility = View.GONE
            btnScan.isEnabled = true
            btnDisconnect.isEnabled = false
        }
    }
    
    private fun logMessage(msg: String) {
        stringBuffer.append(msg + "\n")
        tvLog.text = stringBuffer.toString()
        layoutControls.post {
            layoutControls.fullScroll(View.FOCUS_DOWN)
        }
    }
    
    // --- Lógica de Conexión Unificada ---

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        // Detener escaneo
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
             if (bluetoothAdapter.isDiscovering) {
                 bluetoothAdapter.cancelDiscovery()
             }
        }
        
        val type = device.type
        if (type == BluetoothDevice.DEVICE_TYPE_LE) {
            connectBle(device)
        } else {
            bluetoothService?.connect(device)
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun connectBle(device: BluetoothDevice) {
        disconnectBle()
        tvStatus.text = "Conectando (BLE)..."
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }
    
    @SuppressLint("MissingPermission")
    private fun disconnectBle() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isBleConnected = false
    }
    
    private fun disconnect() {
        bluetoothService?.stop()
        disconnectBle()
        showControls(false)
        tvStatus.text = "Desconectado"
    }
    
    // --- Envío de Comandos BLE ---

    private fun sendColor(color: Int) {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        val command = when(rgProtocol.checkedRadioButtonId) {
            R.id.rbMagic -> createMagicHomeCommand(r, g, b)
            R.id.rbGeneric -> byteArrayOf(r.toByte(), g.toByte(), b.toByte())
            else -> "$r,$g,$b" // Formato Texto
        }
        
        if (command is ByteArray) {
            sendBytes(command)
        } else if (command is String) {
            sendMessage(command)
        }
    }

    private fun sendPowerCommand(on: Boolean) {
        val command = when(rgProtocol.checkedRadioButtonId) {
            R.id.rbMagic -> if (on) byteArrayOf(0x7e, 0x04, 0x04, 0xf0.toByte(), 0x00, 0x01, 0xff.toByte(), 0x00, 0xef.toByte()) 
                                else byteArrayOf(0x7e, 0x04, 0x04, 0x00, 0x00, 0x00, 0xff.toByte(), 0x00, 0xef.toByte())
            R.id.rbGeneric -> if (on) byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) 
                                else byteArrayOf(0x00, 0x00, 0x00)
            else -> if (on) "ON" else "OFF"
        }

        if (command is ByteArray) {
            sendBytes(command)
        } else if (command is String) {
            sendMessage(command)
        }
    }
    
    // --- Protocolos Específicos ---
    
    private fun createMagicHomeCommand(r: Int, g: Int, b: Int): ByteArray {
        // Formato: 56 [r] [g] [b] 00 f0 aa
        return byteArrayOf(0x56.toByte(), r.toByte(), g.toByte(), b.toByte(), 0x00, 0xf0.toByte(), 0xaa.toByte())
    }

    @SuppressLint("MissingPermission")
    private fun sendMessage(message: String) {
        if (isBleConnected) {
            sendBleBytes(message.toByteArray())
            logMessage("TX (Texto): $message")
        } else if (bluetoothService?.getState() == BluetoothService.STATE_CONNECTED) {
            bluetoothService?.write(message.toByteArray())
            logMessage("TX (Texto): $message")
        } else {
            Toast.makeText(this, "No conectado", Toast.LENGTH_SHORT).show()
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun sendBytes(bytes: ByteArray) {
        if (isBleConnected) {
            sendBleBytes(bytes)
            logMessage("TX (Hex): ${bytesToHex(bytes)}")
        } else if (bluetoothService?.getState() == BluetoothService.STATE_CONNECTED) {
            bluetoothService?.write(bytes)
            logMessage("TX (Hex): ${bytesToHex(bytes)}")
        } else {
            Toast.makeText(this, "No conectado", Toast.LENGTH_SHORT).show()
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun sendBleBytes(bytes: ByteArray) {
        val characteristic = bleWriteCharacteristic
        if (bluetoothGatt != null && characteristic != null) {
            characteristic.value = bytes
            val properties = characteristic.properties
            characteristic.writeType = if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            bluetoothGatt?.writeCharacteristic(characteristic)
        } else {
            logMessage("Error: No se encontró característica de escritura")
        }
    }
    
    // --- Utilidades ---
    
    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02X ", b))
        }
        return sb.toString()
    }
    
    private fun findWriteCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        for (service in gatt.services) {
            for (characteristic in service.characteristics) {
                val props = characteristic.properties
                if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    return characteristic
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Por favor, activa el Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        deviceList.clear()
        val pairedDevices = bluetoothAdapter.bondedDevices
        if (!pairedDevices.isNullOrEmpty()) {
            deviceList.addAll(pairedDevices)
        }
        deviceAdapter.notifyDataSetChanged()
        
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
        bluetoothAdapter.startDiscovery()
    }

    // Gestión de permisos
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                startScanning()
            } else {
                Toast.makeText(this, "Permisos denegados. No se puede escanear.", Toast.LENGTH_SHORT).show()
            }
        }

    private fun checkPermissions(): Boolean {
        val permissionsToCheck = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToCheck.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToCheck.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsToCheck.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToCheck.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val missingPermissions = permissionsToCheck.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
            false
        } else {
            true
        }
    }
}