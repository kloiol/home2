package com.houseviewer.app.city

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Parametric 2D city ground plate supporting Square/Rectangle, Hexagon, Semicircle,
 * 5-Pointed Star, and Rhombus with customizable radius, diagonal/aspect ratio,
 * edge curvature, and area coverage scaling.
 */
class CityPlate(
    val shape: PlateShape,
    val radius: Float,
    val diagonalRatio: Float,
    val edgeCurvature: Float,
    val areaCoverage: Float
) {
    val boundary: List<Vec2>
    val centroid: Vec2
    val maxRadius: Float
    val minX: Float
    val maxX: Float
    val minZ: Float
    val maxZ: Float
    val coverageLinearScale: Float = sqrt(areaCoverage.coerceIn(0.20f, 1.0f))

    init {
        val raw = buildBasePolygon(shape, radius, diagonalRatio.coerceIn(0.45f, 1.55f), edgeCurvature.coerceIn(-0.25f, 0.40f))
        // Compute geometric center (average of vertices — strictly interior for all 5 star-shaped plates)
        var sx = 0f
        var sz = 0f
        for (p in raw) {
            sx += p.x
            sz += p.z
        }
        val cx = sx / raw.size.coerceAtLeast(1)
        val cz = sz / raw.size.coerceAtLeast(1)
        // Recenter polygon so centroid sits at (0, 0) for balanced camera orbit
        boundary = raw.map { Vec2(it.x - cx, it.z - cz) }
        centroid = Vec2(0f, 0f)

        var loX = Float.POSITIVE_INFINITY
        var hiX = Float.NEGATIVE_INFINITY
        var loZ = Float.POSITIVE_INFINITY
        var hiZ = Float.NEGATIVE_INFINITY
        var maxR = 1f
        for (p in boundary) {
            if (p.x < loX) loX = p.x
            if (p.x > hiX) hiX = p.x
            if (p.z < loZ) loZ = p.z
            if (p.z > hiZ) hiZ = p.z
            maxR = max(maxR, hypot(p.x, p.z))
        }
        minX = loX; maxX = hiX; minZ = loZ; maxZ = hiZ
        maxRadius = maxR
    }

    /**
     * Returns true if (x, z) is strictly inside the plate polygon with at least [margin]
     * clearance from every boundary edge. Works for convex AND non-convex shapes (stars!).
     */
    fun contains(x: Float, z: Float, margin: Float = 0f): Boolean {
        if (x < minX + margin || x > maxX - margin || z < minZ + margin || z > maxZ - margin) {
            return false
        }
        if (!pointInPolygon(x, z, boundary)) return false
        if (margin <= 0f) return true
        return distanceToBoundary(x, z) >= margin
    }

    /**
     * Returns true if (x, z) lies inside the active city-coverage sub-region
     * (whose area equals [areaCoverage] * total plate area).
     */
    fun containsInCoverage(x: Float, z: Float, margin: Float = 0f): Boolean {
        val inv = 1f / coverageLinearScale.coerceAtLeast(0.25f)
        return contains(x * inv, z * inv, margin * inv)
    }

    fun distanceToBoundary(x: Float, z: Float): Float {
        var best = Float.POSITIVE_INFINITY
        val n = boundary.size
        for (i in 0 until n) {
            val a = boundary[i]
            val b = boundary[(i + 1) % n]
            val d = TensorField.distPointToSegment(x, z, a, b)
            if (d < best) best = d
        }
        return best
    }

    /**
     * Returns unit tangent vector along the nearest boundary edge, plus distance to that edge,
     * allowing the tensor field to smoothly bend roads parallel to curved/angled plate borders.
     */
    fun nearestEdgeTangentAndDist(x: Float, z: Float): Triple<Float, Float, Float> {
        var bestDist = Float.POSITIVE_INFINITY
        var bestTx = 1f
        var bestTz = 0f
        val n = boundary.size
        for (i in 0 until n) {
            val a = boundary[i]
            val b = boundary[(i + 1) % n]
            val d = TensorField.distPointToSegment(x, z, a, b)
            if (d < bestDist) {
                bestDist = d
                val dx = b.x - a.x
                val dz = b.z - a.z
                val l = hypot(dx, dz).coerceAtLeast(1e-4f)
                bestTx = dx / l
                bestTz = dz / l
            }
        }
        return Triple(bestTx, bestTz, bestDist)
    }

    /**
     * Binary-searches the exact point where segment (inside -> outside) crosses the plate boundary.
     */
    fun clipSegmentToBoundary(inside: Vec2, outside: Vec2, useCoverage: Boolean, margin: Float): Vec2 {
        var lo = 0f
        var hi = 1f
        repeat(6) {
            val mid = (lo + hi) * 0.5f
            val mx = inside.x + (outside.x - inside.x) * mid
            val mz = inside.z + (outside.z - inside.z) * mid
            val ok = if (useCoverage) containsInCoverage(mx, mz, margin) else contains(mx, mz, margin)
            if (ok) lo = mid else hi = mid
        }
        return Vec2(
            inside.x + (outside.x - inside.x) * lo,
            inside.z + (outside.z - inside.z) * lo
        )
    }

    private fun pointInPolygon(x: Float, z: Float, poly: List<Vec2>): Boolean {
        var inside = false
        val n = poly.size
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i].x; val zi = poly[i].z
            val xj = poly[j].x; val zj = poly[j].z
            val intersect = ((zi > z) != (zj > z)) &&
                (x < (xj - xi) * (z - zi) / ((zj - zi).let { if (kotlin.math.abs(it) < 1e-6f) 1e-6f else it }) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    private fun buildBasePolygon(
        shape: PlateShape,
        r: Float,
        diagRatio: Float,
        curvature: Float
    ): List<Vec2> {
        return when (shape) {
            PlateShape.SQUARE -> {
                val rx = r
                val rz = r * diagRatio
                val corners = listOf(
                    Vec2(-rx, -rz), Vec2(rx, -rz), Vec2(rx, rz), Vec2(-rx, rz)
                )
                subdivideCurvedEdges(corners, curvature, stepsPerEdge = 7)
            }
            PlateShape.RHOMBUS -> {
                // Controlled directly by its two diagonals: d1 = 2*r, d2 = 2*r*diagRatio
                val d1 = r * 1.18f
                val d2 = r * 1.18f * diagRatio
                val corners = listOf(
                    Vec2(0f, -d2), Vec2(d1, 0f), Vec2(0f, d2), Vec2(-d1, 0f)
                )
                subdivideCurvedEdges(corners, curvature, stepsPerEdge = 8)
            }
            PlateShape.HEXAGON -> {
                val corners = (0 until 6).map { i ->
                    val a = (Math.PI * 2.0 * i / 6.0).toFloat()
                    Vec2(cos(a) * r, sin(a) * r * diagRatio)
                }
                subdivideCurvedEdges(corners, curvature, stepsPerEdge = 6)
            }
            PlateShape.SEMICIRCLE -> {
                val pts = mutableListOf<Vec2>()
                val rx = r * 1.15f
                val rz = r * 1.15f * diagRatio
                val arcSteps = 26
                // Semicircular arc from angle 0 to PI
                for (i in 0..arcSteps) {
                    val a = (Math.PI * i / arcSteps).toFloat()
                    val bulge = 1.0f + curvature * 0.25f * sin(a)
                    pts.add(Vec2(cos(a) * rx * bulge, sin(a) * rz * bulge))
                }
                // Curved or flat chord closing the bottom back from (-rx, 0) to (+rx, 0)
                val chordSteps = 10
                for (i in 1 until chordSteps) {
                    val t = i.toFloat() / chordSteps.toFloat()
                    val x = -rx + 2f * rx * t
                    val bow = -curvature * rz * 0.65f * sin(t * Math.PI.toFloat())
                    pts.add(Vec2(x, bow))
                }
                pts
            }
            PlateShape.FIVE_POINT_STAR -> {
                // 5-pointed star: outer radius = r*1.15, inner valley controlled by diagRatio (0.42..0.72)
                val outerR = r * 1.18f
                val innerFactor = (0.44f + (diagRatio - 0.45f) * 0.26f).coerceIn(0.42f, 0.72f)
                val innerR = outerR * innerFactor
                val corners = (0 until 10).map { i ->
                    val a = (Math.PI * 2.0 * i / 10.0 - Math.PI * 0.5).toFloat()
                    val rad = if (i % 2 == 0) outerR else innerR
                    Vec2(cos(a) * rad, sin(a) * rad)
                }
                subdivideCurvedEdges(corners, curvature * 0.65f, stepsPerEdge = 4)
            }
        }
    }

    /**
     * Subdivides each straight polygon edge into a smooth quadratic Bezier curve bowing
     * outward (curvature > 0) or inward (curvature < 0) relative to the origin.
     */
    private fun subdivideCurvedEdges(
        corners: List<Vec2>,
        curvature: Float,
        stepsPerEdge: Int
    ): List<Vec2> {
        if (kotlin.math.abs(curvature) < 0.01f) {
            return corners
        }
        val out = mutableListOf<Vec2>()
        val n = corners.size
        for (i in 0 until n) {
            val a = corners[i]
            val b = corners[(i + 1) % n]
            val midX = (a.x + b.x) * 0.5f
            val midZ = (a.z + b.z) * 0.5f
            val edgeLen = hypot(b.x - a.x, b.z - a.z).coerceAtLeast(1e-4f)
            // Outward normal away from origin
            var nx = (b.z - a.z) / edgeLen
            var nz = -(b.x - a.x) / edgeLen
            if (nx * midX + nz * midZ < 0f) {
                nx = -nx; nz = -nz
            }
            val ctrlX = midX + nx * edgeLen * curvature * 0.55f
            val ctrlZ = midZ + nz * edgeLen * curvature * 0.55f

            for (s in 0 until stepsPerEdge) {
                val t = s.toFloat() / stepsPerEdge.toFloat()
                val u = 1f - t
                val px = u * u * a.x + 2f * u * t * ctrlX + t * t * b.x
                val pz = u * u * a.z + 2f * u * t * ctrlZ + t * t * b.z
                out.add(Vec2(px, pz))
            }
        }
        return out
    }
}
