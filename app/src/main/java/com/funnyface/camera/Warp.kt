package com.funnyface.camera

import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** 고를 수 있는 효과 목록. 새 효과를 넣으려면 여기에 추가하고 Warp.apply 에 분기를 하나 더 만들면 된다. */
enum class Effect(val label: String) {
    CRY("울상"),
    CHUBBY("볼빵빵"),
    BEARD("수염"),
    BIG_EYES("왕눈이"),
    BIG_MOUTH("왕입"),
    BIG_NOSE("왕코"),
    ALIEN("외계인"),
    BIG_HEAD("대두"),
    SHRINK("쪼글이"),
}

/**
 * 수염을 그릴 위치 정보. (ox, oy) = 입 중앙, (rx, ry) = 얼굴 오른쪽 방향, (dx, dy) = 얼굴 아래 방향,
 * ed = 두 눈 사이 거리(얼굴 크기 단위), chinStart = 턱수염이 시작되는 높이(ed 단위).
 */
class BeardPose(
    val ox: Float, val oy: Float,
    val rx: Float, val ry: Float,
    val dx: Float, val dy: Float,
    val ed: Float, val chinStart: Float,
)

class WarpResult(val verts: FloatArray, val beards: List<BeardPose>)

/**
 * 화면을 고무판처럼 격자로 나눈 뒤, 얼굴 부위 근처의 격자점을 밀거나 당겨서 얼굴을 변형한다.
 * - bulge: 한 점을 중심으로 부풀리거나(양수) 오므린다(음수)
 * - shift: 한 점 주변을 정해진 방향으로 끌어당긴다 (입꼬리 내리기, 눈썹 올리기 등)
 */
object Warp {
    const val COLS = 54
    const val ROWS = 96

    fun build(w: Int, h: Int, faces: List<Face>, effect: Effect): WarpResult {
        val v = FloatArray((COLS + 1) * (ROWS + 1) * 2)
        var i = 0
        for (y in 0..ROWS) {
            for (x in 0..COLS) {
                v[i++] = w * x / COLS.toFloat()
                v[i++] = h * y / ROWS.toFloat()
            }
        }
        val beards = ArrayList<BeardPose>()
        for (f in faces) apply(v, f, effect, beards)
        return WarpResult(v, beards)
    }

    private fun apply(v: FloatArray, f: Face, effect: Effect, beards: MutableList<BeardPose>) {
        val box = f.boundingBox
        val fw = box.width().toFloat()
        val cx = box.exactCenterX()
        val cy = box.exactCenterY()

        fun lm(type: Int): PointF? = f.getLandmark(type)?.position

        // 랜드마크를 못 찾으면 얼굴 상자 기준으로 대략 위치를 잡는다.
        var eyeL = lm(FaceLandmark.LEFT_EYE) ?: PointF(cx - fw * 0.2f, cy - fw * 0.15f)
        var eyeR = lm(FaceLandmark.RIGHT_EYE) ?: PointF(cx + fw * 0.2f, cy - fw * 0.15f)
        if (eyeL.x > eyeR.x) { val t = eyeL; eyeL = eyeR; eyeR = t }   // 화면 기준 왼쪽/오른쪽으로 정렬

        // 얼굴 좌표계: R = 얼굴의 오른쪽 방향, D = 아래 방향, ed = 눈 사이 거리
        val ed = max(1f, sqrt((eyeR.x - eyeL.x).pow(2) + (eyeR.y - eyeL.y).pow(2)))
        val rx = (eyeR.x - eyeL.x) / ed
        val ry = (eyeR.y - eyeL.y) / ed
        val dx = -ry
        val dy = rx
        fun pt(base: PointF, r: Float, d: Float) =
            PointF(base.x + (rx * r + dx * d) * ed, base.y + (ry * r + dy * d) * ed)

        val eyeMid = PointF((eyeL.x + eyeR.x) / 2f, (eyeL.y + eyeR.y) / 2f)
        val nose = lm(FaceLandmark.NOSE_BASE) ?: pt(eyeMid, 0f, 0.6f)

        var mouthL = lm(FaceLandmark.MOUTH_LEFT) ?: pt(eyeMid, -0.4f, 1.0f)
        var mouthR = lm(FaceLandmark.MOUTH_RIGHT) ?: pt(eyeMid, 0.4f, 1.0f)
        if (mouthL.x > mouthR.x) { val t = mouthL; mouthL = mouthR; mouthR = t }
        val mouth = PointF((mouthL.x + mouthR.x) / 2f, (mouthL.y + mouthR.y) / 2f)
        val mouthBottom = lm(FaceLandmark.MOUTH_BOTTOM) ?: pt(mouth, 0f, 0.15f)

        var cheekL = lm(FaceLandmark.LEFT_CHEEK) ?: pt(mouthL, -0.35f, -0.25f)
        var cheekR = lm(FaceLandmark.RIGHT_CHEEK) ?: pt(mouthR, 0.35f, -0.25f)
        if (cheekL.x > cheekR.x) { val t = cheekL; cheekL = cheekR; cheekR = t }

        fun bulgeAt(p: PointF, r: Float, s: Float) = bulge(v, p.x, p.y, r * ed, s)
        fun shiftAt(p: PointF, r: Float, mr: Float, md: Float) =
            shift(v, p.x, p.y, r * ed, (rx * mr + dx * md) * ed, (ry * mr + dy * md) * ed)

        when (effect) {
            Effect.CRY -> {
                // 입을 키우고, 입꼬리는 아래·바깥으로, 입 가운데는 위로 → 울먹이는 입
                bulgeAt(mouth, 0.6f, 0.45f)
                shiftAt(mouthL, 0.42f, -0.06f, 0.17f)
                shiftAt(mouthR, 0.42f, 0.06f, 0.17f)
                shiftAt(pt(mouth, 0f, -0.05f), 0.28f, 0f, -0.07f)
                // 눈썹 안쪽은 올리고 바깥쪽과 눈꼬리는 내려서 팔자 눈썹
                shiftAt(pt(eyeL, 0.2f, -0.3f), 0.3f, 0f, -0.14f)
                shiftAt(pt(eyeR, -0.2f, -0.3f), 0.3f, 0f, -0.14f)
                shiftAt(pt(eyeL, -0.3f, -0.18f), 0.32f, 0f, 0.11f)
                shiftAt(pt(eyeR, 0.3f, -0.18f), 0.32f, 0f, 0.11f)
            }
            Effect.CHUBBY -> {
                // 볼과 턱은 빵빵하게, 눈은 작게
                bulgeAt(cheekL, 0.75f, 0.9f)
                bulgeAt(cheekR, 0.75f, 0.9f)
                bulgeAt(pt(mouth, 0f, 0.55f), 0.6f, 0.5f)
                bulgeAt(eyeL, 0.3f, -0.35f)
                bulgeAt(eyeR, 0.3f, -0.35f)
            }
            Effect.BEARD -> {
                val vb = ((mouthBottom.x - mouth.x) * dx + (mouthBottom.y - mouth.y) * dy) / ed
                beards.add(BeardPose(mouth.x, mouth.y, rx, ry, dx, dy, ed, max(0.2f, vb + 0.05f)))
            }
            Effect.BIG_EYES -> {
                bulgeAt(eyeL, 0.6f, 1.3f)
                bulgeAt(eyeR, 0.6f, 1.3f)
            }
            Effect.BIG_MOUTH -> bulgeAt(mouth, 0.9f, 1.3f)
            Effect.BIG_NOSE -> bulgeAt(nose, 0.7f, 1.5f)
            Effect.ALIEN -> {
                bulgeAt(eyeL, 0.7f, 1.5f)
                bulgeAt(eyeR, 0.7f, 1.5f)
                bulgeAt(pt(mouth, 0f, 0.2f), 0.95f, -0.6f)
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

    /** (cx, cy) 주변 반지름 r 안을 (mx, my) 만큼 끌어당긴다. 중심에서 멀수록 덜 움직인다. */
    private fun shift(v: FloatArray, cx: Float, cy: Float, r: Float, mx: Float, my: Float) {
        if (r <= 0f) return
        val r2 = r * r
        for (row in 1 until ROWS) {
            for (col in 1 until COLS) {
                val i = (row * (COLS + 1) + col) * 2
                val dx = v[i] - cx
                val dy = v[i + 1] - cy
                val d2 = dx * dx + dy * dy
                if (d2 >= r2) continue
                val w = (1f - d2 / r2).pow(2)
                v[i] += mx * w
                v[i + 1] += my * w
            }
        }
    }
}

/**
 * 수염 털 한 올 한 올의 위치를 미리 정해 둔 것 (매 프레임 같은 자리라 깜빡이지 않는다).
 * 털 하나 = [u, v, sin, cos, 턱수염여부] 5칸. u/v 는 입 중앙 기준 좌표(눈 사이 거리 단위).
 */
object Beard {
    const val STRIDE = 5
    const val CHIN_BASE = 0.2f

    val hairs: FloatArray = run {
        val rnd = Random(7)
        val out = ArrayList<Float>()
        var n = 0
        while (n < 1500) {
            val u = rnd.nextFloat() * 1.7f - 0.85f
            val v = rnd.nextFloat() * 1.11f - 0.31f
            val au = abs(u)
            val mustache = v > -0.31f && v < -0.12f && au < 0.55f
            val side = v >= -0.12f && v < CHIN_BASE && au > 0.5f && au < 0.8f
            val chin = v >= CHIN_BASE &&
                au < 0.8f * sqrt(max(0f, 1f - ((v - CHIN_BASE) / 0.6f).pow(2)))
            if (!(mustache || side || chin)) continue
            val a = rnd.nextFloat() - 0.5f
            out.add(u); out.add(v); out.add(sin(a)); out.add(cos(a)); out.add(if (chin) 1f else 0f)
            n++
        }
        out.toFloatArray()
    }
}
