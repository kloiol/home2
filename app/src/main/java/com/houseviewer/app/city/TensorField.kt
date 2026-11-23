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

/**
 * 2D Symmetric Traceless Tensor Field for procedural urban & village road networks
 * with RK4 streamline integration, polyline smoothing, and beltway/boundary clipping.
 */
class TensorField(
    private val halfExtent: Float,
    private val style: TensorFieldStyle,
    val focalCenters: List<Vec2>,
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
                    fields.add(BasisField(center.x, center.z, true, 0f, halfExtent * 0.95f, 1.4f))
                }
                fields.add(BasisField(0f, 0f, false, baseAngle, halfExtent * 1.4f, 0.25f))
            }
            TensorFieldStyle.GRID_PLAZAS -> {
                fields.add(BasisField(0f, 0f, false, baseAngle, halfExtent * 1.8f, 1.3f))
                for (center in focalCenters) {
                    fields.add(BasisField(center.x, center.z, true, 0f, halfExtent * 0.45f, 0.75f))
                }
            }
            TensorFieldStyle.MIXED_ORGANIC -> {
                for (center in focalCenters) {
                    fields.add(BasisField(center.x, center.z, true, 0f, halfExtent * 0.65f, 1.15f))
                }
                val q1Angle = baseAngle
                val q2Angle = baseAngle + 0.55f + rng.nextFloat() * 0.45f
                fields.add(BasisField(-halfExtent * 0.45f, -halfExtent * 0.35f, false, q1Angle, halfExtent * 0.85f, 0.9f))
                fields.add(BasisField(halfExtent * 0.45f, halfExtent * 0.35f, false, q2Angle, halfExtent * 0.85f, 0.9f))
            }
            TensorFieldStyle.VILLAGE_FIELDS -> {
                fields.add(BasisField(0f, 0f, false, baseAngle, halfExtent * 1.6f, 1.25f))
                fields.add(BasisField(halfExtent * 0.6f, -halfExtent * 0.5f, false, baseAngle + 0.22f, halfExtent * 0.9f, 0.45f))
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
     * Checks whether (x, z) is strictly inside the city boundary (rounded-square beltway
     * for cities, or compact village radius for Rural Village).
     */
    fun isInsideBeltway(x: Float, z: Float, beltwayRadius: Float): Boolean {
        val cornerR = beltwayRadius * 0.34f
        val inner = beltwayRadius - cornerR
        val ax = kotlin.math.abs(x)
        val az = kotlin.math.abs(z)
        if (ax <= inner && az <= beltwayRadius) return true
        if (az <= inner && ax <= beltwayRadius) return true
        if (ax > beltwayRadius || az > beltwayRadius) return false
        val dx = ax - inner
        val dz = az - inner
        return dx * dx + dz * dz <= cornerR * cornerR
    }

    /**
     * Generates a closed smooth rounded-square beltway highway loop bordering the city,
     * plus 2-3 clean regional highway entry connectors to the map edge.
     */
    fun buildBeltwayHighway(beltwayRadius: Float): List<RoadPolyline> {
        val result = mutableListOf<RoadPolyline>()
        val cornerR = beltwayRadius * 0.34f
        val inner = beltwayRadius - cornerR
        val loop = mutableListOf<Vec2>()

        // 4 rounded corners + straight sides (closed loop)
        val arcSteps = 7
        // Top-right corner (+inner, +inner), angles 0..PI/2
        for (i in 0..arcSteps) {
            val a = (Math.PI * 0.5 * i / arcSteps).toFloat()
            loop.add(Vec2(inner + cos(a) * cornerR, inner + sin(a) * cornerR))
        }
        // Top-left corner (-inner, +inner), angles PI/2..PI
        for (i in 0..arcSteps) {
            val a = (Math.PI * 0.5 + Math.PI * 0.5 * i / arcSteps).toFloat()
            loop.add(Vec2(-inner + cos(a) * cornerR, inner + sin(a) * cornerR))
        }
        // Bottom-left corner (-inner, -inner), angles PI..3PI/2
        for (i in 0..arcSteps) {
            val a = (Math.PI + Math.PI * 0.5 * i / arcSteps).toFloat()
            loop.add(Vec2(-inner + cos(a) * cornerR, -inner + sin(a) * cornerR))
        }
        // Bottom-right corner (+inner, -inner), angles 3PI/2..2PI
        for (i in 0..arcSteps) {
            val a = (Math.PI * 1.5 + Math.PI * 0.5 * i / arcSteps).toFloat()
            loop.add(Vec2(inner + cos(a) * cornerR, -inner + sin(a) * cornerR))
        }
        // Close loop seamlessly
        loop.add(loop.first())
        result.add(RoadPolyline(loop, isMajor = true, isHighway = true))

        // Add 2-3 clean highway exits to the outer map border so the ring connects outward
        val edge = halfExtent + 1.8f
        val offsetA = (rng.nextFloat() - 0.5f) * inner * 0.8f
        val offsetB = (rng.nextFloat() - 0.5f) * inner * 0.8f
        val exits = listOf(
            listOf(Vec2(offsetA, beltwayRadius), Vec2(offsetA, edge)),
            listOf(Vec2(offsetB, -beltwayRadius), Vec2(offsetB, -edge)),
            listOf(Vec2(beltwayRadius, offsetA * 0.6f), Vec2(edge, offsetA * 0.6f))
        )
        val exitCount = 2 + rng.nextInt(2)
        for (i in 0 until exitCount) {
            result.add(RoadPolyline(exits[i], isMajor = true, isHighway = true))
        }
        return result
    }

    fun traceRoadNetwork(spacing: Float, beltwayRadius: Float): List<RoadPolyline> {
        val roads = mutableListOf<RoadPolyline>()
        val majorPoints = mutableListOf<Vec2>()
        val minorPoints = mutableListOf<Vec2>()

        val traceRadius = if (style == TensorFieldStyle.VILLAGE_FIELDS) halfExtent * 0.58f else beltwayRadius
        val stepSize = (spacing * 0.22f).coerceIn(0.95f, 2.0f)
        val minSepMajor = spacing * 0.82f
        val minSepMinor = spacing * 0.78f
        val maxSteps = ((traceRadius * 2.2f) / stepSize).toInt().coerceIn(24, 100)

        val seeds = mutableListOf<Vec2>()
        for (c in focalCenters) {
            seeds.add(Vec2(c.x + spacing * 0.55f, c.z + 0.2f))
            seeds.add(Vec2(c.x - spacing * 0.55f, c.z - 0.2f))
        }
        var gx = -traceRadius + spacing * 0.65f
        while (gx < traceRadius - spacing * 0.65f) {
            var gz = -traceRadius + spacing * 0.65f
            while (gz < traceRadius - spacing * 0.65f) {
                val jx = gx + (rng.nextFloat() - 0.5f) * spacing * 0.20f
                val jz = gz + (rng.nextFloat() - 0.5f) * spacing * 0.20f
                if (isInsideBeltway(jx, jz, traceRadius * 0.92f)) {
                    seeds.add(Vec2(jx, jz))
                }
                gz += spacing
            }
            gx += spacing
        }

        for (seed in seeds) {
            if (isTooClose(seed, majorPoints, minSepMajor)) continue
            val raw = traceBidirectional(seed, isMajor = true, stepSize, maxSteps, traceRadius, majorPoints, minSepMajor * 0.80f)
            if (raw.size >= 4) {
                val smooth = smoothPolyline(raw, iterations = 2)
                roads.add(RoadPolyline(smooth, isMajor = true))
                majorPoints.addAll(smooth)
            }
        }

        for (seed in seeds) {
            if (isTooClose(seed, minorPoints, minSepMinor)) continue
            val raw = traceBidirectional(seed, isMajor = false, stepSize, maxSteps, traceRadius, minorPoints, minSepMinor * 0.80f)
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
        beltwayRadius: Float,
        existingPoints: List<Vec2>,
        stopDist: Float
    ): List<Vec2> {
        val initDir = sampleDirection(seed.x, seed.z, isMajor)
        val forward = traceOneWay(seed, initDir, isMajor, step, maxSteps / 2, beltwayRadius, existingPoints, stopDist)
        val backward = traceOneWay(seed, Vec2(-initDir.x, -initDir.z), isMajor, step, maxSteps / 2, beltwayRadius, existingPoints, stopDist)
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
        beltwayRadius: Float,
        existingPoints: List<Vec2>,
        stopDist: Float
    ): List<Vec2> {
        val pts = mutableListOf(start)
        var cur = start
        var prevDir = startDir

        for (i in 0 until maxSteps) {
            val next = rk4Step(cur, prevDir, isMajor, step)
            if (!isInsideBeltway(next.x, next.z, beltwayRadius)) {
                // Snap final point cleanly onto the beltway border so streets meet the highway with zero overhang
                val snapped = snapToBeltway(cur, next, beltwayRadius)
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

    private fun snapToBeltway(inside: Vec2, outside: Vec2, beltwayRadius: Float): Vec2 {
        var lo = 0f
        var hi = 1f
        repeat(6) {
            val mid = (lo + hi) * 0.5f
            val mx = inside.x + (outside.x - inside.x) * mid
            val mz = inside.z + (outside.z - inside.z) * mid
            if (isInsideBeltway(mx, mz, beltwayRadius)) lo = mid else hi = mid
        }
        return Vec2(
            inside.x + (outside.x - inside.x) * lo,
            inside.z + (outside.z - inside.z) * lo
        )
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
}
