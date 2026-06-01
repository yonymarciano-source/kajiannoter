package com.kajian.note.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.kajian.note.utils.MindmapGenerator.Mindmap
import com.kajian.note.utils.MindmapGenerator.MindmapNode

/**
 * MindmapView — custom Canvas view untuk render mindmap tree.
 *
 * Features:
 * - Pinch-to-zoom + pan
 * - Radial layout dari center
 * - Color coding per level
 * - Tap node untuk highlight
 */
class MindmapView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var mindmap: Mindmap? = null

    // Paint objects
    private val paintCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1DB954.toInt(); style = Paint.Style.FILL
    }
    private val paintCenterText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 28f; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintNode = arrayOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1565C0.toInt(); style = Paint.Style.FILL },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6A1B9A.toInt(); style = Paint.Style.FILL },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00695C.toInt(); style = Paint.Style.FILL },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE65100.toInt(); style = Paint.Style.FILL }
    )
    private val paintLeaf = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF37474F.toInt(); style = Paint.Style.FILL
    }
    private val paintNodeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 22f; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintLeafText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 18f; textAlign = Paint.Align.CENTER
    }
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88AAAAAA.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val paintHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x441DB954.toInt(); style = Paint.Style.FILL
    }

    // Transform
    private var scaleX2 = 1f; private var scaleY2 = 1f
    private var transX = 0f;  private var transY = 0f
    private val matrix = Matrix()

    // Node positions for hit testing
    private data class NodeBounds(val node: MindmapNode, val cx: Float, val cy: Float, val r: Float)
    private val nodeBounds = mutableListOf<NodeBounds>()
    private var selectedNode: MindmapNode? = null

    // Gesture detectors
    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor.coerceIn(0.5f, 3f)
            scaleX2 = (scaleX2 * f).coerceIn(0.3f, 4f)
            scaleY2 = scaleX2
            invalidate(); return true
        }
    })
    private val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            transX -= dx; transY -= dy; invalidate(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y); return true
        }
    })

    fun setMindmap(m: Mindmap) {
        mindmap = m
        nodeBounds.clear()
        selectedNode = null
        // Reset transform
        scaleX2 = 1f; scaleY2 = 1f; transX = 0f; transY = 0f
        post { centerView() }
        invalidate()
    }

    private fun centerView() {
        transX = width / 2f; transY = height / 2f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        centerView()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        // Convert screen coords to canvas coords
        val cx = (x - transX) / scaleX2
        val cy = (y - transY) / scaleY2
        selectedNode = nodeBounds.find { b ->
            val dx = cx - b.cx; val dy = cy - b.cy
            dx * dx + dy * dy <= b.r * b.r
        }?.node
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = mindmap ?: return
        nodeBounds.clear()

        canvas.save()
        canvas.translate(transX, transY)
        canvas.scale(scaleX2, scaleY2)

        // Draw center node
        val centerR = 80f
        canvas.drawCircle(0f, 0f, centerR, paintCenter)
        if (selectedNode == null) canvas.drawCircle(0f, 0f, centerR + 8f, paintHighlight)
        drawWrappedText(canvas, m.title, 0f, 0f, centerR * 1.6f, paintCenterText)

        // Draw main nodes radially
        val nodes = m.nodes
        if (nodes.isEmpty()) { canvas.restore(); return }

        val angleStep = 360f / nodes.size
        val r1 = 220f // distance from center to main node

        nodes.forEachIndexed { i, node ->
            val angle = Math.toRadians((i * angleStep - 90).toDouble())
            val nx = (r1 * Math.cos(angle)).toFloat()
            val ny = (r1 * Math.sin(angle)).toFloat()

            // Line center → node
            canvas.drawLine(0f, 0f, nx, ny, paintLine)

            // Draw main node
            val nr = 55f
            val np = paintNode[i % paintNode.size]
            canvas.drawCircle(nx, ny, nr, np)
            if (selectedNode == node) canvas.drawCircle(nx, ny, nr + 8f, paintHighlight)
            drawWrappedText(canvas, node.label, nx, ny, nr * 1.7f, paintNodeText)
            nodeBounds.add(NodeBounds(node, nx, ny, nr))

            // Draw children
            val children = node.children
            if (children.isNotEmpty()) {
                val childAngleStep = 80f / maxOf(children.size, 1)
                val baseAngle = Math.toDegrees(angle).toFloat()
                val startAngle = baseAngle - (children.size - 1) * childAngleStep / 2f
                val r2 = 160f // distance from main node to child

                children.forEachIndexed { j, child ->
                    val ca = Math.toRadians((startAngle + j * childAngleStep).toDouble())
                    val cx2 = nx + (r2 * Math.cos(ca)).toFloat()
                    val cy2 = ny + (r2 * Math.sin(ca)).toFloat()

                    canvas.drawLine(nx, ny, cx2, cy2, paintLine)

                    val cr = 38f
                    canvas.drawCircle(cx2, cy2, cr, paintLeaf)
                    if (selectedNode == child) canvas.drawCircle(cx2, cy2, cr + 6f, paintHighlight)
                    drawWrappedText(canvas, child.label, cx2, cy2, cr * 1.7f, paintLeafText)
                    nodeBounds.add(NodeBounds(child, cx2, cy2, cr))
                }
            }
        }

        canvas.restore()
    }

    private fun drawWrappedText(canvas: Canvas, text: String, cx: Float, cy: Float, maxW: Float, paint: Paint) {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var cur = ""
        for (w in words) {
            val test = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(test) > maxW) {
                if (cur.isNotEmpty()) lines.add(cur)
                cur = w
            } else cur = test
        }
        if (cur.isNotEmpty()) lines.add(cur)

        val lh = paint.textSize * 1.2f
        val totalH = lh * lines.size
        val startY = cy - totalH / 2f + paint.textSize * 0.4f
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, cx, startY + i * lh, paint)
        }
    }
}
