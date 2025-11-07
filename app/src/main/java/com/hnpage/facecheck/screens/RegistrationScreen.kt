package com.hnpage.facecheck.screens

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch // Thêm import này
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toAndroidRectF
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.max
import android.graphics.YuvImage
import android.media.Image
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hnpage.facecheck.models.FaceData
import com.hnpage.facecheck.models.FaceNetModel
import com.hnpage.facecheck.navigation.LocalNavController
import com.hnpage.facecheck.repos.FaceRepository
import com.hnpage.facecheck.viewmodels.AppViewModel

@kotlin.OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RegistrationScreen(
    viewModel: AppViewModel = hiltViewModel(),
    lifecycleOwner: LifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current,
    cameraPermissionState: PermissionState = rememberPermissionState(Manifest.permission.CAMERA)
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val faceRepository = remember { FaceRepository(context) }
    val faceNetModel = remember { FaceNetModel(context) }
    var employeeName by remember { mutableStateOf("") }
    var isRegistering by remember { mutableStateOf(false) }

    // State cho việc phát hiện khuôn mặt
    var faceBoundingBox by remember { mutableStateOf<RectF?>(null) }
    var isFaceInBounds by remember { mutableStateOf(false) }
    var imageSize by remember { mutableStateOf(Size(0, 0)) }

    // State để quản lý camera trước/sau
    var isFrontCamera by remember { mutableStateOf(true) }
    val currentCameraSelector = remember(isFrontCamera) {
        if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember { ImageCapture.Builder().build() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }


    Scaffold(topBar = {
        TopAppBar(title = { Text("Đăng ký Nhân viên") }, navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }, actions = {
            // Nút chuyển đổi camera
            IconButton(onClick = {
                isFrontCamera = !isFrontCamera
                Toast.makeText(context, if (isFrontCamera) "Chuyển sang cam trước" else "Chuyển sang cam sau", Toast.LENGTH_SHORT).show()
            }) {
                Icon(
                    imageVector = Icons.Filled.Cameraswitch,
                    contentDescription = "Chuyển đổi camera"
                )
            }
        })
    }) { paddingValues ->
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
                    val previewView = remember { PreviewView(context) }
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize(),
                        update = {
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()

                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val imageAnalysis =
                                    ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build().apply {
                                            setAnalyzer(cameraExecutor) { imageProxy ->
                                                processImageForFaceDetection(imageProxy) { rect ->
                                                    ContextCompat.getMainExecutor(context)
                                                        .execute {
                                                            faceBoundingBox = rect
                                                            // Kích thước đã xoay
                                                            imageSize = Size(imageProxy.height, imageProxy.width)
                                                        }
                                                }
                                            }
                                        }

                                // Sử dụng currentCameraSelector đã được quản lý bởi state
                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        currentCameraSelector, // Sử dụng currentCameraSelector
                                        preview,
                                        imageAnalysis,
                                        imageCapture
                                    )
                                } catch (e: Exception) {
                                    Log.e("RegistrationScreen", "Use case binding failed", e)
                                }
                            }, ContextCompat.getMainExecutor(context))
                        })

                    // Vẽ khung hướng dẫn và hộp bao khuôn mặt
                    FaceRegistrationOverlay(box = faceBoundingBox,
                        imageSize = imageSize,
                        isFrontCamera = isFrontCamera, // Truyền thông tin camera để xử lý lật ảnh
                        onFaceInBoundsChange = { isFaceInBounds = it })
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
                            captureAndProcessFace(
                                context,
                                imageCapture,
                                employeeName,
                                faceRepository,
                                faceNetModel,
                                isFrontCamera // Truyền thông tin camera
                            ) { success ->
                                isRegistering = false
                                if (success) {
                                    navController.popBackStack()
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    enabled = !isRegistering && employeeName.isNotBlank() && isFaceInBounds
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


@Composable
private fun FaceRegistrationOverlay(
    box: RectF?,
    imageSize: Size,
    isFrontCamera: Boolean, // Thêm isFrontCamera
    onFaceInBoundsChange: (Boolean) -> Unit
) {
    var isFaceIn by remember { mutableStateOf(false) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val screenWidth = size.width
        val screenHeight = size.height

        // Vẽ khung hướng dẫn (màu trắng)
        val guideBoxWidth = screenWidth * 0.8f
        val guideBoxHeight = screenHeight * 0.6f
        val guideBoxLeft = (screenWidth - guideBoxWidth) / 2
        val guideBoxTop = (screenHeight - guideBoxHeight) / 2
        val guideRect = Rect(
            left = guideBoxLeft,
            top = guideBoxTop,
            right = guideBoxLeft + guideBoxWidth,
            bottom = guideBoxTop + guideBoxHeight
        )
        drawRect(
            color = Color.White, topLeft = guideRect.topLeft, size = guideRect.size, style = Stroke(
                width = 2.dp.toPx()
            )
        )

        var isFaceInCurrentDraw = false
        // Vẽ hộp bao quanh khuôn mặt (nếu có)
        box?.let {
            if (imageSize.width > 0 && imageSize.height > 0) {
                // Lật ngược tọa độ cho camera trước, giữ nguyên cho camera sau
                val transformedBox = if (isFrontCamera) {
                    RectF(
                        imageSize.width - it.right, it.top, imageSize.width - it.left, it.bottom
                    )
                } else {
                    it // Không lật nếu là camera sau
                }


                // Chuyển đổi tọa độ từ ảnh sang màn hình
                val scaleX = screenWidth / imageSize.width
                val scaleY = screenHeight / imageSize.height
                val scale = max(scaleX, scaleY)
                val offsetX = (screenWidth - imageSize.width * scale) / 2
                val offsetY = (screenHeight - imageSize.height * scale) / 2

                val drawRect = Rect(
                    left = transformedBox.left * scale + offsetX,
                    top = transformedBox.top * scale + offsetY,
                    right = transformedBox.right * scale + offsetX,
                    bottom = transformedBox.bottom * scale + offsetY
                )

                // Kiểm tra xem khuôn mặt có nằm trong khung hướng dẫn không
                isFaceInCurrentDraw = guideRect.contains(drawRect.topLeft) && guideRect.contains(
                    drawRect.bottomRight
                )

                drawRect(
                    color = if (isFaceInCurrentDraw) Color.Green else Color.Red,
                    topLeft = drawRect.topLeft,
                    size = drawRect.size,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
        isFaceIn = isFaceInCurrentDraw
    }

    // Cập nhật trạng thái ra bên ngoài Composable một cách an toàn
    LaunchedEffect(isFaceIn) {
        onFaceInBoundsChange(isFaceIn)
    }
}


@OptIn(ExperimentalGetImage::class)
private fun processImageForFaceDetection(
    imageProxy: ImageProxy, onFaceDetected: (RectF?) -> Unit
) {
    val mediaImage = imageProxy.image ?: run {
        imageProxy.close()
        onFaceDetected(null)
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
    )

    detector.process(image).addOnSuccessListener { faces ->
        if (faces.isNotEmpty()) {
            // Lấy khuôn mặt lớn nhất
            val largestFace =
                faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            onFaceDetected(largestFace?.boundingBox?.toComposeRect()?.toAndroidRectF())
        } else {
            onFaceDetected(null)
        }
    }.addOnFailureListener { e ->
        Log.e("RegistrationScreen", "Face detection failed", e)
        onFaceDetected(null)
    }.addOnCompleteListener {
        imageProxy.close()
    }
}


private fun captureAndProcessFace(
    context: Context,
    imageCapture: ImageCapture,
    employeeName: String,
    faceRepository: FaceRepository,
    faceNetModel: FaceNetModel,
    isFrontCamera: Boolean, // Thêm isFrontCamera
    onResult: (Boolean) -> Unit
) {
    val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    imageCapture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
        @OptIn(ExperimentalGetImage::class)
        override fun onCaptureSuccess(imageProxy: ImageProxy) {
            val bitmap = imageProxy.image?.toBitmap() ?: run {
                onResult(false)
                imageProxy.close()
                return
            }
            val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)

            processBitmapForRegistration(
                context, rotatedBitmap, employeeName, faceRepository, faceNetModel, isFrontCamera, onResult // Truyền isFrontCamera
            )

            imageProxy.close()
        }

        override fun onError(exception: ImageCaptureException) {
            Log.e("RegistrationScreen", "Image capture failed: ${exception.message}", exception)
            Toast.makeText(context, "Chụp ảnh thất bại: ${exception.message}", Toast.LENGTH_LONG)
                .show()
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
    isFrontCamera: Boolean, // Thêm isFrontCamera
    onResult: (Boolean) -> Unit
) {
    val image = InputImage.fromBitmap(bitmap, 0)
    val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL).setMinFaceSize(0.15f).build()
    val detector = FaceDetection.getClient(detectorOptions)

    detector.process(image).addOnSuccessListener { faces ->
        if (faces.isNotEmpty()) {
            val firstFace =
                faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!

            // Điều chỉnh bounding box nếu là camera trước (chỉ khi xử lý hình ảnh tĩnh, không phải preview)
            // Tuy nhiên, FaceNetModel.cropFace đã xử lý crop từ bitmap gốc, không cần lật box trước khi crop
            // BoundingBox từ ML Kit đã đúng với bitmap đã xoay.
            // Việc lật chỉ cần cho hiển thị trên UI Preview.
            val faceRect = firstFace.boundingBox.toComposeRect().toAndroidRectF()

            val faceBitmap = faceNetModel.cropFace(
                bitmap, faceRect
            )

            if (faceBitmap != null) {
                val realEmbedding = faceNetModel.getFaceEmbedding(faceBitmap)

                val newFace = FaceData(employeeName, realEmbedding)
                faceRepository.addFace(newFace)

                Toast.makeText(
                    context, "Đăng ký thành công cho $employeeName!", Toast.LENGTH_SHORT
                ).show()
                onResult(true)
            } else {
                Toast.makeText(
                    context, "Không thể cắt khuôn mặt. Vui lòng thử lại.", Toast.LENGTH_LONG
                ).show()
                onResult(false)
            }
        } else {
            Toast.makeText(
                context, "Không tìm thấy khuôn mặt. Vui lòng thử lại.", Toast.LENGTH_LONG
            ).show()
            onResult(false)
        }
    }.addOnFailureListener { e ->
        Toast.makeText(context, "Xử lý khuôn mặt thất bại: ${e.message}", Toast.LENGTH_LONG)
            .show()
        onResult(false)
    }
}

// Hàm tiện ích: Chuyển đổi Image sang Bitmap
fun Image.toBitmap(): Bitmap {
    if (format == ImageFormat.JPEG) {
        val buffer = planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } else if (format == ImageFormat.YUV_420_888) {
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

        val yuvImage =
            YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height), 100, out
        )
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
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