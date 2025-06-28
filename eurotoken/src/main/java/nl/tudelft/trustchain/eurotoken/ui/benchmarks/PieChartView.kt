package nl.tudelft.trustchain.eurotoken.ui.benchmarks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

data class PieSlice(
    val label: String,
    val value: Double,
    val color: Int
)

class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }

    private var slices = listOf<PieSlice>()
    private val rect = RectF()

    private val colors = listOf(
        Color.parseColor("#FF6B6B"), // Red
        Color.parseColor("#4ECDC4"), // Teal
        Color.parseColor("#45B7D1"), // Blue
        Color.parseColor("#96CEB4"), // Green
        Color.parseColor("#FECA57"), // Yellow
        Color.parseColor("#FF9FF3"), // Pink
        Color.parseColor("#54A0FF"), // Light Blue
        Color.parseColor("#5F27CD"), // Purple
        Color.parseColor("#00D2D3"), // Cyan
        Color.parseColor("#FF9F43") // Orange
    )

    fun setData(data: List<Pair<String, Double>>) {
        slices = data.mapIndexed { index, (label, value) ->
            PieSlice(
                label = label,
                value = value,
                color = colors[index % colors.size]
            )
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (slices.isEmpty()) {
            // Draw "No data" text
            textPaint.color = Color.GRAY
            textPaint.textSize = 28f
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "No transaction data",
                width / 2f,
                height / 2f,
                textPaint
            )
            return
        }

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(centerX, centerY) * 0.8f

        rect.set(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )

        var startAngle = -90f // Start from top

        slices.forEach { slice ->
            val sweepAngle = (slice.value / 100.0 * 360.0).toFloat()

            paint.color = slice.color
            canvas.drawArc(rect, startAngle, sweepAngle, true, paint)

            // Draw label
            if (slice.value > 3.0) { // Only show label if slice is big enough
                val midAngle = Math.toRadians((startAngle + sweepAngle / 2).toDouble())
                val labelRadius = radius * 0.7f
                val labelX = centerX + cos(midAngle).toFloat() * labelRadius
                val labelY = centerY + sin(midAngle).toFloat() * labelRadius

                val labelText = "${slice.label}\n${String.format("%.1f%%", slice.value)}"
                textPaint.color = Color.WHITE
                textPaint.textSize = 24f

                // Draw text with background
                val lines = labelText.split("\n")
                lines.forEachIndexed { lineIndex, line ->
                    canvas.drawText(
                        line,
                        labelX,
                        labelY + lineIndex * 30f,
                        textPaint
                    )
                }
            }

            startAngle += sweepAngle
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = minOf(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
        setMeasuredDimension(size, size)
    }
}
