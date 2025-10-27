package com.hnpage.facecheck

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executor
import android.graphics.YuvImage
import androidx.compose.ui.graphics.toAndroidRectF
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RegistrationScreen(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    val faceRepository = remember { FaceRepository(context) }
    val faceNetModel = remember { FaceNetModel(context) }
    var employeeName by remember { mutableStateOf("") }
    var isRegistering by remember { mutableStateOf(false) }

    val cameraController = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Đăng ký Nhân viên") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (cameraPermissionState.status.isGranted) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                controller = cameraController
                                cameraController.bindToLifecycle(lifecycleOwner)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                OutlinedTextField(
                    value = employeeName,
                    onValueChange = { employeeName = it },
                    label = { Text("Nhập tên nhân viên") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    singleLine = true
                )

                Button(
                    onClick = {
                        if (employeeName.isNotBlank() && !isRegistering) {
                            isRegistering = true
                            captureAndProcessFace(context, cameraController, employeeName, faceRepository, faceNetModel) { success ->
                                isRegistering = false
                                if (success) {
                                    navController.popBackStack()
                                }
                            }
                        } else {
                            Toast.makeText(context, "Vui lòng nhập tên nhân viên", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    enabled = !isRegistering
                ) {
                    Text(if (isRegistering) "Đang xử lý..." else "Chụp và Đăng ký")
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
                Text("Cần quyền truy cập camera để đăng ký.")
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

private fun captureAndProcessFace(
    context: Context,
    cameraController: LifecycleCameraController,
    employeeName: String,
    faceRepository: FaceRepository,
    faceNetModel: FaceNetModel,
    onResult: (Boolean) -> Unit
) {
    val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    cameraController.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
        override fun onCaptureSuccess(imageProxy: ImageProxy) {
            val bitmap = imageProxy.image?.toBitmap() ?: run {
                Log.e("RegistrationScreen", "No image in ImageProxy")
                onResult(false)
                imageProxy.close()
                return
            }
            val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)

            processBitmapForRegistration(context, rotatedBitmap, employeeName, faceRepository, faceNetModel, onResult)

            imageProxy.close()
        }

        override fun onError(exception: ImageCaptureException) {
            Log.e("RegistrationScreen", "Image capture failed: ${exception.message}", exception)
            Toast.makeText(context, "Chụp ảnh thất bại: ${exception.message}", Toast.LENGTH_LONG).show()
            onResult(false)
        }
    })
}

private fun processBitmapForRegistration(
    context: Context,
    bitmap: Bitmap,
    employeeName: String,
    faceRepository: FaceRepository,
    faceNetModel: FaceNetModel,
    onResult: (Boolean) -> Unit
) {
    val image = InputImage.fromBitmap(bitmap, 0)
    val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setMinFaceSize(0.15f)
        .build()
    val detector = FaceDetection.getClient(detectorOptions)

    detector.process(image)
        .addOnSuccessListener { faces ->
            if (faces.isNotEmpty()) {
                val firstFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                Log.d("RegistrationScreen", "Face detected: boundingBox=${firstFace.boundingBox}")

                val faceBitmap = faceNetModel.cropFace(bitmap, firstFace.boundingBox.toComposeRect().toAndroidRectF())

                if (faceBitmap != null) {
                    val realEmbedding = faceNetModel.getFaceEmbedding(faceBitmap)
                    Log.d("RegistrationScreen", "Embedding size for $employeeName: ${realEmbedding.size}")

                    val newFace = FaceData(employeeName, realEmbedding)
                    faceRepository.addFace(newFace)

                    Log.i("RegistrationScreen", "Khuôn mặt đã được đăng ký cho: $employeeName")
                    Toast.makeText(context, "Đăng ký thành công cho $employeeName!", Toast.LENGTH_SHORT).show()
                    val embeddingSnippet = realEmbedding.take(5).joinToString(
                        separator = ", ",
                        prefix = "[",
                        postfix = ", ...]"
                    ) { String.format("%.4f", it) }
                    Toast.makeText(context, "Embedding: $embeddingSnippet", Toast.LENGTH_LONG).show()

                    onResult(true)
                } else {
                    Log.e("RegistrationScreen", "Failed to crop face")
                    Toast.makeText(context, "Không thể cắt khuôn mặt. Vui lòng thử lại.", Toast.LENGTH_LONG).show()
                    onResult(false)
                }
            } else {
                Log.w("RegistrationScreen", "Không tìm thấy khuôn mặt nào trong ảnh.")
                Toast.makeText(context, "Không tìm thấy khuôn mặt. Vui lòng thử lại.", Toast.LENGTH_LONG).show()
                onResult(false)
            }
        }
        .addOnFailureListener { e ->
            Log.e("RegistrationScreen", "Xử lý khuôn mặt thất bại", e)
            Toast.makeText(context, "Xử lý khuôn mặt thất bại: ${e.message}", Toast.LENGTH_LONG).show()
            onResult(false)
        }
}

// Hàm tiện ích: Chuyển đổi Image sang Bitmap
fun android.media.Image.toBitmap(): Bitmap {
    if (format == android.graphics.ImageFormat.JPEG) {
        val buffer = planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } else if (format == android.graphics.ImageFormat.YUV_420_888) {
        val yBuffer = planes[0].buffer // Y
        val uBuffer = planes[1].buffer // U
        val vBuffer = planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, android.graphics.ImageFormat.NV21, this.width, this.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
    Log.e("ImageUtil", "Unsupported image format: $format")
    return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
}

// Hàm tiện ích: Xoay Bitmap
fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
    if (rotationDegrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}