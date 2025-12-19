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
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
    private var lastMusicUpdate: Long = 0
    
    private lateinit var deviceAdapter: BluetoothDeviceAdapter
    private val deviceList = ArrayList<BluetoothDevice>()

    // Bluetooth Services
    private var bluetoothService: BluetoothService? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var isBleConnected = false
    private var isBleConnecting = false

    private var connectedDeviceName: String? = null
    
    // Audio (Microphone)
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var maxMagnitude = 50.0 

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "LedControlPrefs"
        private const val KEY_LAST_DEVICE = "last_device_address"
        private const val SAMPLE_RATE = 44100
        private const val BUFFER_SIZE = 1024
        
        // Claves para guardar los colores favoritos
        private const val KEY_COLOR_1 = "fav_color_1"
        private const val KEY_COLOR_2 = "fav_color_2"
        private const val KEY_COLOR_3 = "fav_color_3"
        private const val KEY_COLOR_4 = "fav_color_4"
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
        
        // Configurar botones de colores favoritos con pulsación larga para guardar
        setupFavoriteButton(findViewById(R.id.btnColor1), KEY_COLOR_1, Color.CYAN)
        setupFavoriteButton(findViewById(R.id.btnColor2), KEY_COLOR_2, Color.MAGENTA)
        setupFavoriteButton(findViewById(R.id.btnColor3), KEY_COLOR_3, Color.parseColor("#8A2BE2")) // BlueViolet
        setupFavoriteButton(findViewById(R.id.btnColor4), KEY_COLOR_4, Color.parseColor("#00FF7F")) // SpringGreen

        btnPower.setOnClickListener { 
            isLightOn = !isLightOn
            sendPowerCommand(isLightOn)
            btnPower.text = if (isLightOn) "Encendido" else "Apagado"
            btnPower.alpha = if (isLightOn) 1.0f else 0.5f
        }
        
        // Destello (Flash): Seven Color Jump (0x25)
        btnFlash.setOnClickListener { sendEffectCommand(0x25) }
        
        // Fiesta (Strobe): Seven Color Strobe (0x30) con velocidad alta (0x05)
        btnStrobe.setOnClickListener { sendEffectCommand(0x30, 0x05) }
        
        // Suave (Fade): Efecto suave que intenta adaptarse al último color seleccionado
        // Se elige el efecto "Gradual" (Fade) más cercano al color actual
        btnFade.setOnClickListener { 
            val fadeEffect = getBestFadeEffectForColor(lastSelectedColor)
            sendEffectCommand(fadeEffect) 
        }
        
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

    private fun setupFavoriteButton(button: View, key: String, defaultColor: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // 1. Cargar color inicial (o por defecto)
        val savedColor = prefs.getInt(key, defaultColor)
        button.backgroundTintList = ColorStateList.valueOf(savedColor)

        // 2. Click Corto: Enviar el color guardado
        button.setOnClickListener {
            val color = prefs.getInt(key, defaultColor)
            setFavoriteColor(color)
            Toast.makeText(this, "Color aplicado", Toast.LENGTH_SHORT).show()
        }

        // 3. Click Largo: Guardar el color actual seleccionado
        button.setOnLongClickListener {
            val colorToSave = lastSelectedColor
            prefs.edit().putInt(key, colorToSave).apply()
            
            // Feedback visual al usuario (Actualizar el TINT, no el background directo)
            button.backgroundTintList = ColorStateList.valueOf(colorToSave)
            
            Toast.makeText(this, "Color guardado", Toast.LENGTH_SHORT).show()
            true // Consumir el evento
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        bluetoothService?.stop()
        disconnectBle()
        stopAudioCapture()
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
        
        // Guardar dispositivo INMEDIATAMENTE al iniciar el intento de conexión
        saveLastDevice(device.address)
        
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
        stopAudioCapture()
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
        
        // Protocolo Magic Home Clásico: 0x56 RR GG BB 00 F0 AA
        val command = byteArrayOf(0x56.toByte(), r.toByte(), g.toByte(), b.toByte(), 0x00, 0xf0.toByte(), 0xaa.toByte())
        
        sendBytes(command)
    }

    private fun sendPowerCommand(on: Boolean) {
        if (on) {
            // "Encender": Restaurar el último color
            if (lastSelectedColor == Color.BLACK) lastSelectedColor = Color.WHITE // Seguridad
            sendColor(lastSelectedColor)
        } else {
            // "Apagar": Enviar color Negro (0,0,0)
            // Esto es compatible con TODOS los controladores que aceptan cambio de color
            sendColor(Color.BLACK)
            
            // Si el modo música está activo, detenerlo para que no vuelva a encender las luces
            if (isMusicModeActive) {
                toggleMusicMode()
            }
        }
    }
    
    // Función inteligente para mapear el color seleccionado a un efecto de desvanecimiento
    private fun getBestFadeEffectForColor(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        
        // Efectos estándar Magic Home (Modos 38-44 aprox son fades de color único)
        // 0x26 = Red Gradual Change
        // 0x27 = Green Gradual Change
        // 0x28 = Blue Gradual Change
        // 0x29 = Yellow Gradual Change
        // 0x2A = Cyan Gradual Change
        // 0x2B = Purple/Magenta Gradual Change
        // 0x2C = White Gradual Change
        
        // Determinamos el color predominante
        return when {
            // Blancos/Grises -> White Gradual
            r > 200 && g > 200 && b > 200 -> 0x2C 
            // Rojos puros -> Red Gradual
            r > g + 100 && r > b + 100 -> 0x26
            // Verdes puros -> Green Gradual
            g > r + 100 && g > b + 100 -> 0x27
            // Azules puros -> Blue Gradual
            b > r + 100 && b > g + 100 -> 0x28
            // Amarillos (Rojo + Verde) -> Yellow Gradual
            r > 150 && g > 150 && b < 100 -> 0x29
            // Cian (Verde + Azul) -> Cyan Gradual
            r < 100 && g > 150 && b > 150 -> 0x2A
            // Magenta (Rojo + Azul) -> Purple Gradual
            r > 150 && g < 100 && b > 150 -> 0x2B
            // Por defecto: Seven Color Cross Fade si no está claro
            else -> 0x37 
        }
    }
    
    private fun sendEffectCommand(effectCode: Int, speed: Int = 16) {
        // Protocolo de efectos Magic Home estándar para dispositivos que usan color 0x56
        // Header: 0xBB, Mode: effectCode, Speed: speed, Footer: 0x44
        val command = byteArrayOf(0xBB.toByte(), effectCode.toByte(), speed.toByte(), 0x44.toByte())
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
            if (checkAudioPermissions()) startAudioCapture()
        } else {
            stopAudioCapture()
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        if (isRecording) return

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
             Toast.makeText(this, "Microfono no soportado", Toast.LENGTH_SHORT).show()
             return
        }
        
        val bufferSize = Math.max(minBufferSize, BUFFER_SIZE * 2) // Ensure plenty of space

        try {
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Toast.makeText(this, "Error inicializando audio", Toast.LENGTH_SHORT).show()
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            isMusicModeActive = true
            
            btnMusicMode.text = "Detener Música"
            btnMusicMode.setBackgroundColor(Color.RED)
            maxMagnitude = 50.0

            recordingThread = Thread {
                val buffer = ShortArray(BUFFER_SIZE)
                val real = DoubleArray(BUFFER_SIZE)
                val imag = DoubleArray(BUFFER_SIZE)
                
                while (isRecording) {
                    val readResult = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: 0
                    if (readResult > 0) {
                        val currentTime = System.currentTimeMillis()
                        // 15 FPS (aprox 66ms)
                        if (currentTime - lastMusicUpdate > 66) { 
                            lastMusicUpdate = currentTime
                            
                            // Convert to Double for FFT
                            for (i in 0 until BUFFER_SIZE) {
                                real[i] = buffer[i].toDouble()
                                imag[i] = 0.0
                            }
                            
                            fft(real, imag)
                            val color = calculateColorFromFFT(real, imag)
                            
                            if (color != Color.BLACK) {
                                sendColor(color)
                                runOnUiThread { viewSelectedColor.setBackgroundColor(color) }
                            }
                        }
                    }
                }
            }
            recordingThread?.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando audio", e)
            stopAudioCapture()
        }
    }
    
    private fun stopAudioCapture() {
        isRecording = false
        isMusicModeActive = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio", e)
        }
        audioRecord = null
        recordingThread = null
        
        runOnUiThread {
            btnMusicMode.text = "Audio Reactivo"
            btnMusicMode.setBackgroundColor(Color.parseColor("#40FFFFFF"))
        }
    }
    
    // Simple FFT implementation
    private fun fft(x: DoubleArray, y: DoubleArray) {
        val n = x.size
        val m = (Math.log(n.toDouble()) / Math.log(2.0)).toInt()
        
        var i: Int; var j = 0; var k: Int
        var tr: Double; var ti: Double
        
        // Bit reversal
        for (i in 0 until n - 1) {
            if (i < j) {
                tr = x[j]; ti = y[j]
                x[j] = x[i]; y[j] = y[i]
                x[i] = tr; y[i] = ti
            }
            k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }
        
        // Butterflies
        var l = 1
        var le: Int
        var le1: Int
        var ur: Double; var ui: Double
        var sr: Double; var si: Double
        
        for (level in 1..m) {
            le = 1 shl level // 2^level
            le1 = le / 2
            ur = 1.0
            ui = 0.0
            sr = cos(PI / le1)
            si = -sin(PI / le1)
            
            for (jj in 0 until le1) {
                i = jj
                while (i < n) {
                    val ip = i + le1
                    tr = x[ip] * ur - y[ip] * ui
                    ti = x[ip] * ui + y[ip] * ur
                    x[ip] = x[i] - tr
                    y[ip] = y[i] - ti
                    x[i] += tr
                    y[i] += ti
                    i += le
                }
                tr = ur
                ur = tr * sr - ui * si
                ui = tr * si + ui * sr
            }
        }
    }

    private fun calculateColorFromFFT(real: DoubleArray, imag: DoubleArray): Int {
        val n = real.size / 2 // Nyquist
        
        fun getMag(i: Int): Double {
            if (i >= n) return 0.0
            return sqrt(real[i] * real[i] + imag[i] * imag[i])
        }

        var bass = 0.0
        var mid = 0.0
        var treble = 0.0

        // Freq per bin = 44100 / 1024 = ~43 Hz
        
        // Bass: ~43Hz - ~300Hz (Bins 1..7)
        for (k in 1..7) bass = Math.max(bass, getMag(k))
        
        // Mids: ~300Hz - ~2000Hz (Bins 8..46)
        for (k in 8..46) mid += getMag(k)
        mid /= 38.0
        
        // Treble: ~2000Hz - ~10000Hz (Bins 47..230)
        for (k in 47..230) treble += getMag(k)
        treble /= 183.0
        
        // Normalización Dinámica
        val currentMax = Math.max(bass, Math.max(mid, treble))
        if (currentMax > maxMagnitude) {
            maxMagnitude = currentMax
        } else {
            maxMagnitude *= 0.98 // Decaimiento suave
        }
        if (maxMagnitude < 100) maxMagnitude = 100.0 // Noise floor adjusted for PCM

        // Color Mapping
        val r = ((bass / maxMagnitude) * 255 * 1.5).toInt().coerceIn(0, 255)
        val g = ((mid / maxMagnitude) * 255).toInt().coerceIn(0, 255)
        val b = ((treble / maxMagnitude) * 255).toInt().coerceIn(0, 255)

        // Threshold para silencio
        if (r < 20 && g < 20 && b < 20) return Color.BLACK
        
        return Color.rgb(r, g, b)
    }

    // --- Permisos ---

    private val requestBluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { 
        if (it.values.all { granted -> granted }) {
            if (!attemptAutoConnect()) startScanning()
        }
    }
    
    private val requestAudioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { 
        if (it) startAudioCapture() 
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