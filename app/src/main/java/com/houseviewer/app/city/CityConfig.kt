package com.houseviewer.app.city

enum class TensorFieldStyle(val label: String) {
    MIXED_ORGANIC("Mixed Organic (Radial + Grid)"),
    RADIAL_OLD_TOWN("Radial Old Town"),
    GRID_PLAZAS("Grid & Cathedral Plazas")
}

data class CityConfig(
    val citySize: Float = 56f,
    val houseCount: Int = 65,
    val cathedralCount: Int = 2,
    val fieldStyle: TensorFieldStyle = TensorFieldStyle.MIXED_ORGANIC,
    val roadSpacing: Float = 7.5f,
    val seed: Long = System.currentTimeMillis()
)

data class GeneratedCityResult(
    val mesh: com.houseviewer.app.model.MeshData,
    val placedHouses: Int,
    val placedCathedrals: Int,
    val roadSegments: Int,
    val seedUsed: Long
)
