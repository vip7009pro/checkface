package com.hnpage.facecheck

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class FaceRepository(context: Context) {

    private val sharedPreferences = context.getSharedPreferences("face_data_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val facesKey = "registered_faces"
    private val expectedEmbeddingDim = 192 // Kích thước embedding kỳ vọng của MobileFaceNet

    // Lưu trữ danh sách khuôn mặt
    fun saveFaces(faces: List<FaceData>) {
        val validFaces = faces.filter {
            if (it.faceEmbedding.size != expectedEmbeddingDim) {
                Log.w("FaceRepository", "Invalid embedding size for ${it.employeeName}: ${it.faceEmbedding.size}, expected $expectedEmbeddingDim")
                false
            } else true
        }
        val jsonString = gson.toJson(validFaces)
        sharedPreferences.edit().putString(facesKey, jsonString).apply()
        Log.d("FaceRepository", "Saved ${validFaces.size} faces")
    }

    // Tải danh sách khuôn mặt
    fun loadFaces(): MutableList<FaceData> {
        val jsonString = sharedPreferences.getString(facesKey, null)
        return if (jsonString != null) {
            val type = object : TypeToken<MutableList<FaceData>>() {}.type
            val faces: MutableList<FaceData> = gson.fromJson(jsonString, type)
            val validFaces = faces.filter {
                if (it.faceEmbedding.size != expectedEmbeddingDim) {
                    Log.w("FaceRepository", "Invalid embedding size for ${it.employeeName}: ${it.faceEmbedding.size}, expected $expectedEmbeddingDim")
                    false
                } else true
            }.toMutableList()
            Log.d("FaceRepository", "Loaded ${validFaces.size} valid faces out of ${faces.size}")
            validFaces
        } else {
            Log.d("FaceRepository", "No faces found in SharedPreferences")
            mutableListOf()
        }
    }

    // Thêm một khuôn mặt mới
    fun addFace(face: FaceData) {
        if (face.faceEmbedding.size != expectedEmbeddingDim) {
            Log.e("FaceRepository", "Cannot add face ${face.employeeName}: invalid embedding size ${face.faceEmbedding.size}, expected $expectedEmbeddingDim")
            return
        }
        val faces = loadFaces()
        faces.add(face)
        saveFaces(faces)
        Log.d("FaceRepository", "Added face for ${face.employeeName}")
    }

    // Xóa tất cả dữ liệu khuôn mặt (để đăng ký lại)
    fun clearFaces() {
        sharedPreferences.edit().remove(facesKey).apply()
        Log.d("FaceRepository", "Cleared all face data")
    }
}