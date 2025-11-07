package com.hnpage.facecheck.models

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class FaceNetModel(context: Context) {

    private var interpreter: Interpreter
    private val imageProcessor: ImageProcessor
    private val imageSize = 112
    private val embeddingDim = 192 // Kích thước embedding của MobileFaceNet

    init {
        val modelBuffer = FileUtil.loadMappedFile(context, "mobile_facenet.tflite")
        val options = Interpreter.Options()
        interpreter = Interpreter(modelBuffer, options)

        imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(imageSize, imageSize, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(127.5f, 127.5f))
            .build()
    }

    fun getFaceEmbedding(image: Bitmap): FloatArray {
        var tensorImage = TensorImage.fromBitmap(image)
        tensorImage = imageProcessor.process(tensorImage)

        val faceEmbedding = Array(1) { FloatArray(embeddingDim) }
        interpreter.run(tensorImage.buffer, faceEmbedding)

        val embedding = normalize(faceEmbedding[0])
        Log.d("FaceNetModel", "Generated embedding size: ${embedding.size}")
        return embedding
    }

    fun cropFace(bitmap: Bitmap, boundingBox: RectF): Bitmap? {
        val left = max(0f, boundingBox.left)
        val top = max(0f, boundingBox.top)
        val right = min(bitmap.width.toFloat(), boundingBox.right)
        val bottom = min(bitmap.height.toFloat(), boundingBox.bottom)

        val width = (right - left).toInt()
        val height = (bottom - top).toInt()

        if (width <= 0 || height <= 0) {
            Log.e("FaceNetModel", "Invalid crop dimensions: width=$width, height=$height")
            return null
        }

        return try {
            Bitmap.createBitmap(bitmap, left.toInt(), top.toInt(), width, height)
        } catch (e: Exception) {
            Log.e("FaceNetModel", "Failed to crop face: ${e.message}")
            null
        }
    }

    private fun normalize(embedding: FloatArray): FloatArray {
        val sumOfSquares = embedding.sumOf { it.toDouble().pow(2) }
        val magnitude = sqrt(sumOfSquares).toFloat()
        return if (magnitude > 0) {
            embedding.map { it / magnitude }.toFloatArray()
        } else {
            embedding
        }
    }

    companion object {
        fun cosineSimilarity(emb1: FloatArray, emb2: FloatArray): Float {
            if (emb1.size != emb2.size) {
                Log.e("FaceNetModel", "Embedding dimensions don't match! emb1: ${emb1.size}, emb2: ${emb2.size}")
                return -1f
            }
            val dotProduct = emb1.zip(emb2).sumOf { (a, b) -> (a * b).toDouble() }
            return dotProduct.toFloat()
        }
    }
}

// Dữ liệu khuôn mặt được lưu trữ
data class FaceData(
    val employeeName: String,
    val faceEmbedding: FloatArray // Đặc trưng khuôn mặt (embedding)
) {
    // Cần thiết để so sánh các mảng FloatArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FaceData

        if (employeeName != other.employeeName) return false
        if (!faceEmbedding.contentEquals(other.faceEmbedding)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = employeeName.hashCode()
        result = 31 * result + faceEmbedding.contentHashCode()
        return result
    }
}