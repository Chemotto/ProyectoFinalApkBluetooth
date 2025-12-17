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
import android.media.audiofx.Visualizer.OnDataCaptureListener
import android.media.audiofx.Visualizer.SUCCESS
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var tvStatus: TextView
    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var rvDevices: RecyclerView
    private lateinit var layoutControls: ScrollView
    
    // Vistas de control
    private lateinit var colorWheel: ColorWheelView
    private lateinit var viewSelectedColor: View
    private lateinit var sbBrightness: SeekBar
    private lateinit var rgProtocol: RadioGroup
    private lateinit var btnPower: Button
    private lateinit var btnFlash: Button
    private lateinit var btnStrobe: Button
    private lateinit var btnFade: Button
    private lateinit var btnMusicMode: Button
    
    // Estado del color y encendido
    private var lastSelectedColor: Int = Color.WHITE
    private var isLightOn = true
    
    private lateinit var deviceAdapter: BluetoothDeviceAdapter
    private val deviceList = ArrayList<BluetoothDevice>()

    // Servicio de Bluetooth Clásico
    private var bluetoothService: BluetoothService? = null
    
    // Variables para Bluetooth LE (BLE)
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var isBleConnected = false
    private var isBleConnecting = false

    private var connectedDeviceName: String? = null
    
    // Visualizador de Audio
    private var visualizer: android.media.audiofx.Visualizer? = null
    private var isMusicModeActive = false

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
                            if (!isBleConnected && !isBleConnecting) {
                                tvStatus.text = "Estado: Inactivo"
                                showControls(false)
                            }
                        }
                    }
                }
                BluetoothService.MESSAGE_READ -> { }
                BluetoothService.MESSAGE_DEVICE_NAME -> {
                    connectedDeviceName = msg.data.getString(BluetoothService.DEVICE_NAME)
                    Toast.makeText(applicationContext, "Conectado a $connectedDeviceName", Toast.LENGTH_SHORT).show()
                }
                BluetoothService.MESSAGE_TOAST -> {
                    if (!isBleConnected && !isBleConnecting) { 
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
            Log.d(TAG, "onConnectionStateChange: status=$status newState=$newState")
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isBleConnecting = false
                    isBleConnected = true
                    connectedDeviceName = gatt.device.name ?: "Dispositivo BLE"
                    runOnUiThread {
                        tvStatus.text = "Conectado. Buscando servicios..."
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        gatt.discoverServices()
                    }, 300)
                    
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isBleConnecting = false
                    isBleConnected = false
                    runOnUiThread {
                        tvStatus.text = "Desconectado (BLE)"
                        showControls(false)
                    }
                    gatt.close()
                }
            } else {
                Log.e(TAG, "Error BLE: $status")
                isBleConnecting = false
                isBleConnected = false
                runOnUiThread {
                    tvStatus.text = "Error de conexión BLE: $status"
                    Toast.makeText(this@MainActivity, "Error al conectar BLE (Status: $status). Reintenta.", Toast.LENGTH_LONG).show()
                }
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                bleWriteCharacteristic = findWriteCharacteristic(gatt)
                runOnUiThread {
                    if (bleWriteCharacteristic != null) {
                        tvStatus.text = "Conectado y listo (BLE)"
                        showControls(true)
                    } else {
                        tvStatus.text = "Conectado (Sin escritura)"
                        Toast.makeText(this@MainActivity, "Error: No se encontró característica de escritura BLE.", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Log.w(TAG, "onServicesDiscovered recibió: $status")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error al descubrir servicios BLE (Status: $status).", Toast.LENGTH_LONG).show()
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // Silencioso, no es necesario hacer nada aquí
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
                    if (bluetoothService?.getState() != BluetoothService.STATE_CONNECTED && !isBleConnected && !isBleConnecting) {
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
        
        colorWheel = findViewById(R.id.colorWheel)
        viewSelectedColor = findViewById(R.id.viewSelectedColor)
        sbBrightness = findViewById(R.id.sbBrightness)
        rgProtocol = findViewById(R.id.rgProtocol)
        btnPower = findViewById(R.id.btnPower)
        btnFlash = findViewById(R.id.btnFlash)
        btnStrobe = findViewById(R.id.btnStrobe)
        btnFade = findViewById(R.id.btnFade)
        btnMusicMode = findViewById(R.id.btnMusicMode)

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
            lastSelectedColor = color
            updatePreviewAndSend()
        }
        
        sbBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updatePreviewColorOnly()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) { }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                updatePreviewAndSend()
            }
        })
        
        btnPower.setOnClickListener { 
            isLightOn = !isLightOn
            sendPowerCommand(isLightOn) 
        }
        
        btnFlash.setOnClickListener { sendEffectCommand(0x25) }
        btnStrobe.setOnClickListener { sendEffectCommand(0x26) }
        btnFade.setOnClickListener { sendEffectCommand(0x27) }
        
        btnMusicMode.setOnClickListener { toggleMusicMode() }

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

    override fun onPause() {
        super.onPause()
        stopVisualizer()
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
        } else {
            rvDevices.visibility = View.VISIBLE
            layoutControls.visibility = View.GONE
            btnScan.isEnabled = true
            btnDisconnect.isEnabled = false
            stopVisualizer() // Detener visualizador si nos desconectamos
        }
    }
    
    private fun logMessage(msg: String) {
        // Silenciado
    }
    
    // --- Lógica de Brillo y Color ---
    
    private fun updatePreviewColorOnly() {
        val brightness = sbBrightness.progress / 100f
        val colorWithBrightness = applyBrightness(lastSelectedColor, brightness)
        viewSelectedColor.setBackgroundColor(colorWithBrightness)
    }
    
    private fun updatePreviewAndSend() {
        val brightness = sbBrightness.progress / 100f
        val colorWithBrightness = applyBrightness(lastSelectedColor, brightness)
        viewSelectedColor.setBackgroundColor(colorWithBrightness)
        sendColor(colorWithBrightness)
    }
    
    private fun applyBrightness(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt()
        val g = (Color.green(color) * factor).toInt()
        val b = (Color.blue(color) * factor).toInt()
        return Color.rgb(r, g, b)
    }
    
    // --- Lógica de Conexión Unificada ---

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
             if (bluetoothAdapter.isDiscovering) {
                 bluetoothAdapter.cancelDiscovery()
             }
        }
        
        val type = device.type
        Log.d(TAG, "Conectando a dispositivo: ${device.name}, Tipo: $type")
        
        Handler(Looper.getMainLooper()).postDelayed({
            if (type != BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                 Log.d(TAG, "Intentando conexión BLE (Por defecto)")
                 isBleConnecting = true
                 bluetoothService?.stop()
                 connectBle(device)
            } else {
                 Log.d(TAG, "Intentando conexión Clásica (Tipo CLASSIC)")
                 isBleConnecting = false
                 disconnectBle()
                 bluetoothService?.connect(device)
            }
        }, 600)
    }
    
    @SuppressLint("MissingPermission")
    private fun connectBle(device: BluetoothDevice) {
        disconnectBle()
        tvStatus.text = "Conectando (BLE)..."
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun disconnectBle() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isBleConnected = false
    }
    
    private fun disconnect() {
        isBleConnecting = false
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
            R.id.rbMagic -> createMagicHomeColorCommand(r, g, b)
            R.id.rbGeneric -> byteArrayOf(r.toByte(), g.toByte(), b.toByte())
            else -> "$r,$g,$b" 
        }
        
        if (command is ByteArray) {
            sendBytes(command)
        } else if (command is String) {
            sendMessage(command)
        }
    }

    private fun sendPowerCommand(on: Boolean) {
        val command = when(rgProtocol.checkedRadioButtonId) {
            // Códigos BLE para Magic Home (0x71...)
            R.id.rbMagic -> if (on) byteArrayOf(0x71, 0x23, 0x0F, 0xA3.toByte()) 
                                else byteArrayOf(0x71, 0x24, 0x0F, 0xA4.toByte())
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
    
    private fun sendEffectCommand(effectCode: Int) {
        val speed = 0x10 // Velocidad media por defecto
        val command = when(rgProtocol.checkedRadioButtonId) {
            // Los códigos de efecto de Magic Home Clásico y BLE son diferentes.
            // Este es el formato BLE: 81 [código] [velocidad] 99
            R.id.rbMagic -> byteArrayOf(0x81.toByte(), effectCode.toByte(), speed.toByte(), 0x99.toByte())
            else -> null
        }
        
        if (command != null) {
            sendBytes(command)
        } else {
            Toast.makeText(this, "Efecto no disponible para este protocolo", Toast.LENGTH_SHORT).show()
        }
    }
    
    // --- Modo Música ---
    
    private fun toggleMusicMode() {
        if (!isMusicModeActive) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startVisualizer()
            } else {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            stopVisualizer()
        }
    }
    
    private fun startVisualizer() {
        try {
            visualizer = android.media.audiofx.Visualizer(0)
            visualizer?.captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1]

            val listener = object : OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: android.media.audiofx.Visualizer, waveform: ByteArray, samplingRate: Int) { }

                override fun onFftDataCapture(visualizer: android.media.audiofx.Visualizer, fft: ByteArray, samplingRate: Int) {
                    val color = fftToColor(fft)
                    sendColor(color)
                    runOnUiThread {
                        viewSelectedColor.setBackgroundColor(color)
                    }
                }
            }

            val rate = android.media.audiofx.Visualizer.getMaxCaptureRate() / 2
            if (visualizer?.setDataCaptureListener(listener, rate, false, true) == SUCCESS) {
                visualizer?.enabled = true
                isMusicModeActive = true
                btnMusicMode.text = "Detener Música"
                Toast.makeText(this, "Modo Música Activado", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar Visualizer", e)
            Toast.makeText(this, "No se pudo iniciar el modo música", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVisualizer() {
        visualizer?.enabled = false
        visualizer?.release()
        visualizer = null
        isMusicModeActive = false
        btnMusicMode.text = "Modo Música"
        Toast.makeText(this, "Modo Música Desactivado", Toast.LENGTH_SHORT).show()
    }

    private fun fftToColor(fft: ByteArray): Int {
        if (fft.isEmpty()) return Color.BLACK
        
        val n = fft.size
        val bass = fft.slice(0 until n / 3).map { it.toInt().and(0xFF) }.average()
        val mid = fft.slice(n/3 until 2*n/3).map { it.toInt().and(0xFF) }.average()
        val treble = fft.slice(2*n/3 until n).map { it.toInt().and(0xFF) }.average()

        val r = (bass * 2).coerceIn(0.0, 255.0).toInt()
        val g = (mid * 2).coerceIn(0.0, 255.0).toInt()
        val b = (treble * 2).coerceIn(0.0, 255.0).toInt()

        return Color.rgb(r, g, b)
    }
    
    // --- Protocolos Específicos ---
    
    private fun createMagicHomeColorCommand(r: Int, g: Int, b: Int): ByteArray {
        // Formato BLE Magic Home: 31 RR GG BB 00 00 0F [Checksum]
        val sum = 0x31 + r + g + b + 0x00 + 0x00 + 0x0F
        val checksum = (sum and 0xFF).toByte()
        return byteArrayOf(0x31, r.toByte(), g.toByte(), b.toByte(), 0x00, 0x00, 0x0F, checksum)
    }

    @SuppressLint("MissingPermission")
    private fun sendMessage(message: String) {
        if (isBleConnected) {
            sendBleBytes(message.toByteArray())
        } else if (bluetoothService?.getState() == BluetoothService.STATE_CONNECTED) {
            bluetoothService?.write(message.toByteArray())
        }
    } 
    
    @SuppressLint("MissingPermission")
    private fun sendBytes(bytes: ByteArray) {
        if (isBleConnected) {
            sendBleBytes(bytes)
        } else if (bluetoothService?.getState() == BluetoothService.STATE_CONNECTED) {
            bluetoothService?.write(bytes)
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
        } 
    }
    
    // --- Utilidades ---
    
    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
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

    // --- Gestión de Permisos ---

    private val requestAudioPermissionLauncher = 
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startVisualizer()
            } else {
                Toast.makeText(this, "Permiso de audio denegado", Toast.LENGTH_SHORT).show()
            }
        }

    private val requestBluetoothPermissionLauncher = 
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                startScanning()
            } else {
                Toast.makeText(this, "Permisos de Bluetooth denegados", Toast.LENGTH_SHORT).show()
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
            requestBluetoothPermissionLauncher.launch(missingPermissions.toTypedArray())
            false
        } else {
            true
        }
    }
}