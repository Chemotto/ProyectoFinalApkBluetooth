package com.chema.proyectofinalapkbluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.widget.Button
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
    private lateinit var btnConfig: Button
    private lateinit var rvDevices: RecyclerView
    private lateinit var deviceAdapter: BluetoothDeviceAdapter
    private val deviceList = ArrayList<BluetoothDevice>()

    // Servicio de Bluetooth
    private var bluetoothService: BluetoothService? = null
    private var connectedDeviceName: String? = null

    // Handler para recibir mensajes del BluetoothService
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                BluetoothService.MESSAGE_STATE_CHANGE -> {
                    when (msg.arg1) {
                        BluetoothService.STATE_CONNECTED -> {
                            tvStatus.text = "Estado: Conectado a $connectedDeviceName"
                            deviceAdapter.notifyDataSetChanged()
                        }
                        BluetoothService.STATE_CONNECTING -> {
                            tvStatus.text = "Estado: Conectando..."
                        }
                        BluetoothService.STATE_NONE -> {
                            tvStatus.text = "Estado: Inactivo"
                        }
                    }
                }
                BluetoothService.MESSAGE_WRITE -> {
                    // Mensaje enviado (opcional: actualizar UI)
                }
                BluetoothService.MESSAGE_READ -> {
                    // Mensaje recibido (opcional: actualizar UI con datos recibidos)
                    // val readBuf = msg.obj as ByteArray
                    // val readMessage = String(readBuf, 0, msg.arg1)
                }
                BluetoothService.MESSAGE_DEVICE_NAME -> {
                    connectedDeviceName = msg.data.getString(BluetoothService.DEVICE_NAME)
                    Toast.makeText(applicationContext, "Conectado a $connectedDeviceName", Toast.LENGTH_SHORT).show()
                }
                BluetoothService.MESSAGE_TOAST -> {
                    Toast.makeText(applicationContext, msg.data.getString(BluetoothService.TOAST), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Receiver para detectar dispositivos encontrados
    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (BluetoothDevice.ACTION_FOUND == action) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    if (!deviceList.contains(it)) {
                        deviceList.add(it)
                        deviceAdapter.notifyItemInserted(deviceList.size - 1)
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
        btnConfig = findViewById(R.id.btnConfig)
        rvDevices = findViewById(R.id.rvDevices)

        // Configurar RecyclerView
        deviceAdapter = BluetoothDeviceAdapter(deviceList) { device ->
            connectToDevice(device)
        }
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        // Inicializar BluetoothAdapter
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Inicializar BluetoothService
        bluetoothService = BluetoothService(handler)

        // Configurar botones
        btnScan.setOnClickListener {
            if (checkPermissions()) {
                startScanning()
            }
        }

        btnConfig.setOnClickListener {
            openConfiguration()
        }

        // Registrar receiver
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
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
            bluetoothService = BluetoothService(handler)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        bluetoothService?.stop()
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Por favor, activa el Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "Estado: Escaneando..."
        deviceList.clear()
        deviceAdapter.notifyDataSetChanged()
        
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
        bluetoothAdapter.startDiscovery()
    }

    private fun connectToDevice(device: BluetoothDevice) {
        // Lógica de conexión usando BluetoothService
        bluetoothService?.connect(device)
    }

    private fun openConfiguration() {
        Toast.makeText(this, "Abrir configuración", Toast.LENGTH_SHORT).show()
        // Aquí puedes navegar a una nueva actividad o mostrar un diálogo
    }

    // Gestión de permisos
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                startScanning()
            } else {
                Toast.makeText(this, "Permisos denegados", Toast.LENGTH_SHORT).show()
            }
        }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        return if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
            false
        } else {
            true
        }
    }
}