package com.houseviewer.app.city

import com.houseviewer.app.model.HouseModel
import com.houseviewer.app.model.MeshData
import com.houseviewer.app.model.ModelRepository
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedurally assembles a 3D low-poly city using a 2D Tensor Field for roads
 * and building orientations, placing cathedrals at focal plazas and houses along
 * street lots, then batching all geometry into a single GPU-friendly MeshData.
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
        val z: Float,
        val yawRad: Float,
        val scale: Float
    )

    private class FloatList(initialCapacity: Int = 4096) {
        private var data = FloatArray(initialCapacity)
        var size: Int = 0
            private set

        fun add(v: Float) {
            if (size >= data.size) {
                data = data.copyOf(data.size * 2)
            }
            data[size++] = v
        }

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

    fun generate(repository: ModelRepository, config: CityConfig): GeneratedCityResult {
        val rng = Random(config.seed)
        val halfExtent = (config.citySize * 0.5f).coerceIn(14f, 70f)

        val allModels = repository.listModels()
        val houseModels = allModels.filter { it.category == "house" }
        val cathedralModels = allModels.filter { it.category == "cathedral" }

        // 1. Choose focal centers for cathedral plazas & radial tensor basis fields
        val desiredCathedrals = config.cathedralCount.coerceIn(0, 6)
        val focalCount = max(1, desiredCathedrals)
        val focalCenters = chooseFocalCenters(focalCount, halfExtent, rng)

        // 2. Build 2D Tensor Field and trace major + minor road streamlines
        val field = TensorField(halfExtent, config.fieldStyle, focalCenters, rng)
        val roads = field.traceRoadNetwork(config.roadSpacing.coerceIn(5.0f, 14.0f))

        val footprints = mutableListOf<PlacedFootprint>()
        val placements = mutableListOf<PlacedBuilding>()
        val plazas = mutableListOf<PlacedFootprint>()

        // 3. Place Cathedrals at focal centers (aligned to local tensor field)
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
                    placements.add(PlacedBuilding(mesh, center.x, center.z, yaw, 1.0f))
                    footprints.add(PlacedFootprint(center.x, center.z, radius))
                    plazas.add(PlacedFootprint(center.x, center.z, radius + 0.8f))
                    placedCathedrals++
                }
            }
        }

        // 4. Sample candidate building lots along both sides of every traced road
        val roadSamples = mutableListOf<Vec2>()
        val lotCandidates = mutableListOf<Triple<Float, Float, Float>>() // (x, z, yawRad)

        for (road in roads) {
            val pts = road.points
            val roadHalfW = if (road.isMajor) 0.95f else 0.72f
            for (i in 0 until pts.size - 1) {
                val p0 = pts[i]
                val p1 = pts[i + 1]
                roadSamples.add(p0)
                val dx = p1.x - p0.x
                val dz = p1.z - p0.z
                val segLen = hypot(dx, dz)
                if (segLen < 0.25f) continue

                val tx = dx / segLen
                val tz = dz / segLen
                // Perpendicular normal to road
                val nx = -tz
                val nz = tx
                val midX = (p0.x + p1.x) * 0.5f
                val midZ = (p0.z + p1.z) * 0.5f

                val offset = roadHalfW + 1.35f + rng.nextFloat() * 0.45f
                // Left side facing road
                val lx = midX + nx * offset
                val lz = midZ + nz * offset
                if (inBounds(lx, lz, halfExtent - 1.6f)) {
                    val yawL = atan2(-nx, -nz)
                    lotCandidates.add(Triple(lx, lz, yawL))
                }
                // Right side facing road
                val rx = midX - nx * offset
                val rz = midZ - nz * offset
                if (inBounds(rx, rz, halfExtent - 1.6f)) {
                    val yawR = atan2(nx, nz)
                    lotCandidates.add(Triple(rx, rz, yawR))
                }
            }
            if (pts.isNotEmpty()) roadSamples.add(pts.last())
        }

        lotCandidates.shuffle(rng)

        // 5. Place houses on valid street lots (zero road overlap, zero building overlap)
        var placedHouses = 0
        val targetHouses = config.houseCount.coerceIn(0, 200)
        if (targetHouses > 0 && houseModels.isNotEmpty()) {
            // Pass 1: Along traced tensor-field roads
            for ((lx, lz, yaw) in lotCandidates) {
                if (placedHouses >= targetHouses) break
                if (isTooCloseToRoad(lx, lz, roadSamples, 1.55f)) continue
                val model = houseModels[rng.nextInt(houseModels.size)]
                val mesh = repository.loadCityMesh(model)
                val scale = 0.90f + rng.nextFloat() * 0.22f
                val radius = max(mesh.widthX, mesh.depthZ) * 0.54f * scale + 0.35f

                if (!collides(lx, lz, radius, footprints)) {
                    placements.add(PlacedBuilding(mesh, lx, lz, yaw, scale))
                    footprints.add(PlacedFootprint(lx, lz, radius))
                    placedHouses++
                }
            }

            // Pass 2: Infill open city blocks aligned to local tensor field if more houses requested
            var attempts = 0
            val maxAttempts = targetHouses * 10
            while (placedHouses < targetHouses && attempts < maxAttempts) {
                attempts++
                val rx = (rng.nextFloat() * 2f - 1f) * (halfExtent - 2.0f)
                val rz = (rng.nextFloat() * 2f - 1f) * (halfExtent - 2.0f)
                if (isTooCloseToRoad(rx, rz, roadSamples, 1.65f)) continue
                val model = houseModels[rng.nextInt(houseModels.size)]
                val mesh = repository.loadCityMesh(model)
                val scale = 0.88f + rng.nextFloat() * 0.22f
                val radius = max(mesh.widthX, mesh.depthZ) * 0.54f * scale + 0.40f
                if (!collides(rx, rz, radius, footprints)) {
                    val dir = field.sampleDirection(rx, rz, isMajor = true)
                    val yaw = atan2(dir.x, dir.z)
                    placements.add(PlacedBuilding(mesh, rx, rz, yaw, scale))
                    footprints.add(PlacedFootprint(rx, rz, radius))
                    placedHouses++
                }
            }
        }

        // 6. Merge ground, roads, plazas, and all placed buildings into one batched MeshData
        val posOut = FloatList(65536)
        val normOut = FloatList(65536)
        val colOut = FloatList(65536)

        // Low-poly green city ground plate
        val groundSize = halfExtent * 2f + 4f
        appendBox(posOut, normOut, colOut, 0f, -0.5f, 0f, groundSize, 0.5f, groundSize, 0.34f, 0.68f, 0.28f)

        // Warm stone plazas under cathedrals
        for (plaza in plazas) {
            appendOctagonDisc(posOut, normOut, colOut, plaza.x, 0.015f, plaza.z, plaza.radius, 0.78f, 0.75f, 0.68f)
        }

        // Dark asphalt/cobblestone road ribbons along every tensor streamline
        var totalRoadSegments = 0
        for (road in roads) {
            val halfW = if (road.isMajor) 0.72f else 0.48f
            val yRoad = if (road.isMajor) 0.030f else 0.024f
            val rCol = if (road.isMajor) 0.28f else 0.34f
            val gCol = if (road.isMajor) 0.30f else 0.36f
            val bCol = if (road.isMajor) 0.34f else 0.40f

            val pts = road.points
            for (i in 0 until pts.size - 1) {
                appendRoadQuad(posOut, normOut, colOut, pts[i], pts[i + 1], halfW, yRoad, rCol, gCol, bCol)
                totalRoadSegments++
            }
        }

        // Append all transformed 3D buildings (cathedrals + houses)
        for (b in placements) {
            appendTransformedMesh(posOut, normOut, colOut, b.mesh, b.x, 0.0f, b.z, b.yawRad, b.scale)
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
            roadSegments = totalRoadSegments,
            seedUsed = config.seed
        )
    }

    private fun chooseFocalCenters(count: Int, halfExtent: Float, rng: Random): List<Vec2> {
        val centers = mutableListOf<Vec2>()
        // Primary center near city heart
        centers.add(Vec2((rng.nextFloat() - 0.5f) * halfExtent * 0.25f, (rng.nextFloat() - 0.5f) * halfExtent * 0.25f))
        val ringR = halfExtent * 0.52f
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

    private fun appendRoadQuad(
        posOut: FloatList,
        normOut: FloatList,
        colOut: FloatList,
        p0: Vec2,
        p1: Vec2,
        halfW: Float,
        y: Float,
        r: Float,
        g: Float,
        b: Float
    ) {
        val dx = p1.x - p0.x
        val dz = p1.z - p0.z
        val len = hypot(dx, dz)
        if (len < 1e-3f) return
        val nx = (-dz / len) * halfW
        val nz = (dx / len) * halfW

        val ax = p0.x + nx; val az = p0.z + nz
        val bx = p0.x - nx; val bz = p0.z - nz
        val cx = p1.x - nx; val cz = p1.z - nz
        val dx2 = p1.x + nx; val dz2 = p1.z + nz

        appendTri(posOut, normOut, colOut, ax, y, az, bx, y, bz, cx, y, cz, 0f, 1f, 0f, r, g, b)
        appendTri(posOut, normOut, colOut, ax, y, az, cx, y, cz, dx2, y, dz2, 0f, 1f, 0f, r, g, b)
    }

    private fun appendOctagonDisc(
        posOut: FloatList,
        normOut: FloatList,
        colOut: FloatList,
        cx: Float,
        y: Float,
        cz: Float,
        radius: Float,
        r: Float,
        g: Float,
        b: Float
    ) {
        val segs = 10
        for (i in 0 until segs) {
            val a0 = 2.0 * Math.PI * i / segs
            val a1 = 2.0 * Math.PI * (i + 1) / segs
            val x0 = cx + (radius * cos(a0)).toFloat()
            val z0 = cz + (radius * sin(a0)).toFloat()
            val x1 = cx + (radius * cos(a1)).toFloat()
            val z1 = cz + (radius * sin(a1)).toFloat()
            appendTri(posOut, normOut, colOut, cx, y, cz, x1, y, z1, x0, y, z0, 0f, 1f, 0f, r, g, b)
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
        // Top (+y)
        appendTri(posOut, normOut, colOut, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0f, 1f, 0f, r, g, b)
        appendTri(posOut, normOut, colOut, x0, y1, z1, x1, y1, z0, x0, y1, z0, 0f, 1f, 0f, r, g, b)
        // Side skirts (slightly darker earth trim)
        val sr = r * 0.62f; val sg = g * 0.62f; val sb = b * 0.62f
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
