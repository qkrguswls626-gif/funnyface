package com.funnyface.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var warpView: WarpView
    private val effectButtons = mutableListOf<Button>()
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var useFront = true
    @Volatile private var effect = Effect.CRY

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.15f)
                .build()
        )
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "카메라 권한이 필요해요", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ───────────── 화면 구성 ─────────────

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        warpView = WarpView(this)
        root.addView(warpView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(24))
            setBackgroundColor(Color.argb(110, 0, 0, 0))
        }

        // 효과 고르기 버튼 줄
        val effectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (e in Effect.values()) {
            val b = Button(this).apply {
                text = e.label
                isAllCaps = false
                setOnClickListener { selectEffect(e) }
            }
            effectButtons.add(b)
            effectRow.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(effectRow)
        }
        bottom.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 촬영 / 카메라 전환 줄
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val shutter = Button(this).apply {
            text = "📸 찍기"
            textSize = 20f
            setOnClickListener { takePhoto() }
        }
        val flip = Button(this).apply {
            text = "🔄 전환"
            setOnClickListener {
                useFront = !useFront
                startCamera()
            }
        }
        actionRow.addView(shutter, LinearLayout.LayoutParams(WRAP_CONTENT, dp(64)))
        actionRow.addView(flip, LinearLayout.LayoutParams(WRAP_CONTENT, dp(64)).apply {
            marginStart = dp(16)
        })
        bottom.addView(actionRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        root.addView(
            bottom,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
        )
        selectEffect(effect)
        return root
    }

    private fun selectEffect(e: Effect) {
        effect = e
        Effect.values().forEachIndexed { i, item ->
            effectButtons[i].alpha = if (item == e) 1f else 0.55f
        }
    }

    // ───────────── 카메라 ─────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val front = useFront

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy, front) }

            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "카메라를 열 수 없음", e)
                Toast.makeText(this, "카메라를 열 수 없어요", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 카메라 프레임 한 장: 똑바로 세우기 → 얼굴 찾기 → 변형 격자 계산 → 화면에 전달 */
    private fun processFrame(proxy: ImageProxy, front: Boolean) {
        try {
            val raw = proxy.toBitmap()
            val m = Matrix().apply {
                postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                if (front) postScale(-1f, 1f)   // 셀카는 거울처럼 좌우 반전
            }
            val frame = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (frame !== raw) raw.recycle()

            val faces: List<Face> = try {
                Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)))
            } catch (e: Exception) {
                emptyList()
            }

            val result = Warp.build(frame.width, frame.height, faces, effect)
            warpView.post { warpView.setFrame(frame, result) }
        } catch (e: Exception) {
            Log.e(TAG, "프레임 처리 실패", e)
        } finally {
            proxy.close()
        }
    }

    // ───────────── 사진 저장 ─────────────

    private fun takePhoto() {
        val photo = warpView.snapshot()
        if (photo == null) {
            Toast.makeText(this, "아직 화면이 준비되지 않았어요", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val ok = try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "funny_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FunnyFace")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: throw IllegalStateException("저장 위치를 만들 수 없음")
                contentResolver.openOutputStream(uri)?.use {
                    photo.compress(Bitmap.CompressFormat.JPEG, 92, it)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                true
            } catch (e: Exception) {
                Log.e(TAG, "저장 실패", e)
                false
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "갤러리(FunnyFace)에 저장했어요" else "저장에 실패했어요",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detector.close()
    }

    companion object {
        private const val TAG = "FunnyFace"
    }
}
