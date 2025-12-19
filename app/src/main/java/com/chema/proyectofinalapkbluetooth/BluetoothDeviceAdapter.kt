package com.chema.proyectofinalapkbluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView

class BluetoothDeviceAdapter(
    private val devices: List<BluetoothDevice>,
    private val onDeviceClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<BluetoothDeviceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        val icSaved: ImageView = view.findViewById(R.id.icSaved)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bluetooth_device, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        val context = holder.itemView.context
        val prefs = context.getSharedPreferences("LedControlPrefs", Context.MODE_PRIVATE)
        
        // 1. Obtener nombre (personalizado o del dispositivo)
        val customName = prefs.getString("custom_name_${device.address}", null)
        val deviceName = device.name ?: "Dispositivo Desconocido"
        holder.tvName.text = customName ?: deviceName
        
        holder.tvAddress.text = device.address
        
        // 2. Comprobar si está guardado
        val savedDevices = prefs.getStringSet("saved_device_addresses", emptySet()) ?: emptySet()
        
        if (savedDevices.contains(device.address)) {
            holder.icSaved.visibility = View.VISIBLE
            
            // 3. Listener para renombrar (Pulsación larga)
            holder.itemView.setOnLongClickListener {
                showRenameDialog(context, device, prefs) {
                    // Actualizar la vista correctamente usando la posición actual
                    val currentPos = holder.bindingAdapterPosition
                    if (currentPos != RecyclerView.NO_POSITION) {
                        notifyItemChanged(currentPos)
                    }
                }
                true
            }
        } else {
            holder.icSaved.visibility = View.GONE
            holder.itemView.setOnLongClickListener(null)
        }
        
        holder.itemView.setOnClickListener { onDeviceClick(device) }
    }
    
    @SuppressLint("MissingPermission")
    private fun showRenameDialog(context: Context, device: BluetoothDevice, prefs: SharedPreferences, onSaved: () -> Unit) {
        val currentName = prefs.getString("custom_name_${device.address}", null) ?: device.name ?: ""

        val input = EditText(context)
        input.setText(currentName)
        input.setSingleLine()
        
        // Contenedor para dar márgenes al EditText
        val container = FrameLayout(context)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.setMargins(50, 20, 50, 20)
        input.layoutParams = params
        container.addView(input)

        AlertDialog.Builder(context)
            .setTitle("Renombrar dispositivo")
            .setMessage("Introduce un nuevo nombre:")
            .setView(container)
            .setPositiveButton("Guardar") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    prefs.edit().putString("custom_name_${device.address}", newName).apply()
                    onSaved()
                } else {
                    // Si lo deja vacío, borrar el nombre personalizado
                    prefs.edit().remove("custom_name_${device.address}").apply()
                    onSaved()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun getItemCount() = devices.size
}