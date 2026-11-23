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
    val isMajor: Boolean
)

/**
 * 2D Symmetric Traceless Tensor Field for procedural urban road networks
 * (inspired by Chen et al. 2008 "Interactive Procedural Street Modeling").
 *
 * A 2D symmetric traceless tensor is represented by (a, b):
 *   T = [ a   b ]
 *       [ b  -a ]
 * Major eigenvector angle = 0.5 * atan2(b, a)
 * Minor eigenvector angle = major + PI/2
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
                // Add 2 angled grid districts for realistic European quarter transitions
                val q1Angle = baseAngle
                val q2Angle = baseAngle + 0.55f + rng.nextFloat() * 0.45f
                fields.add(BasisField(-halfExtent * 0.45f, -halfExtent * 0.35f, false, q1Angle, halfExtent * 0.85f, 0.9f))
                fields.add(BasisField(halfExtent * 0.45f, halfExtent * 0.35f, false, q2Angle, halfExtent * 0.85f, 0.9f))
            }
        }
    }

    /**
     * Evaluates the blended tensor at (x, z) and returns the unit eigenvector
     * (major if isMajor == true, minor otherwise).
     */
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
                    // Radial tensor: major eigenvector points radially outward, minor forms concentric rings
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
     * Traces a network of major and minor road streamlines using RK4 integration.
     */
    fun traceRoadNetwork(spacing: Float): List<RoadPolyline> {
        val roads = mutableListOf<RoadPolyline>()
        val majorPoints = mutableListOf<Vec2>()
        val minorPoints = mutableListOf<Vec2>()

        val stepSize = (spacing * 0.30f).coerceIn(1.2f, 3.0f)
        val minSepMajor = spacing * 0.72f
        val minSepMinor = spacing * 0.68f
        val maxSteps = ((halfExtent * 2.2f) / stepSize).toInt().coerceIn(24, 90)

        // 1. Seed major streamlines from focal plazas + regular candidate grid
        val seeds = mutableListOf<Vec2>()
        for (c in focalCenters) {
            seeds.add(Vec2(c.x + spacing * 0.55f, c.z + 0.3f))
            seeds.add(Vec2(c.x - spacing * 0.55f, c.z - 0.3f))
        }
        var gx = -halfExtent + spacing * 0.6f
        while (gx < halfExtent - spacing * 0.5f) {
            var gz = -halfExtent + spacing * 0.6f
            while (gz < halfExtent - spacing * 0.5f) {
                val jx = gx + (rng.nextFloat() - 0.5f) * spacing * 0.25f
                val jz = gz + (rng.nextFloat() - 0.5f) * spacing * 0.25f
                seeds.add(Vec2(jx, jz))
                gz += spacing
            }
            gx += spacing
        }

        // Trace major roads first
        for (seed in seeds) {
            if (isTooClose(seed, majorPoints, minSepMajor)) continue
            val poly = traceBidirectional(seed, isMajor = true, stepSize, maxSteps, majorPoints, minSepMajor * 0.75f)
            if (poly.size >= 4) {
                roads.add(RoadPolyline(poly, isMajor = true))
                majorPoints.addAll(poly)
            }
        }

        // Trace minor cross-streets
        for (seed in seeds) {
            if (isTooClose(seed, minorPoints, minSepMinor)) continue
            val poly = traceBidirectional(seed, isMajor = false, stepSize, maxSteps, minorPoints, minSepMinor * 0.75f)
            if (poly.size >= 4) {
                roads.add(RoadPolyline(poly, isMajor = false))
                minorPoints.addAll(poly)
            }
        }

        return roads
    }

    private fun traceBidirectional(
        seed: Vec2,
        isMajor: Boolean,
        step: Float,
        maxSteps: Int,
        existingPoints: List<Vec2>,
        stopDist: Float
    ): List<Vec2> {
        val initDir = sampleDirection(seed.x, seed.z, isMajor)
        val forward = traceOneWay(seed, initDir, isMajor, step, maxSteps / 2, existingPoints, stopDist)
        val backward = traceOneWay(seed, Vec2(-initDir.x, -initDir.z), isMajor, step, maxSteps / 2, existingPoints, stopDist)
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
        existingPoints: List<Vec2>,
        stopDist: Float
    ): List<Vec2> {
        val pts = mutableListOf(start)
        var cur = start
        var prevDir = startDir

        for (i in 0 until maxSteps) {
            val next = rk4Step(cur, prevDir, isMajor, step)
            if (next.x < -halfExtent || next.x > halfExtent || next.z < -halfExtent || next.z > halfExtent) {
                break
            }
            val moveX = next.x - cur.x
            val moveZ = next.z - cur.z
            val moveLen = hypot(moveX, moveZ)
            if (moveLen < 1e-3f) break
            val newDir = Vec2(moveX / moveLen, moveZ / moveLen)

            // Stop if streamline loops back onto itself or gets too close to another same-family road
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
