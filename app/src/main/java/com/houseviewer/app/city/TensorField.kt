package com.houseviewer.app.city

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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

/**
 * 2D Symmetric Traceless Tensor Field for procedural urban & village road networks
 * with organic curved bypass highways, variable urban density, and junction detection.
 */
class TensorField(
    private val halfExtent: Float,
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

    init {
        val baseAngle = rng.nextFloat() * Math.PI.toFloat()
        when (style) {
            TensorFieldStyle.RADIAL_OLD_TOWN -> {
                for (center in focalCenters) {
                    fields.add(BasisField(center.x, center.z, true, 0f, halfExtent * 0.90f, 1.4f))
                }
                fields.add(BasisField(0f, 0f, false, baseAngle, halfExtent * 1.4f, 0.25f))
            }
            TensorFieldStyle.GRID_PLAZAS -> {
                fields.add(BasisField(0f, 0f, false, baseAngle, halfExtent * 1.8f, 1.3f))
                for (center in focalCenters) {
                    fields.add(BasisField(center.x, center.z, true, 0f, halfExtent * 0.42f, 0.75f))
                }
            }
            TensorFieldStyle.MIXED_ORGANIC -> {
                for (center in focalCenters) {
                    fields.add(BasisField(center.x, center.z, true, 0f, halfExtent * 0.62f, 1.15f))
                }
                val q1Angle = baseAngle
                val q2Angle = baseAngle + 0.55f + rng.nextFloat() * 0.45f
                fields.add(BasisField(-halfExtent * 0.42f, -halfExtent * 0.35f, false, q1Angle, halfExtent * 0.85f, 0.9f))
                fields.add(BasisField(halfExtent * 0.42f, halfExtent * 0.35f, false, q2Angle, halfExtent * 0.85f, 0.9f))
            }
            TensorFieldStyle.VILLAGE_FIELDS -> {
                fields.add(BasisField(denseCoreCenter.x, denseCoreCenter.z, false, baseAngle, halfExtent * 1.5f, 1.25f))
                fields.add(BasisField(halfExtent * 0.5f, -halfExtent * 0.5f, false, baseAngle + 0.24f, halfExtent * 0.9f, 0.45f))
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
     * Generates an organic, curvy highway bypass that frames roughly half of the dense
     * urban core (sweeping smoothly from one map border to another), rather than a rigid square.
     */
    fun buildCurvyBypassHighway(): RoadPolyline {
        val baseAngle = rng.nextFloat() * Math.PI.toFloat() * 2f
        val sweepSpan = (Math.PI * (0.95 + rng.nextFloat() * 0.35)).toFloat() // ~170° to ~235°
        val startAngle = baseAngle - sweepSpan * 0.5f
        val baseR = halfExtent * (0.58f + rng.nextFloat() * 0.14f)
        val waveAmp1 = halfExtent * (0.07f + rng.nextFloat() * 0.06f)
        val waveAmp2 = halfExtent * (0.04f + rng.nextFloat() * 0.04f)
        val phase1 = rng.nextFloat() * Math.PI.toFloat() * 2f
        val phase2 = rng.nextFloat() * Math.PI.toFloat() * 2f

        val pts = mutableListOf<Vec2>()
        val steps = 32
        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val ang = startAngle + sweepSpan * t
            val wobble = sin(ang * 2.0f + phase1) * waveAmp1 + cos(ang * 3.0f + phase2) * waveAmp2
            val r = (baseR + wobble).coerceIn(halfExtent * 0.42f, halfExtent * 0.86f)
            val px = (denseCoreCenter.x + cos(ang) * r).coerceIn(-halfExtent + 1.5f, halfExtent - 1.5f)
            val pz = (denseCoreCenter.z + sin(ang) * r).coerceIn(-halfExtent + 1.5f, halfExtent - 1.5f)
            pts.add(Vec2(px, pz))
        }

        // Extend both ends smoothly out to the map edge so the highway enters and exits cleanly
        val firstExt = extendToMapEdge(pts[1], pts[0], halfExtent + 1.8f)
        val lastExt = extendToMapEdge(pts[pts.lastIndex - 1], pts.last(), halfExtent + 1.8f)
        val full = ArrayList<Vec2>(pts.size + 2)
        full.add(firstExt)
        full.addAll(pts)
        full.add(lastExt)

        return RoadPolyline(smoothPolyline(full, iterations = 2), isMajor = true, isHighway = true)
    }

    private fun extendToMapEdge(prev: Vec2, end: Vec2, edgeLimit: Float): Vec2 {
        val dx = end.x - prev.x
        val dz = end.z - prev.z
        val len = hypot(dx, dz).coerceAtLeast(1e-4f)
        val ux = dx / len
        val uz = dz / len
        var tMin = Float.POSITIVE_INFINITY
        if (ux > 1e-4f) tMin = min(tMin, (edgeLimit - end.x) / ux)
        if (ux < -1e-4f) tMin = min(tMin, (-edgeLimit - end.x) / ux)
        if (uz > 1e-4f) tMin = min(tMin, (edgeLimit - end.z) / uz)
        if (uz < -1e-4f) tMin = min(tMin, (-edgeLimit - end.z) / uz)
        val step = tMin.coerceIn(1.0f, halfExtent * 1.5f)
        return Vec2(end.x + ux * step, end.z + uz * step)
    }

    /**
     * Traces internal city streets concentrated around the dense urban core.
     * Any street that intersects the curvy bypass highway terminates cleanly at the highway
     * and records a HighwayJunction for road markings (stop bar + crosswalk).
     */
    fun traceRoadNetwork(
        spacing: Float,
        highway: RoadPolyline?,
        junctionsOut: MutableList<HighwayJunction>
    ): List<RoadPolyline> {
        val roads = mutableListOf<RoadPolyline>()
        val majorPoints = mutableListOf<Vec2>()
        val minorPoints = mutableListOf<Vec2>()

        // Organic urban radius around denseCoreCenter (tapers naturally instead of square edges)
        val urbanRadius = if (style == TensorFieldStyle.VILLAGE_FIELDS) halfExtent * 0.56f else halfExtent * 0.78f
        val stepSize = (spacing * 0.22f).coerceIn(0.95f, 2.0f)
        val minSepMajor = spacing * 0.84f
        val minSepMinor = spacing * 0.80f
        val maxSteps = ((urbanRadius * 2.1f) / stepSize).toInt().coerceIn(22, 85)

        val seeds = mutableListOf<Vec2>()
        for (c in focalCenters) {
            seeds.add(Vec2(c.x + spacing * 0.55f, c.z + 0.2f))
            seeds.add(Vec2(c.x - spacing * 0.55f, c.z - 0.2f))
        }
        var gx = denseCoreCenter.x - urbanRadius
        while (gx < denseCoreCenter.x + urbanRadius) {
            var gz = denseCoreCenter.z - urbanRadius
            while (gz < denseCoreCenter.z + urbanRadius) {
                val jx = gx + (rng.nextFloat() - 0.5f) * spacing * 0.25f
                val jz = gz + (rng.nextFloat() - 0.5f) * spacing * 0.25f
                val distCore = hypot(jx - denseCoreCenter.x, jz - denseCoreCenter.z)
                if (distCore < urbanRadius * 0.85f &&
                    kotlin.math.abs(jx) < halfExtent - 2.5f &&
                    kotlin.math.abs(jz) < halfExtent - 2.5f
                ) {
                    seeds.add(Vec2(jx, jz))
                }
                gz += spacing
            }
            gx += spacing
        }

        // Prioritize seeds closer to denseCoreCenter so urban core is richer and outskirts taper off
        seeds.sortBy { hypot(it.x - denseCoreCenter.x, it.z - denseCoreCenter.z) }

        for (seed in seeds) {
            if (isTooClose(seed, majorPoints, minSepMajor)) continue
            if (highway != null && distPointToPolyline(seed, highway.points) < 2.2f) continue
            val raw = traceBidirectional(
                seed, isMajor = true, stepSize, maxSteps, urbanRadius,
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
                seed, isMajor = false, stepSize, maxSteps, urbanRadius,
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

    fun traceCountrySpur(start: Vec2, target: Vec2): RoadPolyline {
        val pts = mutableListOf<Vec2>()
        val segments = 14
        val dx = target.x - start.x
        val dz = target.z - start.z
        val len = hypot(dx, dz).coerceAtLeast(1f)
        val nx = -dz / len
        val nz = dx / len
        val bendAmp = (0.12f + rng.nextFloat() * 0.16f) * len * (if (rng.nextBoolean()) 1f else -1f)
        val waveFreq = 1.0f + rng.nextFloat() * 0.7f

        for (i in 0..segments) {
            val t = i.toFloat() / segments.toFloat()
            val bend = sin(t * Math.PI.toFloat() * waveFreq) * bendAmp * sin(t * Math.PI.toFloat())
            val px = (start.x + dx * t + nx * bend).coerceIn(-halfExtent + 1.8f, halfExtent - 1.8f)
            val pz = (start.z + dz * t + nz * bend).coerceIn(-halfExtent + 1.8f, halfExtent - 1.8f)
            pts.add(Vec2(px, pz))
        }
        return RoadPolyline(smoothPolyline(pts, iterations = 2), isMajor = false, isSpur = true)
    }

    private fun traceBidirectional(
        seed: Vec2,
        isMajor: Boolean,
        step: Float,
        maxSteps: Int,
        urbanRadius: Float,
        existingPoints: List<Vec2>,
        stopDist: Float,
        highway: RoadPolyline?,
        streetHalfW: Float,
        junctionsOut: MutableList<HighwayJunction>
    ): List<Vec2> {
        val initDir = sampleDirection(seed.x, seed.z, isMajor)
        val forward = traceOneWay(
            seed, initDir, isMajor, step, maxSteps / 2, urbanRadius,
            existingPoints, stopDist, highway, streetHalfW, junctionsOut
        )
        val backward = traceOneWay(
            seed, Vec2(-initDir.x, -initDir.z), isMajor, step, maxSteps / 2, urbanRadius,
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
        urbanRadius: Float,
        existingPoints: List<Vec2>,
        stopDist: Float,
        highway: RoadPolyline?,
        streetHalfW: Float,
        junctionsOut: MutableList<HighwayJunction>
    ): List<Vec2> {
        val pts = mutableListOf(start)
        var cur = start
        var prevDir = startDir
        val mapLimit = halfExtent - 2.2f

        for (i in 0 until maxSteps) {
            val next = rk4Step(cur, prevDir, isMajor, step)

            // Check intersection with curvy bypass highway first!
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

            // Stop smoothly inside organic urban boundary so streets never run off to map edges
            val distCore = hypot(next.x - denseCoreCenter.x, next.z - denseCoreCenter.z)
            if (distCore > urbanRadius ||
                next.x < -mapLimit || next.x > mapLimit ||
                next.z < -mapLimit || next.z > mapLimit
            ) {
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
