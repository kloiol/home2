package com.houseviewer.app.city

enum class PlateShape(val label: String) {
    SQUARE("Square / Rectangle"),
    HEXAGON("Hexagon"),
    SEMICIRCLE("Semicircle"),
    FIVE_POINT_STAR("5-Pointed Star"),
    RHOMBUS("Rhombus")
}

enum class TensorFieldStyle(val label: String) {
    MIXED_ORGANIC("Mixed Organic (Radial + Grid)"),
    RADIAL_OLD_TOWN("Radial Old Town"),
    GRID_PLAZAS("Grid & Cathedral Plazas"),
    VILLAGE_FIELDS("Rural Village & Fields"),
    SOVIET_MICRORAYON("Soviet Microrayon (Khrushchevkas Only)")
}

data class CityConfig(
    val plateShape: PlateShape = PlateShape.SQUARE,
    val plateRadius: Float = 30f,
    val diagonalRatio: Float = 1.0f,     // 0.45..1.55 (secondary diagonal / aspect / star valley depth)
    val edgeCurvature: Float = 0.0f,     // -0.25..+0.40 (bows edges inward or outward)
    val areaCoverage: Float = 0.85f,     // 0.25..1.00 (fraction of plate surface area used by city)
    val houseCount: Int = 65,
    val cathedralCount: Int = 2,
    val fieldStyle: TensorFieldStyle = TensorFieldStyle.MIXED_ORGANIC,
    val roadSpacing: Float = 7.5f,
    val includeFields: Boolean = true,
    val seed: Long = System.currentTimeMillis()
) {
    val citySize: Float
        get() = plateRadius * 2f
}

data class GeneratedCityResult(
    val mesh: com.houseviewer.app.model.MeshData,
    val placedHouses: Int,
    val placedCathedrals: Int,
    val placedFields: Int,
    val placedLamps: Int,
    val roadSegments: Int,
    val isSovietMode: Boolean,
    val seedUsed: Long
)
