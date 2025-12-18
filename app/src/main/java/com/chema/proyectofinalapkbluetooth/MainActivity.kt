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
import android.content.SharedPreferences
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
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    
    // Vistas
    private lateinit var chipStatus: Chip
    private lateinit var btnScan: MaterialButton
    private lateinit var rvDevices: RecyclerView
    private lateinit var colorWheel: ColorWheelView
    private lateinit var viewSelectedColor: View
    private lateinit var sbBrightness: SeekBar
    private lateinit var btnPower: MaterialButton
    private lateinit var btnFlash: MaterialButton
    private lateinit var btnStrobe: MaterialButton
    private lateinit var btnFade: MaterialButton
    private lateinit var btnMusicMode: MaterialButton
    
    // Estado
    private var lastSelectedColor: Int = Color.WHITE
    private var isLightOn = true
    private var isMusicModeActive = false
    private var connectingDevice: BluetoothDevice? = null 
    
    private lateinit var deviceAdapter: BluetoothDeviceAdapter
    private val deviceList = ArrayList<BluetoothDevice>()

    // Bluetooth Services
    private var bluetoothService: BluetoothService? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var isBleConnected = false
    private var isBleConnecting = false

    private var connectedDeviceName: String? = null
    
    // Audio
    private var visualizer: android.media.audiofx.Visualizer? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "LedControlPrefs"
        private const val KEY_LAST_DEVICE = "last_device_address"
    }

    // Handler Bluetooth Clásico
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                BluetoothService.MESSAGE_STATE_CHANGE -> {
                    when (msg.arg1) {
                        BluetoothService.STATE_CONNECTED -> {
                            updateConnectionStatus(true, "Clásico")
                            connectingDevice?.let { saveLastDevice(it.address) }
                        }
                        BluetoothService.STATE_CONNECTING -> updateStatusText("Conectando...")
                        BluetoothService.STATE_NONE -> {
                            if (!isBleConnected && !isBleConnecting) updateConnectionStatus(false)
                        }
                    }
                }
                BluetoothService.MESSAGE_DEVICE_NAME -> {
                    connectedDeviceName = msg.data.getString(BluetoothService.DEVICE_NAME)
                    Toast.makeText(applicationContext, "Conectado a $connectedDeviceName", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Callback BLE
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isBleConnecting = false
                    isBleConnected = true
                    connectedDeviceName = gatt.device.name ?: "BLE Device"
                    runOnUiThread { 
                        updateStatusText("Descubriendo servicios...")
                        saveLastDevice(gatt.device.address)
                    }
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isBleConnecting = false
                    isBleConnected = false
                    runOnUiThread { updateConnectionStatus(false) }
                    gatt.close()
                }
            } else {
                Log.e(TAG, "Error BLE: $status")
                isBleConnecting = false
                isBleConnected = false
                runOnUiThread {
                    updateConnectionStatus(false)
                    if (status == 133) {
                         Toast.makeText(this@MainActivity, "Error 133: Reinicia el Bluetooth.", Toast.LENGTH_LONG).show()
                    }
                }
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                bleWriteCharacteristic = findWriteCharacteristic(gatt)
                runOnUiThread {
                    if (bleWriteCharacteristic != null) {
                        updateConnectionStatus(true, "BLE")
                    } else {
                        updateStatusText("Conectado (Sin escritura)")
                    }
                }
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (deviceList.none { d -> d.address == it.address }) {
                            deviceList.add(it)
                            deviceAdapter.notifyItemInserted(deviceList.size - 1)
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> updateStatusText("Escaneando...")
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                     if (!isBleConnected && bluetoothService?.getState() != BluetoothService.STATE_CONNECTED) {
                         updateStatusText("Escaneo finalizado")
                         btnScan.isEnabled = true
                         btnScan.text = "Escanear"
                     }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar Vistas
        chipStatus = findViewById(R.id.chipStatus)
        btnScan = findViewById(R.id.btnScan)
        rvDevices = findViewById(R.id.rvDevices)
        colorWheel = findViewById(R.id.colorWheel)
        viewSelectedColor = findViewById(R.id.viewSelectedColor)
        sbBrightness = findViewById(R.id.sbBrightness)
        btnPower = findViewById(R.id.btnPower)
        btnFlash = findViewById(R.id.btnFlash)
        btnStrobe = findViewById(R.id.btnStrobe)
        btnFade = findViewById(R.id.btnFade)
        btnMusicMode = findViewById(R.id.btnMusicMode)

        updateConnectionStatus(false)
        
        deviceAdapter = BluetoothDeviceAdapter(deviceList) { device -> connectToDevice(device) }
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothService = BluetoothService(this, handler)

        // Listeners
        btnScan.setOnClickListener {
            if (checkPermissions()) startScanning()
        }
        
        colorWheel.onColorChangedListener = { color ->
            lastSelectedColor = color
            updatePreviewAndSend()
        }
        
        sbBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updatePreviewColorOnly()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { updatePreviewAndSend() }
        })
        
        // Listener para botones de colores favoritos
        findViewById<View>(R.id.btnColor1).setOnClickListener { setFavoriteColor(Color.CYAN) }
        findViewById<View>(R.id.btnColor2).setOnClickListener { setFavoriteColor(Color.MAGENTA) }
        findViewById<View>(R.id.btnColor3).setOnClickListener { setFavoriteColor(Color.parseColor("#8A2BE2")) } // BlueViolet
        findViewById<View>(R.id.btnColor4).setOnClickListener { setFavoriteColor(Color.parseColor("#00FF7F")) } // SpringGreen

        btnPower.setOnClickListener { 
            isLightOn = !isLightOn
            sendPowerCommand(isLightOn)
            btnPower.text = if (isLightOn) "Encendido" else "Apagado"
            btnPower.alpha = if (isLightOn) 1.0f else 0.5f
        }
        
        btnFlash.setOnClickListener { sendEffectCommand(0x25) }
        btnStrobe.setOnClickListener { sendEffectCommand(0x26) }
        btnFade.setOnClickListener { sendEffectCommand(0x27) }
        btnMusicMode.setOnClickListener { toggleMusicMode() }

        // Inicializar
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(receiver, filter)

        // Comprobar permisos y autoconectar
        if (checkPermissions()) {
            if (!attemptAutoConnect()) {
                startScanning()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        bluetoothService?.stop()
        disconnectBle()
        stopVisualizer()
    }
    
    // --- Auto Connect ---

    private fun saveLastDevice(address: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_DEVICE, address).apply()
    }

    private fun getLastDevice(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_DEVICE, null)
    }

    @SuppressLint("MissingPermission")
    private fun attemptAutoConnect(): Boolean {
        val lastAddress = getLastDevice()
        if (lastAddress != null) {
            try {
                if (BluetoothAdapter.checkBluetoothAddress(lastAddress)) {
                    val device = bluetoothAdapter.getRemoteDevice(lastAddress)
                    Log.d(TAG, "Autoconectando a: $lastAddress")
                    connectToDevice(device)
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en autoconexión", e)
            }
        }
        return false
    }
    
    // --- UI Helpers ---
    
    private fun updateConnectionStatus(connected: Boolean, type: String = "") {
        if (connected) {
            chipStatus.text = "Conectado ($type)"
            chipStatus.setChipBackgroundColorResource(android.R.color.holo_green_dark)
            btnScan.isEnabled = false
            btnScan.text = "Desconectar"
            btnScan.setOnClickListener { disconnect() }
            rvDevices.visibility = View.GONE
        } else {
            chipStatus.text = "Desconectado"
            chipStatus.setChipBackgroundColorResource(android.R.color.holo_red_dark)
            btnScan.isEnabled = true
            btnScan.text = "Escanear"
            btnScan.setOnClickListener { if (checkPermissions()) startScanning() }
            rvDevices.visibility = View.VISIBLE
        }
    }
    
    private fun updateStatusText(text: String) {
        chipStatus.text = text
    }

    // --- Bluetooth Logic ---

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
             if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()
        }
        
        connectingDevice = device
        updateStatusText("Conectando...")
        
        Handler(Looper.getMainLooper()).postDelayed({
            if (device.type != BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                 isBleConnecting = true
                 bluetoothService?.stop()
                 connectBle(device)
            } else {
                 isBleConnecting = false
                 disconnectBle()
                 bluetoothService?.connect(device)
            }
        }, 500)
    }
    
    @SuppressLint("MissingPermission")
    private fun connectBle(device: BluetoothDevice) {
        disconnectBle()
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
        updateConnectionStatus(false)
        stopVisualizer()
    }
    
    // --- Comandos ---
    
    private fun setFavoriteColor(color: Int) {
        lastSelectedColor = color
        updatePreviewAndSend()
    }
    
    private fun updatePreviewColorOnly() {
        val brightness = sbBrightness.progress / 100f
        viewSelectedColor.setBackgroundColor(applyBrightness(lastSelectedColor, brightness))
    }
    
    private fun updatePreviewAndSend() {
        val brightness = sbBrightness.progress / 100f
        val finalColor = applyBrightness(lastSelectedColor, brightness)
        viewSelectedColor.setBackgroundColor(finalColor)
        sendColor(finalColor)
    }
    
    private fun applyBrightness(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt()
        val g = (Color.green(color) * factor).toInt()
        val b = (Color.blue(color) * factor).toInt()
        return Color.rgb(r, g, b)
    }

    private fun sendColor(color: Int) {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        
        // Protocolo BLE Magic Home: 0x31 R G B 00 00 0F Checksum
        val sum = 0x31 + r + g + b + 0x00 + 0x00 + 0x0F
        val checksum = (sum and 0xFF).toByte()
        val command = byteArrayOf(0x31, r.toByte(), g.toByte(), b.toByte(), 0x00, 0x00, 0x0F, checksum)
        
        sendBytes(command)
    }

    private fun sendPowerCommand(on: Boolean) {
        // Protocolo BLE Magic Home: 0x71 23/24 0F A3/A4
        val command = if (on) byteArrayOf(0x71, 0x23, 0x0F, 0xA3.toByte()) 
                      else byteArrayOf(0x71, 0x24, 0x0F, 0xA4.toByte())
        sendBytes(command)
    }
    
    private fun sendEffectCommand(effectCode: Int) {
        val speed = 0x10.toByte()
        // Protocolo Magic Home Clásico/BLE Effect
        val command = byteArrayOf(0x56, effectCode.toByte(), speed, 0xAA.toByte())
        sendBytes(command)
    }

    @SuppressLint("MissingPermission")
    private fun sendBytes(bytes: ByteArray) {
        if (isBleConnected) {
            val characteristic = bleWriteCharacteristic
            if (bluetoothGatt != null && characteristic != null) {
                characteristic.value = bytes
                
                // Detección automática de capacidades
                val properties = characteristic.properties
                if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                
                bluetoothGatt?.writeCharacteristic(characteristic)
            }
        } else if (bluetoothService?.getState() == BluetoothService.STATE_CONNECTED) {
            bluetoothService?.write(bytes)
        }
    }
    
    private fun findWriteCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        for (service in gatt.services) {
            for (characteristic in service.characteristics) {
                if ((characteristic.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                    return characteristic
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        deviceList.clear()
        deviceAdapter.notifyDataSetChanged()
        bluetoothAdapter.startDiscovery()
    }

    // --- Audio ---
    
    private fun toggleMusicMode() {
        if (!isMusicModeActive) {
            if (checkAudioPermissions()) startVisualizer()
        } else {
            stopVisualizer()
        }
    }
    
    private fun startVisualizer() {
        try {
            visualizer = android.media.audiofx.Visualizer(0)
            visualizer?.captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1]
            visualizer?.setDataCaptureListener(object : OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: android.media.audiofx.Visualizer, w: ByteArray, s: Int) {}
                override fun onFftDataCapture(v: android.media.audiofx.Visualizer, fft: ByteArray, s: Int) {
                    val color = fftToColor(fft)
                    sendColor(color)
                    runOnUiThread { viewSelectedColor.setBackgroundColor(color) }
                }
            }, android.media.audiofx.Visualizer.getMaxCaptureRate() / 2, false, true)
            
            visualizer?.enabled = true
            isMusicModeActive = true
            btnMusicMode.text = "Detener Música"
            btnMusicMode.setBackgroundColor(Color.RED)
        } catch (e: Exception) {
            Toast.makeText(this, "Error de audio", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopVisualizer() {
        visualizer?.enabled = false
        visualizer?.release()
        visualizer = null
        isMusicModeActive = false
        btnMusicMode.text = "Audio Reactivo"
        btnMusicMode.setBackgroundColor(Color.parseColor("#40FFFFFF"))
    }
    
    private fun fftToColor(fft: ByteArray): Int {
        if (fft.isEmpty()) return Color.BLACK
        val n = fft.size
        val r = (Math.abs(fft[0].toInt()) * 4).coerceIn(0, 255)
        val g = (Math.abs(fft[n/2].toInt()) * 4).coerceIn(0, 255)
        val b = (Math.abs(fft[n-1].toInt()) * 4).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    // --- Permisos ---

    private val requestBluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { 
        if (it.values.all { granted -> granted }) {
            if (!attemptAutoConnect()) startScanning()
        }
    }
    
    private val requestAudioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { 
        if (it) startVisualizer() 
    }

    private fun checkPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requestBluetoothPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                return false
            }
        } else {
             if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestBluetoothPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                return false
            }
        }
        return true
    }
    
    private fun checkAudioPermissions(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return false
        }
        return true
    }
}