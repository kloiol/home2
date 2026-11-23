package com.houseviewer.app.city

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

data class Vec2(val x: Float, val z: Float)

data class RoadPolyline(
    val points: List<Vec2>,
    val isMajor: Boolean,
    val isSpur: Boolean = false,
    val isHighway: Boolean = false
)

data class HighwayJunction(
    val x: Float,
    val z: Float,
    val approachDirX: Float,
    val approachDirZ: Float,
    val streetHalfW: Float
)

data class ManhattanBlock(
    val uMin: Float,
    val uMax: Float,
    val vMin: Float,
    val vMax: Float,
    val angleRad: Float
)

/**
 * 2D Symmetric Traceless Tensor Field that bends smoothly to conform to any
 * parametric CityPlate shape (Square, Hexagon, Semicircle, 5-Pointed Star, Rhombus)
 * and respects the user's surface-area coverage setting.
 */
class TensorField(
    val plate: CityPlate,
    private val style: TensorFieldStyle,
    val focalCenters: List<Vec2>,
    val denseCoreCenter: Vec2,
    private val rng: Random
) {
    private data class BasisField(
        val cx: Float,
        val cz: Float,
        val isRadial: Boolean,
        val gridAngle: Float,
        val decayRadius: Float,
        val weight: Float
    )

    private val fields = mutableListOf<BasisField>()
    private val refRadius = plate.maxRadius * plate.coverageLinearScale
    var manhattanAngle: Float = 0f
        private set
    val manhattanBlocks: MutableList<ManhattanBlock> = mutableListOf()

    init {
        val baseAngle = rng.nextFloat() * Math.PI.toFloat()
        when (style) {
            TensorFieldStyle.RADIAL_OLD_TOWN -> {
                for (center in focalCenters) {
                    fields.add(BasisField(center.x, center.z, true, 0f, refRadius * 0.90f, 1.4f))
                }
                fields.add(BasisField(0f, 0f, false, baseAngle, refRadius * 1.4f, 0.25f))
            }
            TensorFieldStyle.GRID_PLAZAS -> {
                fields.add(BasisField(0f, 0f, false, baseAngle, refRadius * 1.8f, 1.3f))
                for (center in focalCenters) {
                    fields.add(BasisField(center.x, center.z, true, 0f, refRadius * 0.42f, 0.75f))
                }
            }
            TensorFieldStyle.MIXED_ORGANIC -> {
                for (center in focalCenters) {
                    fields.add(BasisField(center.x, center.z, true, 0f, refRadius * 0.62f, 1.15f))
                }
                val q1Angle = baseAngle
                val q2Angle = baseAngle + 0.55f + rng.nextFloat() * 0.45f
                fields.add(BasisField(-refRadius * 0.42f, -refRadius * 0.35f, false, q1Angle, refRadius * 0.85f, 0.9f))
                fields.add(BasisField(refRadius * 0.42f, refRadius * 0.35f, false, q2Angle, refRadius * 0.85f, 0.9f))
            }
            TensorFieldStyle.VILLAGE_FIELDS -> {
                fields.add(BasisField(denseCoreCenter.x, denseCoreCenter.z, false, baseAngle, refRadius * 1.5f, 1.25f))
                fields.add(BasisField(refRadius * 0.5f, -refRadius * 0.5f, false, baseAngle + 0.24f, refRadius * 0.9f, 0.45f))
            }
            TensorFieldStyle.SOVIET_MICRORAYON -> {
                val gridAngle = (rng.nextInt(4) * (Math.PI * 0.25)).toFloat() + (rng.nextFloat() - 0.5f) * 0.10f
                fields.add(BasisField(0f, 0f, false, gridAngle, refRadius * 2.4f, 1.8f))
                if (rng.nextFloat() < 0.14f) {
                    val rx = (rng.nextFloat() - 0.5f) * refRadius * 0.50f
                    val rz = (rng.nextFloat() - 0.5f) * refRadius * 0.50f
                    fields.add(BasisField(rx, rz, true, 0f, refRadius * 0.26f, 0.70f))
                }
            }
            TensorFieldStyle.NYC_MANHATTAN -> {
                manhattanAngle = (rng.nextInt(4) * (Math.PI * 0.5)).toFloat() + (rng.nextFloat() - 0.5f) * 0.24f
                fields.add(BasisField(0f, 0f, false, manhattanAngle, refRadius * 3.0f, 2.0f))
            }
        }
    }

    fun sampleDirection(x: Float, z: Float, isMajor: Boolean): Vec2 {
        var sumA = 0.0
        var sumB = 0.0

        for (f in fields) {
            val dx = (x - f.cx).toDouble()
            val dz = (z - f.cz).toDouble()
            val distSq = dx * dx + dz * dz
            val sigmaSq = (f.decayRadius * f.decayRadius).toDouble().coerceAtLeast(1.0)
            val w = f.weight * exp(-distSq / (2.0 * sigmaSq))

            if (f.isRadial) {
                if (distSq > 1e-4) {
                    val inv = 1.0 / distSq
                    val a = (dx * dx - dz * dz) * inv
                    val b = (2.0 * dx * dz) * inv
                    sumA += w * a
                    sumB += w * b
                }
            } else {
                val twoTheta = 2.0 * f.gridAngle.toDouble()
                sumA += w * cos(twoTheta)
                sumB += w * sin(twoTheta)
            }
        }

        // Gently bend tensor field along the plate's outer contour when near the shape border
        if (style != TensorFieldStyle.SOVIET_MICRORAYON && style != TensorFieldStyle.NYC_MANHATTAN) {
            val (edgeTx, edgeTz, edgeDist) = plate.nearestEdgeTangentAndDist(x, z)
            val influenceBand = (plate.maxRadius * 0.28f).coerceIn(4.0f, 12.0f)
            if (edgeDist < influenceBand) {
                val edgeW = ((1.0f - edgeDist / influenceBand) * 0.85f).toDouble()
                val edgeAngle = atan2(edgeTz.toDouble(), edgeTx.toDouble())
                sumA += edgeW * cos(2.0 * edgeAngle)
                sumB += edgeW * sin(2.0 * edgeAngle)
            }
        }

        if (sumA * sumA + sumB * sumB < 1e-8) {
            return if (isMajor) Vec2(1f, 0f) else Vec2(0f, 1f)
        }

        var angle = 0.5 * atan2(sumB, sumA)
        if (!isMajor) {
            angle += Math.PI * 0.5
        }
        return Vec2(cos(angle).toFloat(), sin(angle).toFloat())
    }

    /**
     * Builds a curvy bypass highway that conforms smoothly to roughly half of the plate's
     * contour (scaled to the active city coverage zone) so it bends naturally around
     * hexagons, semicircles, rhombuses, and 5-pointed stars without ever leaving the plate!
     */
    fun buildCurvyBypassHighway(): RoadPolyline? {
        val poly = plate.boundary
        if (poly.size < 4) return null

        // Walk along ~48% to ~62% of the plate perimeter inset inside the coverage zone
        val insetScale = (plate.coverageLinearScale * 0.86f).coerceIn(0.32f, 0.90f)
        val n = poly.size
        val startIdx = rng.nextInt(n)
        val spanCount = ((n * (0.46f + rng.nextFloat() * 0.16f)).toInt()).coerceIn(3, n - 1)

        val raw = mutableListOf<Vec2>()
        for (k in 0..spanCount) {
            val p = poly[(startIdx + k) % n]
            val wx = p.x * insetScale
            val wz = p.z * insetScale
            if (plate.contains(wx, wz, margin = 1.6f)) {
                raw.add(Vec2(wx, wz))
            }
        }
        if (raw.size < 3) return null
        val smooth = smoothPolyline(raw, iterations = 2)
        return RoadPolyline(smooth, isMajor = true, isHighway = true)
    }

    fun traceRoadNetwork(
        spacing: Float,
        highway: RoadPolyline?,
        junctionsOut: MutableList<HighwayJunction>
    ): List<RoadPolyline> {
        if (style == TensorFieldStyle.NYC_MANHATTAN) {
            return buildManhattanRoadNetwork(spacing)
        }
        val roads = mutableListOf<RoadPolyline>()
        val majorPoints = mutableListOf<Vec2>()
        val minorPoints = mutableListOf<Vec2>()

        val effectiveSpacing = if (style == TensorFieldStyle.SOVIET_MICRORAYON) {
            (spacing * 1.28f).coerceIn(8.5f, 16.0f)
        } else {
            spacing
        }
        val stepSize = (effectiveSpacing * 0.22f).coerceIn(0.95f, 2.2f)
        val minSepMajor = effectiveSpacing * 0.84f
        val minSepMinor = effectiveSpacing * 0.80f
        val maxSteps = ((refRadius * 2.4f) / stepSize).toInt().coerceIn(22, 90)
        val roadMargin = 2.0f

        val seeds = mutableListOf<Vec2>()
        if (style != TensorFieldStyle.SOVIET_MICRORAYON) {
            for (c in focalCenters) {
                val p1 = Vec2(c.x + effectiveSpacing * 0.55f, c.z + 0.2f)
                val p2 = Vec2(c.x - effectiveSpacing * 0.55f, c.z - 0.2f)
                if (plate.containsInCoverage(p1.x, p1.z, roadMargin)) seeds.add(p1)
                if (plate.containsInCoverage(p2.x, p2.z, roadMargin)) seeds.add(p2)
            }
        }

        var gx = plate.minX + effectiveSpacing * 0.5f
        while (gx < plate.maxX - effectiveSpacing * 0.5f) {
            var gz = plate.minZ + effectiveSpacing * 0.5f
            while (gz < plate.maxZ - effectiveSpacing * 0.5f) {
                val jitter = if (style == TensorFieldStyle.SOVIET_MICRORAYON) 0.08f else 0.25f
                val jx = gx + (rng.nextFloat() - 0.5f) * effectiveSpacing * jitter
                val jz = gz + (rng.nextFloat() - 0.5f) * effectiveSpacing * jitter
                if (plate.containsInCoverage(jx, jz, roadMargin)) {
                    seeds.add(Vec2(jx, jz))
                }
                gz += effectiveSpacing
            }
            gx += effectiveSpacing
        }

        seeds.sortBy { hypot(it.x - denseCoreCenter.x, it.z - denseCoreCenter.z) }

        for (seed in seeds) {
            if (isTooClose(seed, majorPoints, minSepMajor)) continue
            if (highway != null && distPointToPolyline(seed, highway.points) < 2.2f) continue
            val raw = traceBidirectional(
                seed, isMajor = true, stepSize, maxSteps, roadMargin,
                majorPoints, minSepMajor * 0.80f, highway, 0.72f, junctionsOut
            )
            if (raw.size >= 4) {
                val smooth = smoothPolyline(raw, iterations = 2)
                roads.add(RoadPolyline(smooth, isMajor = true))
                majorPoints.addAll(smooth)
            }
        }

        for (seed in seeds) {
            if (isTooClose(seed, minorPoints, minSepMinor)) continue
            if (highway != null && distPointToPolyline(seed, highway.points) < 2.2f) continue
            val raw = traceBidirectional(
                seed, isMajor = false, stepSize, maxSteps, roadMargin,
                minorPoints, minSepMinor * 0.80f, highway, 0.50f, junctionsOut
            )
            if (raw.size >= 4) {
                val smooth = smoothPolyline(raw, iterations = 2)
                roads.add(RoadPolyline(smooth, isMajor = false))
                minorPoints.addAll(smooth)
            }
        }

        return roads
    }


    /**
     * Generates an authentic Manhattan Commissioners' Plan orthogonal grid of broad
     * parallel Avenues (major) and tighter perpendicular Cross-Streets (minor), clipped
     * cleanly to any parametric CityPlate shape, and records every rectangular district block.
     */
    private fun buildManhattanRoadNetwork(spacing: Float): List<RoadPolyline> {
        val roads = mutableListOf<RoadPolyline>()
        manhattanBlocks.clear()

        val aveStep = (spacing * 1.18f).coerceIn(6.8f, 12.2f)
        val stStep = (spacing * 0.72f).coerceIn(4.8f, 8.2f)
        val ux = cos(manhattanAngle)
        val uz = sin(manhattanAngle)
        val vx = -uz
        val vz = ux
        val maxSpan = plate.maxRadius * plate.coverageLinearScale + aveStep

        val uCoords = mutableListOf<Float>()
        val vCoords = mutableListOf<Float>()
        val nU = (maxSpan / aveStep).toInt().coerceIn(2, 16)
        val nV = (maxSpan / stStep).toInt().coerceIn(3, 22)
        for (i in -nU..nU) uCoords.add(i * aveStep)
        for (j in -nV..nV) vCoords.add(j * stStep)

        val roadMargin = 1.35f
        val sampleStep = 1.15f

        // 1. Parallel broad Avenues (running along V, spaced along U)
        for (u in uCoords) {
            var currentSeg = mutableListOf<Vec2>()
            var v = -maxSpan
            while (v <= maxSpan + 0.01f) {
                val wx = u * ux + v * vx
                val wz = u * uz + v * vz
                if (plate.containsInCoverage(wx, wz, roadMargin)) {
                    currentSeg.add(Vec2(wx, wz))
                } else if (currentSeg.size >= 2) {
                    roads.add(RoadPolyline(currentSeg, isMajor = true))
                    currentSeg = mutableListOf()
                } else {
                    currentSeg.clear()
                }
                v += sampleStep
            }
            if (currentSeg.size >= 2) {
                roads.add(RoadPolyline(currentSeg, isMajor = true))
            }
        }

        // 2. Parallel tighter Cross-Streets (running along U, spaced along V)
        for (v in vCoords) {
            var currentSeg = mutableListOf<Vec2>()
            var u = -maxSpan
            while (u <= maxSpan + 0.01f) {
                val wx = u * ux + v * vx
                val wz = u * uz + v * vz
                if (plate.containsInCoverage(wx, wz, roadMargin)) {
                    currentSeg.add(Vec2(wx, wz))
                } else if (currentSeg.size >= 2) {
                    roads.add(RoadPolyline(currentSeg, isMajor = false))
                    currentSeg = mutableListOf()
                } else {
                    currentSeg.clear()
                }
                u += sampleStep
            }
            if (currentSeg.size >= 2) {
                roads.add(RoadPolyline(currentSeg, isMajor = false))
            }
        }

        // 3. Record every rectangular Manhattan district formed between adjacent avenues & streets
        for (i in 0 until uCoords.size - 1) {
            val u0 = uCoords[i]
            val u1 = uCoords[i + 1]
            val uc = (u0 + u1) * 0.5f
            for (j in 0 until vCoords.size - 1) {
                val v0 = vCoords[j]
                val v1 = vCoords[j + 1]
                val vc = (v0 + v1) * 0.5f
                val cx = uc * ux + vc * vx
                val cz = uc * uz + vc * vz
                // Keep block if center or any inner quadrant lies inside the plate coverage zone
                var inside = plate.containsInCoverage(cx, cz, 0.8f)
                if (!inside) {
                    val testPts = arrayOf(
                        Pair(u0 + 1.0f, v0 + 1.0f), Pair(u1 - 1.0f, v0 + 1.0f),
                        Pair(u0 + 1.0f, v1 - 1.0f), Pair(u1 - 1.0f, v1 - 1.0f)
                    )
                    for ((tu, tv) in testPts) {
                        val tx = tu * ux + tv * vx
                        val tz = tu * uz + tv * vz
                        if (plate.containsInCoverage(tx, tz, 0.8f)) {
                            inside = true
                            break
                        }
                    }
                }
                if (inside) {
                    manhattanBlocks.add(ManhattanBlock(u0, u1, v0, v1, manhattanAngle))
                }
            }
        }

        return roads
    }

    fun traceCountrySpur(start: Vec2, target: Vec2): RoadPolyline {
        val pts = mutableListOf<Vec2>()
        val segments = 14
        val dx = target.x - start.x
        val dz = target.z - start.z
        val len = hypot(dx, dz).coerceAtLeast(1f)
        val nx = -dz / len
        val nz = dx / len
        val bendAmp = (0.10f + rng.nextFloat() * 0.14f) * len * (if (rng.nextBoolean()) 1f else -1f)
        val waveFreq = 1.0f + rng.nextFloat() * 0.6f

        for (i in 0..segments) {
            val t = i.toFloat() / segments.toFloat()
            val bend = sin(t * Math.PI.toFloat() * waveFreq) * bendAmp * sin(t * Math.PI.toFloat())
            val px = start.x + dx * t + nx * bend
            val pz = start.z + dz * t + nz * bend
            if (plate.contains(px, pz, margin = 1.5f)) {
                pts.add(Vec2(px, pz))
            } else {
                pts.add(Vec2(start.x + dx * t, start.z + dz * t))
            }
        }
        return RoadPolyline(smoothPolyline(pts, iterations = 2), isMajor = false, isSpur = true)
    }

    private fun traceBidirectional(
        seed: Vec2,
        isMajor: Boolean,
        step: Float,
        maxSteps: Int,
        margin: Float,
        existingPoints: List<Vec2>,
        stopDist: Float,
        highway: RoadPolyline?,
        streetHalfW: Float,
        junctionsOut: MutableList<HighwayJunction>
    ): List<Vec2> {
        val initDir = sampleDirection(seed.x, seed.z, isMajor)
        val forward = traceOneWay(
            seed, initDir, isMajor, step, maxSteps / 2, margin,
            existingPoints, stopDist, highway, streetHalfW, junctionsOut
        )
        val backward = traceOneWay(
            seed, Vec2(-initDir.x, -initDir.z), isMajor, step, maxSteps / 2, margin,
            existingPoints, stopDist, highway, streetHalfW, junctionsOut
        )
        val combined = ArrayList<Vec2>(backward.size + forward.size)
        for (i in backward.lastIndex downTo 1) {
            combined.add(backward[i])
        }
        combined.addAll(forward)
        return combined
    }

    private fun traceOneWay(
        start: Vec2,
        startDir: Vec2,
        isMajor: Boolean,
        step: Float,
        maxSteps: Int,
        margin: Float,
        existingPoints: List<Vec2>,
        stopDist: Float,
        highway: RoadPolyline?,
        streetHalfW: Float,
        junctionsOut: MutableList<HighwayJunction>
    ): List<Vec2> {
        val pts = mutableListOf(start)
        var cur = start
        var prevDir = startDir

        for (i in 0 until maxSteps) {
            val next = rk4Step(cur, prevDir, isMajor, step)

            if (highway != null) {
                val hit = findPolylineIntersection(cur, next, highway.points)
                if (hit != null) {
                    pts.add(hit)
                    val dx = hit.x - cur.x
                    val dz = hit.z - cur.z
                    val l = hypot(dx, dz).coerceAtLeast(1e-4f)
                    junctionsOut.add(HighwayJunction(hit.x, hit.z, dx / l, dz / l, streetHalfW))
                    break
                }
            }

            if (!plate.containsInCoverage(next.x, next.z, margin)) {
                val snapped = plate.clipSegmentToBoundary(cur, next, useCoverage = true, margin = margin)
                pts.add(snapped)
                break
            }

            val moveX = next.x - cur.x
            val moveZ = next.z - cur.z
            val moveLen = hypot(moveX, moveZ)
            if (moveLen < 1e-3f) break
            val newDir = Vec2(moveX / moveLen, moveZ / moveLen)

            if (i > 2 && isTooClose(next, existingPoints, stopDist)) {
                pts.add(next)
                break
            }
            if (i > 4 && isTooClose(next, pts.subList(0, pts.size - 3), stopDist * 0.85f)) {
                break
            }

            pts.add(next)
            cur = next
            prevDir = newDir
        }
        return pts
    }

    private fun findPolylineIntersection(a: Vec2, b: Vec2, poly: List<Vec2>): Vec2? {
        for (i in 0 until poly.size - 1) {
            val hit = segmentIntersect(a, b, poly[i], poly[i + 1])
            if (hit != null) return hit
        }
        return null
    }

    private fun segmentIntersect(p1: Vec2, p2: Vec2, p3: Vec2, p4: Vec2): Vec2? {
        val d1x = p2.x - p1.x; val d1z = p2.z - p1.z
        val d2x = p4.x - p3.x; val d2z = p4.z - p3.z
        val denom = d1x * d2z - d1z * d2x
        if (kotlin.math.abs(denom) < 1e-6f) return null
        val t = ((p3.x - p1.x) * d2z - (p3.z - p1.z) * d2x) / denom
        val u = ((p3.x - p1.x) * d1z - (p3.z - p1.z) * d1x) / denom
        if (t in 0f..1f && u in 0f..1f) {
            return Vec2(p1.x + t * d1x, p1.z + t * d1z)
        }
        return null
    }

    private fun distPointToPolyline(p: Vec2, poly: List<Vec2>): Float {
        var best = Float.POSITIVE_INFINITY
        for (i in 0 until poly.size - 1) {
            val d = distPointToSegment(p.x, p.z, poly[i], poly[i + 1])
            if (d < best) best = d
        }
        return best
    }

    private fun smoothPolyline(points: List<Vec2>, iterations: Int): List<Vec2> {
        if (points.size < 3) return points
        var cur = points
        repeat(iterations) {
            val next = ArrayList<Vec2>(cur.size)
            next.add(cur.first())
            for (i in 1 until cur.size - 1) {
                val p0 = cur[i - 1]
                val p1 = cur[i]
                val p2 = cur[i + 1]
                next.add(
                    Vec2(
                        p0.x * 0.22f + p1.x * 0.56f + p2.x * 0.22f,
                        p0.z * 0.22f + p1.z * 0.56f + p2.z * 0.22f
                    )
                )
            }
            next.add(cur.last())
            cur = next
        }
        return cur
    }

    private fun rk4Step(p: Vec2, prevDir: Vec2, isMajor: Boolean, h: Float): Vec2 {
        val k1 = alignedDir(p.x, p.z, prevDir, isMajor)
        val k2 = alignedDir(p.x + k1.x * h * 0.5f, p.z + k1.z * h * 0.5f, k1, isMajor)
        val k3 = alignedDir(p.x + k2.x * h * 0.5f, p.z + k2.z * h * 0.5f, k2, isMajor)
        val k4 = alignedDir(p.x + k3.x * h, p.z + k3.z * h, k3, isMajor)

        val dx = (k1.x + 2f * k2.x + 2f * k3.x + k4.x) / 6f
        val dz = (k1.z + 2f * k2.z + 2f * k3.z + k4.z) / 6f
        return Vec2(p.x + dx * h, p.z + dz * h)
    }

    private fun alignedDir(x: Float, z: Float, ref: Vec2, isMajor: Boolean): Vec2 {
        val d = sampleDirection(x, z, isMajor)
        return if (d.x * ref.x + d.z * ref.z < 0f) Vec2(-d.x, -d.z) else d
    }

    private fun isTooClose(p: Vec2, points: List<Vec2>, threshold: Float): Boolean {
        val tSq = threshold * threshold
        for (q in points) {
            val dx = p.x - q.x
            val dz = p.z - q.z
            if (dx * dx + dz * dz < tSq) return true
        }
        return false
    }

    companion object {
        fun distPointToSegment(px: Float, pz: Float, a: Vec2, b: Vec2): Float {
            val abx = b.x - a.x
            val abz = b.z - a.z
            val lenSq = abx * abx + abz * abz
            if (lenSq < 1e-6f) return hypot(px - a.x, pz - a.z)
            val t = (((px - a.x) * abx + (pz - a.z) * abz) / lenSq).coerceIn(0f, 1f)
            val cx = a.x + t * abx
            val cz = a.z + t * abz
            return hypot(px - cx, pz - cz)
        }
    }
}
