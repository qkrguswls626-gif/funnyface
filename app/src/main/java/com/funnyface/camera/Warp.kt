package com.funnyface.camera

import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.sqrt

/** 고를 수 있는 효과 목록. 새 효과를 넣으려면 여기에 추가하고 Warp.apply 에 분기를 하나 더 만들면 된다. */
enum class Effect(val label: String) {
    BIG_EYES("왕눈이"),
    BIG_MOUTH("왕입"),
    BIG_NOSE("왕코"),
    ALIEN("외계인"),
    BIG_HEAD("대두"),
    SHRINK("쪼글이"),
}

/**
 * 화면을 고무판처럼 격자로 나눈 뒤, 얼굴 부위 근처의 격자점을 밀거나 당겨서 얼굴을 변형한다.
 * 격자점을 중심에서 바깥으로 밀면 그 부위가 커 보이고(볼록), 안으로 당기면 작아 보인다(오목).
 */
object Warp {
    const val COLS = 36
    const val ROWS = 64

    fun build(w: Int, h: Int, faces: List<Face>, effect: Effect): FloatArray {
        val v = FloatArray((COLS + 1) * (ROWS + 1) * 2)
        var i = 0
        for (y in 0..ROWS) {
            for (x in 0..COLS) {
                v[i++] = w * x / COLS.toFloat()
                v[i++] = h * y / ROWS.toFloat()
            }
        }
        for (f in faces) apply(v, f, effect)
        return v
    }

    private fun apply(v: FloatArray, f: Face, effect: Effect) {
        val box = f.boundingBox
        val fw = box.width().toFloat()
        val cx = box.exactCenterX()
        val cy = box.exactCenterY()

        fun lm(type: Int): PointF? = f.getLandmark(type)?.position

        // 랜드마크를 못 찾으면 얼굴 상자 기준으로 대략 위치를 잡는다.
        val leftEye = lm(FaceLandmark.LEFT_EYE) ?: PointF(cx - fw * 0.2f, cy - fw * 0.15f)
        val rightEye = lm(FaceLandmark.RIGHT_EYE) ?: PointF(cx + fw * 0.2f, cy - fw * 0.15f)
        val nose = lm(FaceLandmark.NOSE_BASE) ?: PointF(cx, cy + fw * 0.05f)
        val ml = lm(FaceLandmark.MOUTH_LEFT)
        val mr = lm(FaceLandmark.MOUTH_RIGHT)
        val mouth = if (ml != null && mr != null) {
            PointF((ml.x + mr.x) / 2f, (ml.y + mr.y) / 2f)
        } else {
            PointF(cx, cy + fw * 0.3f)
        }

        when (effect) {
            Effect.BIG_EYES -> {
                bulge(v, leftEye.x, leftEye.y, fw * 0.28f, 1.3f)
                bulge(v, rightEye.x, rightEye.y, fw * 0.28f, 1.3f)
            }
            Effect.BIG_MOUTH -> bulge(v, mouth.x, mouth.y, fw * 0.42f, 1.3f)
            Effect.BIG_NOSE -> bulge(v, nose.x, nose.y, fw * 0.32f, 1.5f)
            Effect.ALIEN -> {
                bulge(v, leftEye.x, leftEye.y, fw * 0.32f, 1.5f)
                bulge(v, rightEye.x, rightEye.y, fw * 0.32f, 1.5f)
                bulge(v, mouth.x, mouth.y + fw * 0.1f, fw * 0.45f, -0.6f)
            }
            Effect.BIG_HEAD -> bulge(v, cx, cy, fw * 0.95f, 0.9f)
            Effect.SHRINK -> bulge(v, cx, cy, fw * 0.85f, -0.65f)
        }
    }

    /**
     * (cx, cy) 중심, 반지름 r 안의 격자점을 이동시킨다.
     * strength > 0 이면 확대(볼록), < 0 이면 축소(오목). 격자가 접히지 않으려면 -1 < strength < 3.
     */
    private fun bulge(v: FloatArray, cx: Float, cy: Float, r: Float, strength: Float) {
        if (r <= 0f) return
        for (row in 1 until ROWS) {          // 테두리 격자점은 고정해서 화면 가장자리가 벌어지지 않게 한다
            for (col in 1 until COLS) {
                val i = (row * (COLS + 1) + col) * 2
                val dx = v[i] - cx
                val dy = v[i + 1] - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d >= r || d == 0f) continue
                val t = d / r
                val k = 1f + strength * (1f - t) * (1f - t)
                v[i] = cx + dx * k
                v[i + 1] = cy + dy * k
            }
        }
    }
}
