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
    private val selectorBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectorWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var selectedColor = Color.WHITE
    
    // Posición del selector
    private var selectorX = 0f
    private var selectorY = 0f
    
    // Listener para notificar el cambio de color
    var onColorChangedListener: ((Int) -> Unit)? = null

    init {
        paint.style = Paint.Style.FILL
        
        // Borde negro exterior
        selectorBorderPaint.style = Paint.Style.STROKE
        selectorBorderPaint.color = Color.BLACK
        selectorBorderPaint.strokeWidth = 3f
        
        // Borde blanco interior (para contraste)
        selectorWhitePaint.style = Paint.Style.STROKE
        selectorWhitePaint.color = Color.WHITE
        selectorWhitePaint.strokeWidth = 8f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(centerX, centerY) * 0.9f
        
        // Inicializar el selector en el centro
        selectorX = centerX
        selectorY = centerY
        
        updateShader()
    }

    private fun updateShader() {
        if (radius <= 0) return
        
        // CORRECCIÓN IMPORTANTE: Orden de colores alineado con cálculo HSV (Rojo -> Amarillo -> Verde...)
        // Esto asegura que el color visual coincida con el color seleccionado
        val colors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
            Color.BLUE, Color.MAGENTA, Color.RED
        )
        val sweepShader = SweepGradient(centerX, centerY, colors, null)

        val radialShader = RadialGradient(
            centerX, centerY, radius,
            Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP
        )

        paint.shader = ComposeShader(sweepShader, radialShader, PorterDuff.Mode.SRC_OVER)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(centerX, centerY, radius, paint)
        
        // Dibujar el selector mejorado
        val indicatorRadius = 25f 
        
        val fillSelectorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        fillSelectorPaint.style = Paint.Style.FILL
        fillSelectorPaint.color = selectedColor
        
        // 1. Círculo relleno de color
        canvas.drawCircle(selectorX, selectorY, indicatorRadius, fillSelectorPaint)
        // 2. Anillo blanco grueso
        canvas.drawCircle(selectorX, selectorY, indicatorRadius, selectorWhitePaint)
        // 3. Anillo negro fino exterior
        canvas.drawCircle(selectorX, selectorY, indicatorRadius + 2f, selectorBorderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true) // Evitar que el ScrollView intercepte
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                updateSelector(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateSelector(event.x, event.y)
                performClick()
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    
    private fun updateSelector(x: Float, y: Float) {
        val dx = x - centerX
        val dy = y - centerY
        val dist = sqrt(dx * dx + dy * dy)
        
        if (dist <= radius) {
            selectorX = x
            selectorY = y
        } else {
            val ratio = radius / dist
            selectorX = centerX + dx * ratio
            selectorY = centerY + dy * ratio
        }
        
        selectedColor = getColorAt(selectorX, selectorY)
        onColorChangedListener?.invoke(selectedColor)
        invalidate()
    }
    
    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun getColorAt(x: Float, y: Float): Int {
        val dx = x - centerX
        val dy = y - centerY
        val dist = sqrt(dx * dx + dy * dy)
        
        val saturation = (dist / radius).coerceIn(0f, 1f)
        val value = 1f
        
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0) angle += 360f
        
        val hsv = floatArrayOf(angle, saturation, value)
        return Color.HSVToColor(hsv)
    }
}