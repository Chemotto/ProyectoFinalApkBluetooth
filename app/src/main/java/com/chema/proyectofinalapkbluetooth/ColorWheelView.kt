package com.chema.proyectofinalapkbluetooth

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

class ColorWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var selectedColor = Color.WHITE
    
    // Listener para notificar el cambio de color
    var onColorChangedListener: ((Int) -> Unit)? = null

    init {
        paint.style = Paint.Style.FILL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(centerX, centerY) * 0.9f
        updateShader()
    }

    private fun updateShader() {
        if (radius <= 0) return
        
        // Gradiente de barrido para los colores (Hue)
        val colors = intArrayOf(
            Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN,
            Color.GREEN, Color.YELLOW, Color.RED
        )
        val sweepShader = SweepGradient(centerX, centerY, colors, null)

        // Gradiente radial para la saturación (blanco al centro)
        val radialShader = RadialGradient(
            centerX, centerY, radius,
            Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP
        )

        // Combinar ambos
        paint.shader = ComposeShader(sweepShader, radialShader, PorterDuff.Mode.SRC_OVER)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(centerX, centerY, radius, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Manejamos ACTION_DOWN y ACTION_MOVE para permitir arrastrar y seleccionar
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val x = event.x - centerX
                val y = event.y - centerY
                val dist = sqrt(x * x + y * y)

                // Permitimos tocar un poco fuera del radio visible (hasta 1.1x) para facilitar la selección de bordes
                if (dist <= radius * 1.1f) {
                    selectedColor = getColorAt(event.x, event.y)
                    onColorChangedListener?.invoke(selectedColor)
                    
                    // Solo llamamos performClick en UP para cumplir con accesibilidad, 
                    // pero actualizamos color en movimiento fluido
                    return true 
                }
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    
    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun getColorAt(x: Float, y: Float): Int {
        val dx = x - centerX
        val dy = y - centerY
        val dist = sqrt(dx * dx + dy * dy)
        
        // Saturación: Distancia desde el centro (0 al centro, 1 en el borde)
        // Limitamos a 1f para que no se sature de más si tocamos un poco fuera
        val saturation = (dist / radius).coerceIn(0f, 1f)
        
        // Brillo (Value): Siempre al máximo para colores vivos
        val value = 1f
        
        // Tono (Hue): Ángulo
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0) angle += 360f
        
        val hsv = floatArrayOf(angle, saturation, value)
        return Color.HSVToColor(hsv)
    }
}