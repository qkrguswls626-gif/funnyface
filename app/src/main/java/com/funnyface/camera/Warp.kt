package com.funnyface.camera

import android.graphics.Color
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
    BEARD("상남자"),
    BIG_EYES("왕눈이"),
    BIG_MOUTH("왕입"),
    BIG_NOSE("왕코"),
    ALIEN("외계인"),
    BIG_HEAD("대두"),
    SHRINK("쪼글이"),
}

/**
 * 수염·눈썹을 그릴 위치 정보. (ox, oy) = 입 중앙, (rx, ry) = 얼굴 오른쪽 방향, (dx, dy) = 얼굴 아래 방향,
 * ed = 두 눈 사이 거리(얼굴 크기 단위), chinStart = 턱수염이 시작되는 높이(ed 단위).
 */
class BeardPose(
    val ox: Float, val oy: Float,
    val elx: Float, val ely: Float,
    val erx: Float, val ery: Float,
    val rx: Float, val ry: Float,
    val dx: Float, val dy: Float,
    val ed: Float, val chinStart: Float,
)

/** 얼굴 위에 덧칠하는 부드러운 색 얼룩 (홍조, 광택, 수염 그림자 등). */
class Tint(val x: Float, val y: Float, val r: Float, val color: Int)

class WarpResult(val verts: FloatArray, val beards: List<BeardPose>, val tints: List<Tint>)

/**
 * 화면을 고무판처럼 격자로 나눈 뒤, 얼굴 부위 근처의 격자점을 밀거나 당겨서 얼굴을 변형한다.
 * - bulge: 한 점을 중심으로 부풀리거나(양수) 오므린다(음수)
 * - shift: 한 점 주변을 정해진 방향으로 끌어당긴다 (입꼬리 내리기, 눈썹 올리기 등)
 * - stretch: 한 점 주변을 한 방향으로만 늘리거나(양수) 납작하게 누른다(음수)
 */
object Warp {
    const val COLS = 54
    const val ROWS = 96

    // 얼굴 기준점 배열의 칸 번호 (x, y 순서로 2칸씩)
    private const val EYE_L = 0
    private const val EYE_R = 2
    private const val NOSE = 4
    private const val MOUTH_L = 6
    private const val MOUTH_R = 8
    private const val MOUTH_B = 10
    private const val CHEEK_L = 12
    private const val CHEEK_R = 14
    private const val BOX_C = 16
    private const val BOX_W = 18
    private const val K = 19

    /** 얼굴별로 직전 프레임의 기준점을 기억해 두었다가 떨림을 걸러낸다. (분석 스레드에서만 사용) */
    private val smoothed = HashMap<Int, FloatArray>()

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
        val tints = ArrayList<Tint>()
        val seen = HashSet<Int>()
        faces.forEachIndexed { idx, f ->
            val id = f.trackingId ?: (-1 - idx)
            seen.add(id)
            apply(v, stabilize(id, keypoints(f)), effect, beards, tints)
        }
        smoothed.keys.retainAll(seen)
        return WarpResult(v, beards, tints)
    }

    /** 얼굴 인식 결과에서 기준점을 뽑는다. 못 찾은 점은 눈 위치를 기준으로 대략 잡는다. */
    private fun keypoints(f: Face): FloatArray {
        val box = f.boundingBox
        val fw = box.width().toFloat()
        val cx = box.exactCenterX()
        val cy = box.exactCenterY()
        fun lm(type: Int): PointF? = f.getLandmark(type)?.position

        var eyeL = lm(FaceLandmark.LEFT_EYE) ?: PointF(cx - fw * 0.2f, cy - fw * 0.15f)
        var eyeR = lm(FaceLandmark.RIGHT_EYE) ?: PointF(cx + fw * 0.2f, cy - fw * 0.15f)
        if (eyeL.x > eyeR.x) { val t = eyeL; eyeL = eyeR; eyeR = t }   // 화면 기준 왼쪽/오른쪽으로 정렬

        val ed = max(1f, sqrt((eyeR.x - eyeL.x).pow(2) + (eyeR.y - eyeL.y).pow(2)))
        val rx = (eyeR.x - eyeL.x) / ed
        val ry = (eyeR.y - eyeL.y) / ed
        fun pt(base: PointF, r: Float, d: Float) =
            PointF(base.x + (rx * r - ry * d) * ed, base.y + (ry * r + rx * d) * ed)

        val eyeMid = PointF((eyeL.x + eyeR.x) / 2f, (eyeL.y + eyeR.y) / 2f)
        val nose = lm(FaceLandmark.NOSE_BASE) ?: pt(eyeMid, 0f, 0.6f)
        var mouthL = lm(FaceLandmark.MOUTH_LEFT) ?: pt(eyeMid, -0.4f, 1.0f)
        var mouthR = lm(FaceLandmark.MOUTH_RIGHT) ?: pt(eyeMid, 0.4f, 1.0f)
        if (mouthL.x > mouthR.x) { val t = mouthL; mouthL = mouthR; mouthR = t }
        val mouth = PointF((mouthL.x + mouthR.x) / 2f, (mouthL.y + mouthR.y) / 2f)
        val mouthB = lm(FaceLandmark.MOUTH_BOTTOM) ?: pt(mouth, 0f, 0.15f)
        var cheekL = lm(FaceLandmark.LEFT_CHEEK) ?: pt(mouthL, -0.35f, -0.25f)
        var cheekR = lm(FaceLandmark.RIGHT_CHEEK) ?: pt(mouthR, 0.35f, -0.25f)
        if (cheekL.x > cheekR.x) { val t = cheekL; cheekL = cheekR; cheekR = t }

        val k = FloatArray(K)
        fun put(at: Int, p: PointF) { k[at] = p.x; k[at + 1] = p.y }
        put(EYE_L, eyeL); put(EYE_R, eyeR); put(NOSE, nose)
        put(MOUTH_L, mouthL); put(MOUTH_R, mouthR); put(MOUTH_B, mouthB)
        put(CHEEK_L, cheekL); put(CHEEK_R, cheekR)
        k[BOX_C] = cx; k[BOX_C + 1] = cy; k[BOX_W] = fw
        return k
    }

    /**
     * 떨림 보정: 기준점이 조금만 움직였으면(인식 오차) 천천히 따라가고,
     * 많이 움직였으면(실제 움직임) 바로 따라간다.
     */
    private fun stabilize(id: Int, raw: FloatArray): FloatArray {
        val prev = smoothed[id]
        if (prev == null) {
            smoothed[id] = raw
            return raw
        }
        val ed = max(1f, sqrt((raw[EYE_R] - raw[EYE_L]).pow(2) + (raw[EYE_R + 1] - raw[EYE_L + 1]).pow(2)))
        var j = 0
        while (j < BOX_W) {
            val mx = raw[j] - prev[j]
            val my = raw[j + 1] - prev[j + 1]
            val a = (sqrt(mx * mx + my * my) / (0.06f * ed)).coerceIn(0.2f, 1f)
            prev[j] += mx * a
            prev[j + 1] += my * a
            j += 2
        }
        prev[BOX_W] += (raw[BOX_W] - prev[BOX_W]) * 0.3f
        return prev
    }

    private fun apply(
        v: FloatArray, k: FloatArray, effect: Effect,
        beards: MutableList<BeardPose>, tints: MutableList<Tint>,
    ) {
        fun p(at: Int) = PointF(k[at], k[at + 1])
        val eyeL = p(EYE_L)
        val eyeR = p(EYE_R)
        val nose = p(NOSE)
        val mouthL = p(MOUTH_L)
        val mouthR = p(MOUTH_R)
        val mouthB = p(MOUTH_B)
        val cheekL = p(CHEEK_L)
        val cheekR = p(CHEEK_R)
        val cx = k[BOX_C]
        val cy = k[BOX_C + 1]
        val fw = k[BOX_W]
        val mouth = PointF((mouthL.x + mouthR.x) / 2f, (mouthL.y + mouthR.y) / 2f)

        // 얼굴 좌표계: R = 얼굴의 오른쪽 방향, D = 아래 방향, ed = 눈 사이 거리
        val ed = max(1f, sqrt((eyeR.x - eyeL.x).pow(2) + (eyeR.y - eyeL.y).pow(2)))
        val rx = (eyeR.x - eyeL.x) / ed
        val ry = (eyeR.y - eyeL.y) / ed
        val dx = -ry
        val dy = rx
        fun pt(base: PointF, r: Float, d: Float) =
            PointF(base.x + (rx * r + dx * d) * ed, base.y + (ry * r + dy * d) * ed)

        fun bulgeAt(c: PointF, r: Float, s: Float) = bulge(v, c.x, c.y, r * ed, s)
        fun shiftAt(c: PointF, r: Float, mr: Float, md: Float) =
            shift(v, c.x, c.y, r * ed, (rx * mr + dx * md) * ed, (ry * mr + dy * md) * ed)
        fun widen(c: PointF, r: Float, s: Float) = stretch(v, c.x, c.y, r * ed, rx, ry, s)   // 가로로 늘리기
        fun heighten(c: PointF, r: Float, s: Float) = stretch(v, c.x, c.y, r * ed, dx, dy, s) // 세로로 늘리기
        fun tint(c: PointF, r: Float, color: Int) = tints.add(Tint(c.x, c.y, r * ed, color))

        when (effect) {
            Effect.CRY -> {
                // 입: 가로·세로로 크게 벌리고 입꼬리는 아래로, 윗입술 가운데는 위로 → 엉엉 우는 입
                widen(mouth, 0.8f, 0.5f)
                heighten(mouth, 0.62f, 0.7f)
                shiftAt(mouthL, 0.45f, -0.08f, 0.2f)
                shiftAt(mouthR, 0.45f, 0.08f, 0.2f)
                shiftAt(pt(mouth, 0f, -0.08f), 0.3f, 0f, -0.08f)
                // 눈: 찡그려서 가늘게, 눈썹 안쪽은 올리고 바깥쪽은 내려 팔자 눈썹
                heighten(eyeL, 0.34f, -0.35f)
                heighten(eyeR, 0.34f, -0.35f)
                shiftAt(pt(eyeL, 0.2f, -0.3f), 0.3f, 0.03f, -0.16f)
                shiftAt(pt(eyeR, -0.2f, -0.3f), 0.3f, -0.03f, -0.16f)
                shiftAt(pt(eyeL, -0.3f, -0.18f), 0.32f, 0f, 0.12f)
                shiftAt(pt(eyeR, 0.3f, -0.18f), 0.32f, 0f, 0.12f)
                // 코는 살짝 부풀리고, 코와 눈가는 운 것처럼 빨갛게
                bulgeAt(nose, 0.4f, 0.3f)
                val red = Color.argb(55, 225, 50, 50)
                tint(nose, 0.34f, red)
                tint(pt(eyeL, 0f, 0.1f), 0.38f, Color.argb(38, 225, 50, 50))
                tint(pt(eyeR, 0f, 0.1f), 0.38f, Color.argb(38, 225, 50, 50))
            }
            Effect.CHUBBY -> {
                // 얼굴 아래쪽을 옆으로 넓히고 턱은 짧게 → 눌린 찐빵 얼굴
                widen(pt(mouth, 0f, -0.1f), 1.35f, 0.35f)
                shiftAt(pt(mouth, 0f, 0.75f), 0.65f, 0f, -0.16f)
                // 볼은 빵빵하게
                val cl = pt(cheekL, -0.08f, 0.08f)
                val cr = pt(cheekR, 0.08f, 0.08f)
                bulgeAt(cl, 0.8f, 1.1f)
                bulgeAt(cr, 0.8f, 1.1f)
                // 눈은 가늘고 작게, 입은 볼 사이에 끼여 작게
                heighten(eyeL, 0.34f, -0.4f)
                heighten(eyeR, 0.34f, -0.4f)
                bulgeAt(eyeL, 0.32f, -0.25f)
                bulgeAt(eyeR, 0.32f, -0.25f)
                bulgeAt(mouth, 0.45f, -0.25f)
                // 볼에 홍조와 반들반들한 광택
                val blush = Color.argb(42, 255, 90, 90)
                val shine = Color.argb(85, 255, 255, 255)
                tint(cl, 0.5f, blush)
                tint(cr, 0.5f, blush)
                tint(pt(cl, 0.04f, -0.1f), 0.24f, shine)
                tint(pt(cr, -0.04f, -0.1f), 0.24f, shine)
            }
            Effect.BEARD -> {
                // 턱을 살짝 키우고, 수염 자리에 푸르스름한 그림자를 깐 뒤 털을 그린다
                bulgeAt(pt(mouth, 0f, 0.45f), 1.0f, 0.25f)
                val shadow = Color.argb(60, 35, 28, 24)
                tint(pt(mouth, 0f, -0.2f), 0.5f, shadow)
                tint(pt(mouth, 0f, 0.5f), 0.75f, shadow)
                tint(pt(mouth, -0.6f, 0.2f), 0.45f, shadow)
                tint(pt(mouth, 0.6f, 0.2f), 0.45f, shadow)
                val vb = ((mouthB.x - mouth.x) * dx + (mouthB.y - mouth.y) * dy) / ed
                beards.add(
                    BeardPose(
                        mouth.x, mouth.y, eyeL.x, eyeL.y, eyeR.x, eyeR.y,
                        rx, ry, dx, dy, ed, max(Beard.CHIN_BASE, vb + 0.05f)
                    )
                )
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

    /** (cx, cy) 주변을 (ax, ay) 방향으로만 늘린다(s > 0) / 납작하게 누른다(s < 0, -0.7 이상). */
    private fun stretch(v: FloatArray, cx: Float, cy: Float, r: Float, ax: Float, ay: Float, s: Float) {
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
                val proj = dx * ax + dy * ay
                v[i] += ax * proj * s * w
                v[i + 1] += ay * proj * s * w
            }
        }
    }
}

/**
 * 수염·눈썹 털 한 올 한 올의 위치를 미리 정해 둔 것 (매 프레임 같은 자리라 깜빡이지 않는다).
 * 좌표 단위는 눈 사이 거리.
 */
object Beard {
    const val CHIN_BASE = 0.2f

    /** 수염: 털 하나 = [u, v, sin, cos, 턱수염여부]. 원점은 입 중앙. */
    const val FACE_STRIDE = 5
    val face: FloatArray = run {
        val rnd = Random(7)
        val out = ArrayList<Float>()
        fun fill(count: Int, chin: Boolean, inside: (Float, Float, Float) -> Boolean) {
            var n = 0
            while (n < count) {
                val u = rnd.nextFloat() * 1.8f - 0.9f
                val v = rnd.nextFloat() * 1.15f - 0.32f
                if (!inside(u, v, abs(u))) continue
                val a = rnd.nextFloat() - 0.5f
                out.add(u); out.add(v); out.add(sin(a)); out.add(cos(a)); out.add(if (chin) 1f else 0f)
                n++
            }
        }
        // 콧수염: 가운데는 코 밑까지, 양 끝은 입꼬리 옆으로 살짝 처짐
        fill(900, false) { _, v, au ->
            au < 0.58f && v > -0.30f + 0.10f * (au / 0.58f).pow(2) &&
                v < (if (au > 0.42f) 0.0f else -0.11f)
        }
        // 입 양옆
        fill(500, false) { _, v, au -> v >= -0.05f && v < CHIN_BASE && au > 0.52f && au < 0.85f }
        // 턱수염
        fill(1700, true) { _, v, au ->
            v >= CHIN_BASE && au < 0.85f * sqrt(max(0f, 1f - ((v - CHIN_BASE) / 0.62f).pow(2)))
        }
        out.toFloatArray()
    }

    /** 눈썹: 털 하나 = [u(바깥쪽이 +), v, sin, cos]. 원점은 눈 중앙. */
    const val BROW_STRIDE = 4
    val brow: FloatArray = run {
        val rnd = Random(11)
        val out = ArrayList<Float>()
        repeat(340) {
            val u = rnd.nextFloat() * 0.83f - 0.38f
            val center = -0.33f - 0.05f * (1f - ((u - 0.15f) / 0.45f).pow(2))
            val thick = 0.11f - 0.07f * ((u + 0.38f) / 0.83f)
            val v = center + (rnd.nextFloat() - 0.5f) * thick
            val a = rnd.nextFloat() * 0.5f
            out.add(u); out.add(v); out.add(sin(a)); out.add(cos(a))
        }
        out.toFloatArray()
    }
}
