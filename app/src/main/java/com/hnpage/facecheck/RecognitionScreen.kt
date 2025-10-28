package com.hnpage.facecheck

import android.app.Activity
import android.graphics.RectF
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toAndroidRectF
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.max

// Hằng số cho việc phân tích hình ảnh
private const val ANALYSIS_INTERVAL_MS = 200L // Phân tích 1 frame mỗi giây
private const val RECOGNITION_COOLDOWN_MS = 1000L // Chờ 3 giây sau khi nhận diện thành công
private const val FACE_RECOGNITION_THRESHOLD = 0.7f // Ngưỡng nhận diện
private const val SCREEN_DIM_DELAY_MS = 5000L // Thời gian chờ trước khi làm tối màn hình

// Enum để quản lý trạng thái phân tích
enum class AnalysisStatus {
    IDLE, SUCCESS, FAILURE
}


@kotlin.OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RecognitionScreen(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    val faceRepository = remember { FaceRepository(context) }
    val faceNetModel = remember { FaceNetModel(context) }

    var registeredFaces by remember { mutableStateOf<List<FaceData>>(emptyList()) }
    var recognizedEmployee by remember { mutableStateOf<String?>(null) }
    var similarityScore by remember { mutableStateOf<Float?>(null) }
    var faceBoundingBox by remember { mutableStateOf<RectF?>(null) }
    var imageSize by remember { mutableStateOf(Size(0f, 0f)) }


    var lastAnalysisTime by remember { mutableStateOf(0L) }
    var lastRecognitionTime by remember { mutableStateOf(0L) }
    var analysisStatus by remember { mutableStateOf(AnalysisStatus.IDLE) }
    var isFaceDetected by remember { mutableStateOf(false) }


    // Tải danh sách khuôn mặt đã đăng ký
    LaunchedEffect(key1 = lifecycleOwner) {
        registeredFaces = faceRepository.loadFaces()
        if (registeredFaces.isEmpty()) {
            Toast.makeText(context, "Chưa có nhân viên nào được đăng ký", Toast.LENGTH_LONG).show()
        } else {
            Log.d("RecognitionScreen", "Loaded ${registeredFaces.size} registered faces")
        }
    }

    val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> = remember {
        ProcessCameraProvider.getInstance(context)
    }
    val previewView = remember { PreviewView(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // Quản lý màn hình
    val activity = context as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            cameraExecutor.shutdown()
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Reset độ sáng khi thoát
            activity?.window?.attributes = activity.window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    // Coroutine để quản lý độ sáng màn hình
    LaunchedEffect(isFaceDetected) {
        val dimJob: Job = launch {
            if (!isFaceDetected) {
                delay(SCREEN_DIM_DELAY_MS)
                activity?.window?.attributes = activity.window.attributes.apply {
                    screenBrightness = 0.1f // Làm tối màn hình
                }
                Log.d("RecognitionScreen", "Screen dimmed")
            }
        }

        if (isFaceDetected) {
            dimJob.cancel() // Hủy việc làm tối nếu phát hiện khuôn mặt
            activity?.window?.attributes = activity.window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE // Khôi phục độ sáng
            }
            Log.d("RecognitionScreen", "Screen brightness restored")
        }
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Điểm danh Nhân viên") },
                actions = {
                    TextButton(onClick = {
                        faceRepository.clearFaces()
                        registeredFaces = emptyList()
                        Toast.makeText(context, "Đã xóa toàn bộ dữ liệu khuôn mặt", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Xóa dữ liệu")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (cameraPermissionState.status.isGranted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                    update = {
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build().apply {
                                    setAnalyzer(cameraExecutor) { imageProxy ->
                                        val currentTime = System.currentTimeMillis()
                                        if (currentTime - lastAnalysisTime >= ANALYSIS_INTERVAL_MS && currentTime - lastRecognitionTime >= RECOGNITION_COOLDOWN_MS) {
                                            lastAnalysisTime = currentTime
                                            processImageForRecognition(
                                                imageProxy, faceNetModel, registeredFaces
                                            ) { name, rect, score ->
                                                ContextCompat.getMainExecutor(context).execute {
                                                    isFaceDetected = rect != null
                                                    faceBoundingBox = rect
                                                    imageSize = Size(
                                                        imageProxy.height.toFloat(),
                                                        imageProxy.width.toFloat()
                                                    ) // Lưu kích thước ảnh đã xoay

                                                    if (name != null) {
                                                        recognizedEmployee = name
                                                        similarityScore = score
                                                        lastRecognitionTime = System.currentTimeMillis()
                                                        analysisStatus = AnalysisStatus.SUCCESS
                                                        Log.d("RecognitionScreen", "Recognized: $name with score $score")
                                                    } else if (rect != null) {
                                                        // Khuôn mặt được phát hiện nhưng không nhận dạng được
                                                        analysisStatus = AnalysisStatus.FAILURE
                                                        Log.d("RecognitionScreen", "Face detected but not recognized")
                                                    } else {
                                                        // Không có khuôn mặt nào
                                                        analysisStatus = AnalysisStatus.IDLE
                                                    }
                                                }
                                            }
                                        } else {
                                            imageProxy.close()
                                        }
                                    }
                                }

                            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner, cameraSelector, preview, imageAnalysis
                                )
                            } catch (e: Exception) {
                                Log.e("RecognitionScreen", "Use case binding failed", e)
                            }
                        }, ContextCompat.getMainExecutor(context))
                    }
                )

                // Vẽ hộp bao quanh khuôn mặt
                faceBoundingBox?.let { box ->
                    DrawBoundingBox(
                        box = box,
                        imageSize = imageSize,
                        isSuccess = analysisStatus == AnalysisStatus.SUCCESS
                    )
                }


                // Chỉ thị màu trạng thái phân tích
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(
                            when (analysisStatus) {
                                AnalysisStatus.SUCCESS -> Color.Green
                                AnalysisStatus.FAILURE -> Color.Red
                                AnalysisStatus.IDLE -> Color.Gray
                            }
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    val textToShow = when {
                        recognizedEmployee != null -> {
                            val scoreText = similarityScore?.let { " (Score: %.2f)".format(it) } ?: ""
                            "Chào mừng, $recognizedEmployee!$scoreText"
                        }
                        registeredFaces.isEmpty() -> "Vui lòng đăng ký nhân viên mới."
                        isFaceDetected -> "Đang nhận diện..."
                        else -> "Vui lòng nhìn vào camera..."
                    }

                    Text(
                        text = textToShow,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    Button(onClick = { navController.navigate(AppDestinations.REGISTRATION_ROUTE) }) {
                        Text("Đăng ký nhân viên mới")
                    }
                }
            }

            // Xóa tên nhân viên đã nhận diện và reset trạng thái sau một khoảng thời gian
            LaunchedEffect(recognizedEmployee) {
                if (recognizedEmployee != null) {
                    delay(RECOGNITION_COOLDOWN_MS)
                    recognizedEmployee = null
                    similarityScore = null
                    faceBoundingBox = null
                    isFaceDetected = false
                    analysisStatus = AnalysisStatus.IDLE
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Cần quyền truy cập camera để điểm danh.")
                Button(onClick = { cameraPermissionState.launchPermissionRequest() }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Yêu cầu quyền")
                }
            }
        }
    }
}

@Composable
fun DrawBoundingBox(box: RectF, imageSize: Size, isSuccess: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (imageSize.width > 0 && imageSize.height > 0) {
            // Camera trước thường bị lật ngược theo chiều ngang
            val mirroredBox = RectF(
                imageSize.width - box.right,
                box.top,
                imageSize.width - box.left,
                box.bottom
            )

            // Tính toán tỉ lệ và offset để vẽ trên màn hình
            val scaleX = size.width / imageSize.width
            val scaleY = size.height / imageSize.height
            // Sử dụng scale lớn hơn để lấp đầy và cắt (giống PreviewView.ScaleType.FILL_CENTER)
            val scale = max(scaleX, scaleY)

            val offsetX = (size.width - imageSize.width * scale) / 2
            val offsetY = (size.height - imageSize.height * scale) / 2

            val drawRect = androidx.compose.ui.geometry.Rect(
                left = mirroredBox.left * scale + offsetX,
                top = mirroredBox.top * scale + offsetY,
                right = mirroredBox.right * scale + offsetX,
                bottom = mirroredBox.bottom * scale + offsetY
            )

            drawRect(
                color = if (isSuccess) Color.Green else Color.Red,
                topLeft = drawRect.topLeft,
                size = drawRect.size,
                style = Stroke(width = 4.dp.toPx())
            )
        }
    }
}


@OptIn(ExperimentalGetImage::class)
private fun processImageForRecognition(
    imageProxy: ImageProxy,
    faceNetModel: FaceNetModel,
    registeredFaces: List<FaceData>,
    onResult: (String?, RectF?, Float?) -> Unit
) {
    val mediaImage = imageProxy.image ?: run {
        imageProxy.close()
        onResult(null, null, null)
        return
    }
    // Kích thước ảnh gốc trước khi xoay (cho việc chuyển đổi tọa độ)
    val rotatedBitmap = rotateBitmap(mediaImage.toBitmap(), imageProxy.imageInfo.rotationDegrees)

    val image = InputImage.fromBitmap(rotatedBitmap, 0)
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
    )

    detector.process(image)
        .addOnSuccessListener { faces ->
            if (faces.isNotEmpty()) {
                val firstFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                val faceRect = firstFace.boundingBox.toComposeRect().toAndroidRectF()

                val faceBitmap = faceNetModel.cropFace(rotatedBitmap, faceRect)

                if (faceBitmap != null) {
                    try {
                        val currentEmbedding = faceNetModel.getFaceEmbedding(faceBitmap)
                        var bestMatch: Pair<String, Float>? = null

                        for (registeredFace in registeredFaces) {
                            try {
                                val similarity = FaceNetModel.cosineSimilarity(currentEmbedding, registeredFace.faceEmbedding)
                                if (similarity > (bestMatch?.second ?: 0f)) {
                                    bestMatch = Pair(registeredFace.employeeName, similarity)
                                }
                            } catch (e: Exception) {
                                Log.e("RecognitionScreen", "Error calculating similarity", e)
                            }
                        }

                        if (bestMatch != null && bestMatch.second >= FACE_RECOGNITION_THRESHOLD) {
                            onResult(bestMatch.first, faceRect, bestMatch.second)
                        } else {
                            // Phát hiện khuôn mặt nhưng không khớp
                            onResult(null, faceRect, bestMatch?.second)
                        }
                    } catch (e: Exception) {
                        Log.e("RecognitionScreen", "Error getting embedding", e)
                        onResult(null, faceRect, null) // Lỗi, trả về hộp bao
                    }
                } else {
                    onResult(null, faceRect, null) // Không cắt được mặt, trả về hộp bao
                }
            } else {
                onResult(null, null, null) // Không có khuôn mặt
            }
        }
        .addOnFailureListener { e ->
            Log.e("RecognitionScreen", "Face detection failed", e)
            onResult(null, null, null)
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}
