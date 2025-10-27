package com.hnpage.facecheck

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