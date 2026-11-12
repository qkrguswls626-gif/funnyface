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
    private var verts: FloatArray? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fit = Matrix()

    fun setFrame(bitmap: Bitmap, vertices: FloatArray) {
        frame = bitmap
        verts = vertices
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val b = frame ?: return
        val v = verts ?: return
        // 화면을 꽉 채우도록 확대(가운데 기준 크롭)
        val s = max(width / b.width.toFloat(), height / b.height.toFloat())
        fit.reset()
        fit.setScale(s, s)
        fit.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        canvas.save()
        canvas.concat(fit)
        canvas.drawBitmapMesh(b, Warp.COLS, Warp.ROWS, v, 0, null, 0, paint)
        canvas.restore()
    }

    /** 지금 보이는 변형된 화면을 사진으로 만든다. */
    fun snapshot(): Bitmap? {
        val b = frame ?: return null
        val v = verts ?: return null
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmapMesh(b, Warp.COLS, Warp.ROWS, v, 0, null, 0, paint)
        return out
    }
}
