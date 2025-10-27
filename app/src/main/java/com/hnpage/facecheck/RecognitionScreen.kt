package com.hnpage.facecheck

import android.graphics.RectF
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAndroidRectF
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

// Hằng số cho việc phân tích hình ảnh
private const val ANALYSIS_INTERVAL_MS = 1000L // Phân tích 1 frame mỗi giây
private const val RECOGNITION_COOLDOWN_MS = 3000L // Chờ 3 giây sau khi nhận diện thành công
private const val FACE_RECOGNITION_THRESHOLD = 0.7f // Giảm ngưỡng để dễ nhận diện hơn

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
    var lastAnalysisTime by remember { mutableStateOf(0L) }
    var lastRecognitionTime by remember { mutableStateOf(0L) }
    var analysisStatus by remember { mutableStateOf(AnalysisStatus.IDLE) }

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

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Điểm danh Nhân viên") },
                actions = {
                    // Nút để xóa dữ liệu và đăng ký lại
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
                                            ) { name, _ ->
                                                ContextCompat.getMainExecutor(context).execute {
                                                    if (name != null) {
                                                        recognizedEmployee = name
                                                        lastRecognitionTime = System.currentTimeMillis()
                                                        analysisStatus = AnalysisStatus.SUCCESS
                                                        Log.d("RecognitionScreen", "Recognized: $name")
                                                    } else {
                                                        analysisStatus = AnalysisStatus.FAILURE
                                                        Log.d("RecognitionScreen", "No face recognized")
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
                                Log.d("RecognitionScreen", "Camera bound successfully")
                            } catch (e: Exception) {
                                Log.e("RecognitionScreen", "Use case binding failed", e)
                                ContextCompat.getMainExecutor(context).execute {
                                    Toast.makeText(context, "Lỗi khởi tạo camera", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }, ContextCompat.getMainExecutor(context))
                    }
                )

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
                    Text(
                        text = when {
                            recognizedEmployee != null -> "Chào mừng, $recognizedEmployee!"
                            registeredFaces.isEmpty() -> "Vui lòng đăng ký nhân viên mới."
                            else -> "Vui lòng nhìn vào camera..."
                        },
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
                    analysisStatus = AnalysisStatus.IDLE
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Cần quyền truy cập camera để điểm danh.")
                Button(
                    onClick = { cameraPermissionState.launchPermissionRequest() },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Yêu cầu quyền")
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun processImageForRecognition(
    imageProxy: ImageProxy,
    faceNetModel: FaceNetModel,
    registeredFaces: List<FaceData>,
    onResult: (String?, RectF?) -> Unit
) {
    val mediaImage = imageProxy.image ?: run {
        Log.e("RecognitionScreen", "No image in ImageProxy")
        imageProxy.close()
        onResult(null, null)
        return
    }
    val rotatedBitmap = rotateBitmap(mediaImage.toBitmap(), imageProxy.imageInfo.rotationDegrees)

    val image = InputImage.fromBitmap(rotatedBitmap, 0)
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build()
    )

    detector.process(image)
        .addOnSuccessListener { faces ->
            Log.d("RecognitionScreen", "Detected ${faces.size} faces")
            if (faces.isNotEmpty()) {
                val firstFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                Log.d("RecognitionScreen", "Face detected: boundingBox=${firstFace.boundingBox}, landmarks=${firstFace.allLandmarks}")

                val faceBitmap = faceNetModel.cropFace(
                    rotatedBitmap, firstFace.boundingBox.toComposeRect().toAndroidRectF()
                )

                if (faceBitmap != null) {
                    Log.d("RecognitionScreen", "FaceBitmap: width=${faceBitmap.width}, height=${faceBitmap.height}")

                    try {
                        val currentEmbedding = faceNetModel.getFaceEmbedding(faceBitmap)
                        Log.d("RecognitionScreen", "Current embedding size: ${currentEmbedding.size}")

                        var bestMatch: Pair<String, Float>? = null
                        for (registeredFace in registeredFaces) {
                            Log.d("RecognitionScreen", "Registered face: ${registeredFace.employeeName}, embedding size: ${registeredFace.faceEmbedding.size}")
                            try {
                                val similarity = FaceNetModel.cosineSimilarity(
                                    currentEmbedding, registeredFace.faceEmbedding
                                )
                                if (similarity > (bestMatch?.second ?: 0f)) {
                                    bestMatch = Pair(registeredFace.employeeName, similarity)
                                }
                            } catch (e: IllegalArgumentException) {
                                Log.e("RecognitionScreen", "Cosine similarity failed for ${registeredFace.employeeName}: ${e.message}")
                            }
                        }

                        if (bestMatch != null && bestMatch.second >= FACE_RECOGNITION_THRESHOLD) {
                            Log.d("RecognitionScreen", "Match found: ${bestMatch.first} with similarity ${bestMatch.second}")
                            onResult(bestMatch.first, firstFace.boundingBox.toComposeRect().toAndroidRectF())
                        } else {
                            Log.d("RecognitionScreen", "No match found, best similarity: ${bestMatch?.second}")
                            onResult(null, firstFace.boundingBox.toComposeRect().toAndroidRectF())
                        }
                    } catch (e: Exception) {
                        Log.e("RecognitionScreen", "Error processing embedding: ${e.message}")
                        onResult(null, firstFace.boundingBox.toComposeRect().toAndroidRectF())
                    }
                } else {
                    Log.e("RecognitionScreen", "Failed to crop face")
                    onResult(null, firstFace.boundingBox.toComposeRect().toAndroidRectF())
                }
            } else {
                Log.d("RecognitionScreen", "No faces detected")
                onResult(null, null)
            }
        }
        .addOnFailureListener { e ->
            Log.e("RecognitionScreen", "Face detection failed", e)
            onResult(null, null)
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}