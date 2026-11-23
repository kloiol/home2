package com.houseviewer.app.city

import com.houseviewer.app.model.MeshData
import com.houseviewer.app.model.ModelRepository
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedurally assembles a 3D low-poly city or rural village using a 2D Tensor Field:
 * - Continuous mitered road ribbons (no gaps or torn squares at curves)
 * - Clean vertical layer separation (zero z-fighting)
 * - Fenced agricultural field quarters in open village/city blocks
 * - Scenic empty country spur roads leading to isolated outpost buildings (Village preset)
 */
object CityGenerator {

    private data class PlacedFootprint(
        val x: Float,
        val z: Float,
        val radius: Float
    )

    private data class PlacedBuilding(
        val mesh: MeshData,
        val x: Float,
        val y: Float,
        val z: Float,
        val yawRad: Float,
        val scale: Float
    )

    private data class FencedField(
        val x: Float,
        val z: Float,
        val w: Float,
        val d: Float,
        val yawRad: Float,
        val r: Float,
        val g: Float,
        val b: Float
    )

    private class FloatList(initialCapacity: Int = 4096) {
        private var data = FloatArray(initialCapacity)
        var size: Int = 0
            private set

        fun add3(a: Float, b: Float, c: Float) {
            if (size + 3 > data.size) {
                data = data.copyOf(max(data.size * 2, size + 3))
            }
            data[size++] = a
            data[size++] = b
            data[size++] = c
        }

        fun toFloatArray(): FloatArray = data.copyOf(size)
    }

    // Distinct low-poly rural field colors (wheat gold, fresh pasture, clover, tilled soil)
    private val FIELD_COLORS = arrayOf(
        floatArrayOf(0.56f, 0.76f, 0.28f),
        floatArrayOf(0.76f, 0.72f, 0.30f),
        floatArrayOf(0.46f, 0.68f, 0.24f),
        floatArrayOf(0.64f, 0.52f, 0.32f),
        floatArrayOf(0.66f, 0.78f, 0.34f)
    )

    fun generate(repository: ModelRepository, config: CityConfig): GeneratedCityResult {
        val rng = Random(config.seed)
        val halfExtent = (config.citySize * 0.5f).coerceIn(14f, 70f)
        val isVillage = config.fieldStyle == TensorFieldStyle.VILLAGE_FIELDS

        val allModels = repository.listModels()
        val houseModels = allModels.filter { it.category == "house" }
        val cathedralModels = allModels.filter { it.category == "cathedral" }

        // 1. Focal centers for cathedral plazas & radial tensor fields
        val desiredCathedrals = config.cathedralCount.coerceIn(0, 6)
        val focalCount = max(1, desiredCathedrals)
        val focalCenters = chooseFocalCenters(focalCount, if (isVillage) halfExtent * 0.45f else halfExtent, rng)

        // 2. Build 2D Tensor Field & trace smooth major/minor roads
        val field = TensorField(halfExtent, config.fieldStyle, focalCenters, rng)
        val roads = field.traceRoadNetwork(config.roadSpacing.coerceIn(5.0f, 14.0f)).toMutableList()

        val footprints = mutableListOf<PlacedFootprint>()
        val placements = mutableListOf<PlacedBuilding>()
        val plazas = mutableListOf<PlacedFootprint>()

        // 3. Place Cathedrals at focal centers on raised stone plazas (y = 0.09f)
        var placedCathedrals = 0
        if (desiredCathedrals > 0 && cathedralModels.isNotEmpty()) {
            for (i in 0 until desiredCathedrals) {
                val center = focalCenters[i % focalCenters.size]
                val model = cathedralModels[rng.nextInt(cathedralModels.size)]
                val mesh = repository.loadCityMesh(model)
                val radius = max(mesh.widthX, mesh.depthZ) * 0.56f + 0.9f

                if (!collides(center.x, center.z, radius, footprints)) {
                    val dir = field.sampleDirection(center.x, center.z, isMajor = true)
                    val yaw = atan2(dir.x, dir.z)
                    placements.add(PlacedBuilding(mesh, center.x, 0.09f, center.z, yaw, 1.0f))
                    footprints.add(PlacedFootprint(center.x, center.z, radius))
                    plazas.add(PlacedFootprint(center.x, center.z, radius + 0.9f))
                    placedCathedrals++
                }
            }
        }

        // 4. In Rural Village preset, create 3-5 empty scenic spur roads leading to isolated outpost buildings!
        var placedHouses = 0
        val targetHouses = config.houseCount.coerceIn(0, 200)
        if (isVillage && houseModels.isNotEmpty()) {
            val spurCount = (3 + rng.nextInt(3)).coerceAtMost(max(1, targetHouses / 4))
            val baseSpurAngle = rng.nextFloat() * Math.PI.toFloat() * 2f
            for (s in 0 until spurCount) {
                val angle = baseSpurAngle + (2f * Math.PI.toFloat() * s / spurCount) + (rng.nextFloat() - 0.5f) * 0.35f
                val startR = halfExtent * 0.42f
                val endR = halfExtent * (0.78f + rng.nextFloat() * 0.12f)
                val startPt = Vec2(cos(angle) * startR, sin(angle) * startR)
                val endPt = Vec2(cos(angle) * endR, sin(angle) * endR)

                val model = houseModels[rng.nextInt(houseModels.size)]
                val mesh = repository.loadCityMesh(model)
                val radius = max(mesh.widthX, mesh.depthZ) * 0.58f + 0.8f
                if (!collides(endPt.x, endPt.z, radius, footprints)) {
                    val spurRoad = field.traceCountrySpur(startPt, endPt)
                    roads.add(spurRoad)
                    val lastSeg = spurRoad.points
                    val prev = lastSeg[max(0, lastSeg.size - 2)]
                    val yaw = atan2(prev.x - endPt.x, prev.z - endPt.z)
                    placements.add(PlacedBuilding(mesh, endPt.x, 0.0f, endPt.z, yaw, 1.0f))
                    footprints.add(PlacedFootprint(endPt.x, endPt.z, radius + 1.2f))
                    placedHouses++
                }
            }
        }

        // 5. Collect road sample points (for clearance) & lot candidates (skip spur roads so spurs stay empty!)
        val roadSamples = mutableListOf<Vec2>()
        val lotCandidates = mutableListOf<Triple<Float, Float, Float>>()

        for (road in roads) {
            val pts = road.points
            val roadHalfW = if (road.isMajor) 0.88f else if (road.isSpur) 0.58f else 0.66f
            for (i in 0 until pts.size - 1) {
                val p0 = pts[i]
                val p1 = pts[i + 1]
                roadSamples.add(p0)
                if (road.isSpur) continue // Keep countryside spur roads empty & scenic!

                val dx = p1.x - p0.x
                val dz = p1.z - p0.z
                val segLen = hypot(dx, dz)
                if (segLen < 0.25f) continue

                val tx = dx / segLen
                val tz = dz / segLen
                val nx = -tz
                val nz = tx
                val midX = (p0.x + p1.x) * 0.5f
                val midZ = (p0.z + p1.z) * 0.5f

                val offset = roadHalfW + 1.40f + rng.nextFloat() * 0.45f
                val lotBound = if (isVillage) halfExtent * 0.66f else halfExtent - 1.8f

                val lx = midX + nx * offset
                val lz = midZ + nz * offset
                if (inBounds(lx, lz, lotBound)) {
                    lotCandidates.add(Triple(lx, lz, atan2(-nx, -nz)))
                }
                val rx = midX - nx * offset
                val rz = midZ - nz * offset
                if (inBounds(rx, rz, lotBound)) {
                    lotCandidates.add(Triple(rx, rz, atan2(nx, nz)))
                }
            }
            if (pts.isNotEmpty()) roadSamples.add(pts.last())
        }

        lotCandidates.shuffle(rng)

        // 6. Place houses on street lots
        if (targetHouses > 0 && houseModels.isNotEmpty()) {
            for ((lx, lz, yaw) in lotCandidates) {
                if (placedHouses >= targetHouses) break
                if (isTooCloseToRoad(lx, lz, roadSamples, 1.55f)) continue
                val model = houseModels[rng.nextInt(houseModels.size)]
                val mesh = repository.loadCityMesh(model)
                val scale = 0.90f + rng.nextFloat() * 0.20f
                val radius = max(mesh.widthX, mesh.depthZ) * 0.54f * scale + 0.35f

                if (!collides(lx, lz, radius, footprints)) {
                    placements.add(PlacedBuilding(mesh, lx, 0.0f, lz, yaw, scale))
                    footprints.add(PlacedFootprint(lx, lz, radius))
                    placedHouses++
                }
            }

            // Infill open village/city core blocks if needed
            val infillLimit = if (isVillage) halfExtent * 0.60f else halfExtent - 2.0f
            var attempts = 0
            val maxAttempts = targetHouses * 8
            while (placedHouses < targetHouses && attempts < maxAttempts) {
                attempts++
                val rx = (rng.nextFloat() * 2f - 1f) * infillLimit
                val rz = (rng.nextFloat() * 2f - 1f) * infillLimit
                if (isTooCloseToRoad(rx, rz, roadSamples, 1.65f)) continue
                val model = houseModels[rng.nextInt(houseModels.size)]
                val mesh = repository.loadCityMesh(model)
                val scale = 0.88f + rng.nextFloat() * 0.20f
                val radius = max(mesh.widthX, mesh.depthZ) * 0.54f * scale + 0.42f
                if (!collides(rx, rz, radius, footprints)) {
                    val dir = field.sampleDirection(rx, rz, isMajor = true)
                    placements.add(PlacedBuilding(mesh, rx, 0.0f, rz, atan2(dir.x, dir.z), scale))
                    footprints.add(PlacedFootprint(rx, rz, radius))
                    placedHouses++
                }
            }
        }

        // 7. Place Fenced Agricultural Field Quarters in open areas
        val fields = mutableListOf<FencedField>()
        if (config.includeFields || isVillage) {
            val desiredFields = if (isVillage) {
                (config.citySize * 0.55f).toInt().coerceIn(14, 42)
            } else {
                (config.citySize * 0.22f).toInt().coerceIn(5, 18)
            }
            var fieldAttempts = 0
            while (fields.size < desiredFields && fieldAttempts < desiredFields * 14) {
                fieldAttempts++
                val fx = (rng.nextFloat() * 2f - 1f) * (halfExtent - 3.6f)
                val fz = (rng.nextFloat() * 2f - 1f) * (halfExtent - 3.6f)
                val fw = 3.6f + rng.nextFloat() * 3.0f
                val fd = 3.0f + rng.nextFloat() * 2.6f
                val fRadius = hypot(fw, fd) * 0.52f + 0.45f

                if (isTooCloseToRoad(fx, fz, roadSamples, fRadius * 0.72f)) continue
                if (collides(fx, fz, fRadius, footprints)) continue

                val dir = field.sampleDirection(fx, fz, isMajor = true)
                val yaw = atan2(dir.x, dir.z)
                val col = FIELD_COLORS[rng.nextInt(FIELD_COLORS.size)]
                fields.add(FencedField(fx, fz, fw, fd, yaw, col[0], col[1], col[2]))
                footprints.add(PlacedFootprint(fx, fz, fRadius))
            }
        }

        // 8. Build unified GPU mesh with clean vertical separation (ZERO z-fighting!)
        val posOut = FloatList(65536)
        val normOut = FloatList(65536)
        val colOut = FloatList(65536)

        // Layer 0: Ground plate (top at y = 0.0f)
        val groundSize = halfExtent * 2f + 4f
        appendBox(posOut, normOut, colOut, 0f, -0.8f, 0f, groundSize, 0.8f, groundSize, 0.34f, 0.68f, 0.28f)

        // Layer 1: Fenced agricultural fields (top at y = 0.06f, wooden fences rising to y = 0.44f)
        for (f in fields) {
            appendFencedField(posOut, normOut, colOut, f)
        }

        // Layer 2: Cathedral stone plazas (top at y = 0.09f)
        for (plaza in plazas) {
            appendOctagonSlab(posOut, normOut, colOut, plaza.x, 0.0f, 0.09f, plaza.z, plaza.radius, 0.78f, 0.75f, 0.68f)
        }

        // Layer 3: Continuous Mitered Road Ribbons (minor y = 0.12f, major y = 0.15f, spur dirt/gravel y = 0.11f)
        var totalRoadSegments = 0
        for (road in roads) {
            val halfW = when {
                road.isMajor -> 0.78f
                road.isSpur -> 0.44f
                else -> 0.52f
            }
            val yRoad = when {
                road.isMajor -> 0.15f
                road.isSpur -> 0.11f
                else -> 0.125f
            }
            val rCol: Float
            val gCol: Float
            val bCol: Float
            if (road.isSpur) {
                // Warm rural dirt/gravel country lane
                rCol = 0.62f; gCol = 0.56f; bCol = 0.44f
            } else if (road.isMajor) {
                rCol = 0.28f; gCol = 0.30f; bCol = 0.34f
            } else {
                rCol = 0.35f; gCol = 0.37f; bCol = 0.41f
            }

            totalRoadSegments += appendContinuousRoadRibbon(
                posOut, normOut, colOut, road.points, halfW, yRoad, rCol, gCol, bCol
            )
        }

        // Layer 4: All 3D Cathedrals and Houses
        for (b in placements) {
            appendTransformedMesh(posOut, normOut, colOut, b.mesh, b.x, b.y, b.z, b.yawRad, b.scale)
        }

        val positions = posOut.toFloatArray()
        val normals = normOut.toFloatArray()
        val colors = colOut.toFloatArray()
        val vertexCount = positions.size / 3

        val cityMesh = MeshData(
            positions = positions,
            normals = normals,
            colors = colors,
            vertexCount = vertexCount,
            boundingExtent = halfExtent * 1.45f,
            widthX = groundSize,
            depthZ = groundSize,
            heightY = 7.0f
        )

        return GeneratedCityResult(
            mesh = cityMesh,
            placedHouses = placedHouses,
            placedCathedrals = placedCathedrals,
            placedFields = fields.size,
            roadSegments = totalRoadSegments,
            seedUsed = config.seed
        )
    }

    /**
     * Meshes a polyline as a single continuous mitered ribbon with shared left/right
     * edge vertices at every turn — completely eliminating torn squares & corner gaps!
     */
    private fun appendContinuousRoadRibbon(
        posOut: FloatList,
        normOut: FloatList,
        colOut: FloatList,
        pts: List<Vec2>,
        halfW: Float,
        y: Float,
        r: Float,
        g: Float,
        b: Float
    ): Int {
        if (pts.size < 2) return 0
        val leftPts = ArrayList<Vec2>(pts.size)
        val rightPts = ArrayList<Vec2>(pts.size)

        for (i in pts.indices) {
            val prev = pts[max(0, i - 1)]
            val curr = pts[i]
            val next = pts[kotlin.math.min(pts.lastIndex, i + 1)]

            val t1x = curr.x - prev.x
            val t1z = curr.z - prev.z
            val l1 = hypot(t1x, t1z)
            val d1x = if (l1 > 1e-4f) t1x / l1 else 0f
            val d1z = if (l1 > 1e-4f) t1z / l1 else 0f

            val t2x = next.x - curr.x
            val t2z = next.z - curr.z
            val l2 = hypot(t2x, t2z)
            val d2x = if (l2 > 1e-4f) t2x / l2 else d1x
            val d2z = if (l2 > 1e-4f) t2z / l2 else d1z

            var tx = if (i == 0) d2x else if (i == pts.lastIndex) d1x else d1x + d2x
            var tz = if (i == 0) d2z else if (i == pts.lastIndex) d1z else d1z + d2z
            val tLen = hypot(tx, tz)
            if (tLen < 1e-4f) {
                tx = d1x; tz = d1z
            } else {
                tx /= tLen; tz /= tLen
            }

            val nx = -tz
            val nz = tx

            // Miter scale keeps road width consistent through bends without spiking
            val n1x = -d1z
            val n1z = d1x
            val dot = if (i == 0 || i == pts.lastIndex) 1f else (nx * n1x + nz * n1z)
            val miter = if (dot > 0.35f) (halfW / dot).coerceAtMost(halfW * 1.35f) else halfW

            leftPts.add(Vec2(curr.x + nx * miter, curr.z + nz * miter))
            rightPts.add(Vec2(curr.x - nx * miter, curr.z - nz * miter))
        }

        var segCount = 0
        for (i in 0 until pts.size - 1) {
            val l0 = leftPts[i]
            val r0 = rightPts[i]
            val l1 = leftPts[i + 1]
            val r1 = rightPts[i + 1]
            appendTri(posOut, normOut, colOut, l0.x, y, l0.z, r0.x, y, r0.z, r1.x, y, r1.z, 0f, 1f, 0f, r, g, b)
            appendTri(posOut, normOut, colOut, l0.x, y, l0.z, r1.x, y, r1.z, l1.x, y, l1.z, 0f, 1f, 0f, r, g, b)
            segCount++
        }
        return segCount
    }

    /**
     * Renders a rotated agricultural field patch (y = 0.06f) surrounded by a crisp
     * low-poly wooden post-and-rail fence.
     */
    private fun appendFencedField(
        posOut: FloatList,
        normOut: FloatList,
        colOut: FloatList,
        f: FencedField
    ) {
        val c = cos(f.yawRad)
        val s = sin(f.yawRad)
        val hw = f.w * 0.5f
        val hd = f.d * 0.5f

        fun worldPt(lx: Float, lz: Float): Vec2 {
            return Vec2(lx * c + lz * s + f.x, -lx * s + lz * c + f.z)
        }

        // Field crop surface (slightly raised at y = 0.06f)
        val p0 = worldPt(-hw, hd)
        val p1 = worldPt(hw, hd)
        val p2 = worldPt(hw, -hd)
        val p3 = worldPt(-hw, -hd)
        val yField = 0.06f
        appendTri(posOut, normOut, colOut, p0.x, yField, p0.z, p1.x, yField, p1.z, p2.x, yField, p2.z, 0f, 1f, 0f, f.r, f.g, f.b)
        appendTri(posOut, normOut, colOut, p0.x, yField, p0.z, p2.x, yField, p2.z, p3.x, yField, p3.z, 0f, 1f, 0f, f.r, f.g, f.b)

        // Low-poly wooden fence posts & rails around perimeter
        val fr = 0.46f; val fg = 0.32f; val fb = 0.19f
        val postH = 0.40f
        val postSize = 0.10f

        val corners = arrayOf(
            Vec2(-hw, hd), Vec2(hw, hd), Vec2(hw, -hd), Vec2(-hw, -hd)
        )
        for (edge in 0 until 4) {
            val a = corners[edge]
            val b = corners[(edge + 1) % 4]
            val edgeLen = hypot(b.x - a.x, b.z - a.z)
            val posts = max(2, (edgeLen / 1.45f).toInt())

            // Fence posts along this side
            for (k in 0 until posts) {
                val t = k.toFloat() / posts.toFloat()
                val lp = worldPt(a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t)
                appendBox(posOut, normOut, colOut, lp.x, 0.04f, lp.z, postSize, postH, postSize, fr, fg, fb)
            }

            // Horizontal rail beam (leave a small gate gap on edge 0 if wide enough)
            val wa = worldPt(a.x, a.z)
            val wb = worldPt(b.x, b.z)
            if (edge == 0 && edgeLen > 3.2f) {
                val m1 = worldPt(a.x + (b.x - a.x) * 0.36f, a.z + (b.z - a.z) * 0.36f)
                val m2 = worldPt(a.x + (b.x - a.x) * 0.64f, a.z + (b.z - a.z) * 0.64f)
                appendFenceRail(posOut, normOut, colOut, wa, m1, 0.28f, 0.07f, fr, fg, fb)
                appendFenceRail(posOut, normOut, colOut, m2, wb, 0.28f, 0.07f, fr, fg, fb)
            } else {
                appendFenceRail(posOut, normOut, colOut, wa, wb, 0.28f, 0.07f, fr, fg, fb)
            }
        }
    }

    private fun appendFenceRail(
        posOut: FloatList,
        normOut: FloatList,
        colOut: FloatList,
        a: Vec2,
        b: Vec2,
        yCenter: Float,
        thickness: Float,
        r: Float,
        g: Float,
        bCol: Float
    ) {
        val dx = b.x - a.x
        val dz = b.z - a.z
        val len = hypot(dx, dz)
        if (len < 0.1f) return
        val nx = (-dz / len) * (thickness * 0.5f)
        val nz = (dx / len) * (thickness * 0.5f)
        val y0 = yCenter - thickness * 0.5f
        val y1 = yCenter + thickness * 0.5f

        val aL = Vec2(a.x + nx, a.z + nz)
        val aR = Vec2(a.x - nx, a.z - nz)
        val bL = Vec2(b.x + nx, b.z + nz)
        val bR = Vec2(b.x - nx, b.z - nz)

        // Top face
        appendTri(posOut, normOut, colOut, aL.x, y1, aL.z, aR.x, y1, aR.z, bR.x, y1, bR.z, 0f, 1f, 0f, r, g, bCol)
        appendTri(posOut, normOut, colOut, aL.x, y1, aL.z, bR.x, y1, bR.z, bL.x, y1, bL.z, 0f, 1f, 0f, r, g, bCol)
        // Side faces
        appendTri(posOut, normOut, colOut, aL.x, y0, aL.z, bL.x, y0, bL.z, bL.x, y1, bL.z, nx, 0f, nz, r, g, bCol)
        appendTri(posOut, normOut, colOut, aL.x, y0, aL.z, bL.x, y1, bL.z, aL.x, y1, aL.z, nx, 0f, nz, r, g, bCol)
        appendTri(posOut, normOut, colOut, bR.x, y0, bR.z, aR.x, y0, aR.z, aR.x, y1, aR.z, -nx, 0f, -nz, r, g, bCol)
        appendTri(posOut, normOut, colOut, bR.x, y0, bR.z, aR.x, y1, aR.z, bR.x, y1, bR.z, -nx, 0f, -nz, r, g, bCol)
    }

    private fun chooseFocalCenters(count: Int, spreadExtent: Float, rng: Random): List<Vec2> {
        val centers = mutableListOf<Vec2>()
        centers.add(Vec2((rng.nextFloat() - 0.5f) * spreadExtent * 0.22f, (rng.nextFloat() - 0.5f) * spreadExtent * 0.22f))
        val ringR = spreadExtent * 0.52f
        val startAngle = rng.nextFloat() * Math.PI.toFloat() * 2f
        for (i in 1 until count) {
            val angle = startAngle + (2f * Math.PI.toFloat() * (i - 1) / max(1, count - 1))
            val jitter = 0.85f + rng.nextFloat() * 0.25f
            centers.add(Vec2(cos(angle) * ringR * jitter, sin(angle) * ringR * jitter))
        }
        return centers
    }

    private fun inBounds(x: Float, z: Float, limit: Float): Boolean {
        return x >= -limit && x <= limit && z >= -limit && z <= limit
    }

    private fun collides(x: Float, z: Float, radius: Float, list: List<PlacedFootprint>): Boolean {
        for (f in list) {
            val dx = x - f.x
            val dz = z - f.z
            val minD = radius + f.radius
            if (dx * dx + dz * dz < minD * minD) return true
        }
        return false
    }

    private fun isTooCloseToRoad(x: Float, z: Float, samples: List<Vec2>, minDist: Float): Boolean {
        val minSq = minDist * minDist
        for (s in samples) {
            val dx = x - s.x
            val dz = z - s.z
            if (dx * dx + dz * dz < minSq) return true
        }
        return false
    }

    private fun appendTransformedMesh(
        posOut: FloatList,
        normOut: FloatList,
        colOut: FloatList,
        mesh: MeshData,
        tx: Float,
        ty: Float,
        tz: Float,
        yawRad: Float,
        scale: Float
    ) {
        val c = cos(yawRad)
        val s = sin(yawRad)
        val p = mesh.positions
        val n = mesh.normals
        val col = mesh.colors
        val count = mesh.vertexCount

        var i = 0
        while (i < count * 3) {
            val x = p[i] * scale
            val y = p[i + 1] * scale
            val z = p[i + 2] * scale

            val rx = x * c + z * s + tx
            val ry = y + ty
            val rz = -x * s + z * c + tz
            posOut.add3(rx, ry, rz)

            val nx = n[i]
            val ny = n[i + 1]
            val nz = n[i + 2]
            normOut.add3(nx * c + nz * s, ny, -nx * s + nz * c)

            colOut.add3(col[i], col[i + 1], col[i + 2])
            i += 3
        }
    }

    private fun appendOctagonSlab(
        posOut: FloatList,
        normOut: FloatList,
        colOut: FloatList,
        cx: Float,
        y0: Float,
        y1: Float,
        cz: Float,
        radius: Float,
        r: Float,
        g: Float,
        b: Float
    ) {
        val segs = 12
        for (i in 0 until segs) {
            val a0 = 2.0 * Math.PI * i / segs
            val a1 = 2.0 * Math.PI * (i + 1) / segs
            val x0 = cx + (radius * cos(a0)).toFloat()
            val z0 = cz + (radius * sin(a0)).toFloat()
            val x1 = cx + (radius * cos(a1)).toFloat()
            val z1 = cz + (radius * sin(a1)).toFloat()
            appendTri(posOut, normOut, colOut, cx, y1, cz, x1, y1, z1, x0, y1, z0, 0f, 1f, 0f, r, g, b)
            // Side rim
            val sr = r * 0.85f; val sg = g * 0.85f; val sb = b * 0.85f
            appendTri(posOut, normOut, colOut, x0, y0, z0, x1, y0, z1, x1, y1, z1, x0 - cx, 0f, z0 - cz, sr, sg, sb)
            appendTri(posOut, normOut, colOut, x0, y0, z0, x1, y1, z1, x0, y1, z0, x0 - cx, 0f, z0 - cz, sr, sg, sb)
        }
    }

    private fun appendBox(
        posOut: FloatList,
        normOut: FloatList,
        colOut: FloatList,
        cx: Float,
        y0: Float,
        cz: Float,
        sx: Float,
        sy: Float,
        sz: Float,
        r: Float,
        g: Float,
        b: Float
    ) {
        val x0 = cx - sx * 0.5f; val x1 = cx + sx * 0.5f
        val y1 = y0 + sy
        val z0 = cz - sz * 0.5f; val z1 = cz + sz * 0.5f
        appendTri(posOut, normOut, colOut, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0f, 1f, 0f, r, g, b)
        appendTri(posOut, normOut, colOut, x0, y1, z1, x1, y1, z0, x0, y1, z0, 0f, 1f, 0f, r, g, b)
        val sr = r * 0.65f; val sg = g * 0.65f; val sb = b * 0.65f
        appendTri(posOut, normOut, colOut, x0, y0, z1, x1, y0, z1, x1, y1, z1, 0f, 0f, 1f, sr, sg, sb)
        appendTri(posOut, normOut, colOut, x0, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f, sr, sg, sb)
        appendTri(posOut, normOut, colOut, x1, y0, z0, x0, y0, z0, x0, y1, z0, 0f, 0f, -1f, sr, sg, sb)
        appendTri(posOut, normOut, colOut, x1, y0, z0, x0, y1, z0, x1, y1, z0, 0f, 0f, -1f, sr, sg, sb)
        appendTri(posOut, normOut, colOut, x1, y0, z1, x1, y0, z0, x1, y1, z0, 1f, 0f, 0f, sr, sg, sb)
        appendTri(posOut, normOut, colOut, x1, y0, z1, x1, y1, z0, x1, y1, z1, 1f, 0f, 0f, sr, sg, sb)
        appendTri(posOut, normOut, colOut, x0, y0, z0, x0, y0, z1, x0, y1, z1, -1f, 0f, 0f, sr, sg, sb)
        appendTri(posOut, normOut, colOut, x0, y0, z0, x0, y1, z1, x0, y1, z0, -1f, 0f, 0f, sr, sg, sb)
    }

    private fun appendTri(
        posOut: FloatList,
        normOut: FloatList,
        colOut: FloatList,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float
    ) {
        posOut.add3(x0, y0, z0); posOut.add3(x1, y1, z1); posOut.add3(x2, y2, z2)
        normOut.add3(nx, ny, nz); normOut.add3(nx, ny, nz); normOut.add3(nx, ny, nz)
        colOut.add3(r, g, b); colOut.add3(r, g, b); colOut.add3(r, g, b)
    }
}
