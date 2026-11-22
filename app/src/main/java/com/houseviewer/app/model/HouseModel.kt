package com.houseviewer.app.model

data class HouseModel(
    val id: String,
    val displayName: String,
    val category: String,
    val objPath: String,
    val mtlPath: String?,
    val isBuiltIn: Boolean
)

class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val colors: FloatArray,
    val vertexCount: Int,
    val boundingExtent: Float = 2.2f
) {
    val triangleCount: Int
        get() = vertexCount / 3
}
