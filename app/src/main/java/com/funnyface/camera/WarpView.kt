package com.funnyface.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View
import kotlin.math.max

/** 변형된 카메라 화면을 그리는 뷰. */
class WarpView(context: Context) : View(context) {
    private var frame: Bitmap? = null
    private var warp: WarpResult? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 28, 20, 14)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val hairLines = FloatArray(Beard.hairs.size / Beard.STRIDE * 4)
    private val fit = Matrix()

    fun setFrame(bitmap: Bitmap, result: WarpResult) {
        frame = bitmap
        warp = result
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val b = frame ?: return
        val w = warp ?: return
        // 화면을 꽉 채우도록 확대(가운데 기준 크롭)
        val s = max(width / b.width.toFloat(), height / b.height.toFloat())
        fit.reset()
        fit.setScale(s, s)
        fit.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        canvas.save()
        canvas.concat(fit)
        render(canvas, b, w)
        canvas.restore()
    }

    /** 지금 보이는 변형된 화면을 사진으로 만든다. */
    fun snapshot(): Bitmap? {
        val b = frame ?: return null
        val w = warp ?: return null
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        render(Canvas(out), b, w)
        return out
    }

    private fun render(canvas: Canvas, b: Bitmap, w: WarpResult) {
        canvas.drawBitmapMesh(b, Warp.COLS, Warp.ROWS, w.verts, 0, null, 0, paint)
        for (p in w.beards) drawBeard(canvas, p)
    }

    private fun drawBeard(canvas: Canvas, p: BeardPose) {
        val h = Beard.hairs
        val len = 0.07f * p.ed
        var o = 0
        var i = 0
        while (i < h.size) {
            val u = h[i]
            var v = h[i + 1]
            if (h[i + 4] > 0.5f) v += p.chinStart - Beard.CHIN_BASE   // 입을 벌리면 턱수염도 같이 내려간다
            val sx = p.ox + (p.rx * u + p.dx * v) * p.ed
            val sy = p.oy + (p.ry * u + p.dy * v) * p.ed
            val sn = h[i + 2]
            val cs = h[i + 3]
            hairLines[o++] = sx
            hairLines[o++] = sy
            hairLines[o++] = sx + (p.dx * cs + p.rx * sn) * len
            hairLines[o++] = sy + (p.dy * cs + p.ry * sn) * len
            i += Beard.STRIDE
        }
        hairPaint.strokeWidth = max(1.5f, p.ed * 0.014f)
        canvas.drawLines(hairLines, hairPaint)
    }
}
