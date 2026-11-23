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
 * Procedurally assembles a 3D low-poly city, rural village, or exclusive Soviet Microrayon
 * on any parametric CityPlate shape (Square, Hexagon, Semicircle, 5-Pointed Star, Rhombus),
 * bending roads and constraining buildings to the user's surface-area coverage setting.
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

    private val FIELD_COLORS = arrayOf(
        floatArrayOf(0.56f, 0.76f, 0.28f),
        floatArrayOf(0.76f, 0.72f, 0.30f),
        floatArrayOf(0.46f, 0.68f, 0.24f),
        floatArrayOf(0.64f, 0.52f, 0.32f),
        floatArrayOf(0.66f, 0.78f, 0.34f)
    )

    fun generate(repository: ModelRepository, config: CityConfig): GeneratedCityResult {
        val rng = Random(config.seed)
        val plate = CityPlate(
            shape = config.plateShape,
            radius = config.plateRadius.coerceIn(16f, 68f),
            diagonalRatio = config.diagonalRatio,
            edgeCurvature = config.edgeCurvature,
            areaCoverage = config.areaCoverage
        )
        val isVillage = config.fieldStyle == TensorFieldStyle.VILLAGE_FIELDS
        val isSoviet = config.fieldStyle == TensorFieldStyle.SOVIET_MICRORAYON

        val allModels = repository.listModels()
        val houseModels = allModels.filter { it.category == "house" }
        val cathedralModels = allModels.filter { it.category == "cathedral" }
        val khrushModels = allModels.filter { it.category == "khrushchevka" }
        val lampModels = allModels.filter { it.category == "lamp_post" }

        // Strictly ONE consistent lamp post model per seed
        val chosenLampMesh: MeshData? = if (lampModels.isNotEmpty()) {
            repository.loadCityMesh(lampModels[rng.nextInt(lampModels.size)])
        } else {
            null
        }

        // 1. Sample dense urban core & randomized focal/cathedral positions strictly inside the plate coverage zone
        val denseCoreCenter = samplePointInsidePlate(plate, useCoverage = true, margin = 4.0f, rng = rng)
        val desiredCathedrals = if (isSoviet) 0 else config.cathedralCount.coerceIn(0, 6)
        val focalCount = max(1, desiredCathedrals)
        val focalCenters = chooseRandomFocalCenters(focalCount, plate, rng)

        // 2. Build shape-conforming Tensor Field + curvy contour-following bypass highway
        val field = TensorField(plate, config.fieldStyle, focalCenters, denseCoreCenter, rng)
        val bypassHighway = if (!isVillage) field.buildCurvyBypassHighway() else null

        val junctions = mutableListOf<HighwayJunction>()
        val roads = field.traceRoadNetwork(
            config.roadSpacing.coerceIn(5.0f, 14.0f),
            bypassHighway,
            junctions
        ).toMutableList()
        if (bypassHighway != null) {
            roads.add(bypassHighway)
        }

        val footprints = mutableListOf<PlacedFootprint>()
        val placements = mutableListOf<PlacedBuilding>()
        val plazas = mutableListOf<PlacedFootprint>()

        // 3. Place Cathedrals (never in Soviet preset, strictly inside plate coverage & clear of highway)
        var placedCathedrals = 0
        if (!isSoviet && desiredCathedrals > 0 && cathedralModels.isNotEmpty()) {
            for (i in 0 until desiredCathedrals) {
                val model = cathedralModels[rng.nextInt(cathedralModels.size)]
                val mesh = repository.loadCityMesh(model)
                val radius = max(mesh.widthX, mesh.depthZ) * 0.56f + 0.9f
                var chosen: Vec2? = focalCenters.getOrNull(i)

                if (chosen == null ||
                    !plate.containsInCoverage(chosen.x, chosen.z, radius + 0.8f) ||
                    collides(chosen.x, chosen.z, radius, footprints) ||
                    (bypassHighway != null && isNearRoadSegments(chosen.x, chosen.z, listOf(bypassHighway), radius + 1.6f))
                ) {
                    chosen = null
                    for (attempt in 0 until 55) {
                        val pt = samplePointInsidePlate(plate, useCoverage = true, margin = radius + 0.8f, rng = rng)
                        if (!plate.containsInCoverage(pt.x, pt.z, radius + 0.8f)) continue
                        if (!collides(pt.x, pt.z, radius, footprints) &&
                            (bypassHighway == null || !isNearRoadSegments(pt.x, pt.z, listOf(bypassHighway), radius + 1.6f))
                        ) {
                            chosen = pt
                            break
                        }
                    }
                }
                if (chosen == null) continue

                val dir = field.sampleDirection(chosen.x, chosen.z, isMajor = true)
                val yaw = atan2(dir.x, dir.z)
                placements.add(PlacedBuilding(mesh, chosen.x, 0.09f, chosen.z, yaw, 1.0f))
                footprints.add(PlacedFootprint(chosen.x, chosen.z, radius))
                plazas.add(PlacedFootprint(chosen.x, chosen.z, radius + 0.9f))
                placedCathedrals++
            }
        }

        // 4. Rural Village scenic spur roads to isolated outposts (strictly inside plate!)
        var placedBuildings = 0
        val targetBuildings = config.houseCount.coerceIn(0, 200)
        if (isVillage && houseModels.isNotEmpty()) {
            val spurCount = (3 + rng.nextInt(3)).coerceAtMost(max(1, targetBuildings / 4))
            for (s in 0 until spurCount) {
                val endPt = samplePointInsidePlate(plate, useCoverage = false, margin = 3.2f, rng = rng)
                if (!plate.contains(endPt.x, endPt.z, 3.2f)) continue
                val startPt = Vec2(endPt.x * 0.45f, endPt.z * 0.45f)
                if (!plate.contains(startPt.x, startPt.z, 2.0f)) continue

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
                    placedBuildings++
                }
            }
        }

        // 5. Collect street lot candidates strictly inside plate coverage zone
        val lotCandidates = mutableListOf<Triple<Float, Float, Float>>()
        val maxCovR = plate.maxRadius * plate.coverageLinearScale
        for (road in roads) {
            if (road.isSpur || road.isHighway) continue
            val pts = road.points
            val roadHalfW = if (road.isMajor) 0.78f else 0.58f
            for (i in 0 until pts.size - 1) {
                val p0 = pts[i]
                val p1 = pts[i + 1]
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

                val distCore = hypot(midX - denseCoreCenter.x, midZ - denseCoreCenter.z)
                val densityProb = if (isSoviet) 0.90f else (1.0f - (distCore / (maxCovR * 1.15f)) * 0.60f).coerceIn(0.25f, 1.0f)
                if (rng.nextFloat() > densityProb) continue

                val offset = roadHalfW + (if (isSoviet) 2.15f else 1.38f) + rng.nextFloat() * 0.45f
                val edgeMargin = if (isSoviet) 2.8f else 1.8f

                val lx = midX + nx * offset
                val lz = midZ + nz * offset
                if (plate.containsInCoverage(lx, lz, edgeMargin)) {
                    val yawL = atan2(-nx, -nz) + if (isSoviet && rng.nextFloat() < 0.30f) (Math.PI * 0.5).toFloat() else 0f
                    lotCandidates.add(Triple(lx, lz, yawL))
                }
                val rx = midX - nx * offset
                val rz = midZ - nz * offset
                if (plate.containsInCoverage(rx, rz, edgeMargin)) {
                    val yawR = atan2(nx, nz) + if (isSoviet && rng.nextFloat() < 0.30f) (Math.PI * 0.5).toFloat() else 0f
                    lotCandidates.add(Triple(rx, rz, yawR))
                }
            }
        }

        val scoredLots = lotCandidates.map { lot ->
            val key = hypot(lot.first - denseCoreCenter.x, lot.second - denseCoreCenter.z) +
                rng.nextFloat() * maxCovR * 0.45f
            Pair(key, lot)
        }.sortedBy { it.first }
        lotCandidates.clear()
        for ((_, lot) in scoredLots) {
            lotCandidates.add(lot)
        }

        // 6. Place primary buildings strictly inside plate coverage zone
        val primaryPool = if (isSoviet) khrushModels else houseModels
        if (targetBuildings > 0 && primaryPool.isNotEmpty()) {
            for ((lx, lz, yaw) in lotCandidates) {
                if (placedBuildings >= targetBuildings) break
                val model = primaryPool[rng.nextInt(primaryPool.size)]
                val mesh = repository.loadCityMesh(model)
                val scale = if (isSoviet) (0.86f + rng.nextFloat() * 0.16f) else (0.90f + rng.nextFloat() * 0.20f)
                val radius = max(mesh.widthX, mesh.depthZ) * 0.53f * scale + (if (isSoviet) 0.55f else 0.35f)

                if (!plate.containsInCoverage(lx, lz, radius + 0.3f)) continue
                if (isNearRoadSegments(lx, lz, roads, radius + 0.35f)) continue
                if (!collides(lx, lz, radius, footprints)) {
                    placements.add(PlacedBuilding(mesh, lx, 0.0f, lz, yaw, scale))
                    footprints.add(PlacedFootprint(lx, lz, radius))
                    placedBuildings++
                }
            }

            var attempts = 0
            val maxAttempts = targetBuildings * 12
            while (placedBuildings < targetBuildings && attempts < maxAttempts) {
                attempts++
                val pt = samplePointInsidePlate(plate, useCoverage = true, margin = if (isSoviet) 3.0f else 2.0f, rng = rng)
                val model = primaryPool[rng.nextInt(primaryPool.size)]
                val mesh = repository.loadCityMesh(model)
                val scale = if (isSoviet) (0.86f + rng.nextFloat() * 0.16f) else (0.88f + rng.nextFloat() * 0.20f)
                val radius = max(mesh.widthX, mesh.depthZ) * 0.53f * scale + (if (isSoviet) 0.60f else 0.40f)
                if (!plate.containsInCoverage(pt.x, pt.z, radius + 0.3f)) continue
                if (isNearRoadSegments(pt.x, pt.z, roads, radius + 0.40f)) continue
                if (!collides(pt.x, pt.z, radius, footprints)) {
                    val dir = field.sampleDirection(pt.x, pt.z, isMajor = true)
                    val yaw = atan2(dir.x, dir.z) + if (isSoviet && rng.nextFloat() < 0.35f) (Math.PI * 0.5).toFloat() else 0f
                    placements.add(PlacedBuilding(mesh, pt.x, 0.0f, pt.z, yaw, scale))
                    footprints.add(PlacedFootprint(pt.x, pt.z, radius))
                    placedBuildings++
                }
            }
        }

        // 7. Place Fenced Agricultural Fields strictly inside plate & clear of all roads/highways
        val fields = mutableListOf<FencedField>()
        if (!isSoviet && (config.includeFields || isVillage)) {
            val desiredFields = if (isVillage) {
                (plate.radius * 0.95f).toInt().coerceIn(12, 38)
            } else {
                (plate.radius * 0.48f).toInt().coerceIn(6, 20)
            }
            var fieldAttempts = 0
            while (fields.size < desiredFields && fieldAttempts < desiredFields * 26) {
                fieldAttempts++
                val pt = samplePointInsidePlate(plate, useCoverage = false, margin = 2.8f, rng = rng)
                val fw = if (isVillage) 3.2f + rng.nextFloat() * 2.6f else 2.6f + rng.nextFloat() * 2.2f
                val fd = if (isVillage) 2.6f + rng.nextFloat() * 2.2f else 2.2f + rng.nextFloat() * 1.8f
                val fRadius = hypot(fw, fd) * 0.52f + 0.40f

                if (collides(pt.x, pt.z, fRadius, footprints)) continue
                val dir = field.sampleDirection(pt.x, pt.z, isMajor = true)
                val yaw = atan2(dir.x, dir.z)

                if (!fieldFitsInsidePlate(pt.x, pt.z, fw, fd, yaw, plate)) continue
                if (fieldIntersectsAnyRoad(pt.x, pt.z, fw, fd, yaw, roads)) continue

                val col = FIELD_COLORS[rng.nextInt(FIELD_COLORS.size)]
                fields.add(FencedField(pt.x, pt.z, fw, fd, yaw, col[0], col[1], col[2]))
                footprints.add(PlacedFootprint(pt.x, pt.z, fRadius))
            }
        }

        // 8. Scatter ONE consistent Lamp Post model logically along roads in EVERY preset
        val placedLampPositions = mutableListOf<Vec2>()
        var placedLamps = 0
        if (chosenLampMesh != null) {
            val minLampSpacing = 6.4f
            val minLampSpacingSq = minLampSpacing * minLampSpacing
            var sideToggle = 1f

            for (road in roads) {
                if (road.isSpur) continue
                val pts = road.points
                if (pts.size < 2) continue
                val roadHalfW = when {
                    road.isHighway -> 1.08f
                    road.isMajor -> 0.72f
                    else -> 0.50f
                }
                val stepInterval = if (road.isHighway) 9.5f else 7.6f
                var accum = stepInterval * 0.5f

                for (i in 0 until pts.size - 1) {
                    val a = pts[i]
                    val b = pts[i + 1]
                    val segLen = hypot(b.x - a.x, b.z - a.z)
                    if (segLen < 0.2f) continue
                    val ux = (b.x - a.x) / segLen
                    val uz = (b.z - a.z) / segLen
                    val nx = -uz
                    val nz = ux

                    accum += segLen
                    if (accum >= stepInterval) {
                        accum -= stepInterval
                        sideToggle = -sideToggle
                        val lx = (a.x + b.x) * 0.5f + nx * sideToggle * (roadHalfW + 0.34f)
                        val lz = (a.z + b.z) * 0.5f + nz * sideToggle * (roadHalfW + 0.34f)
                        if (!plate.contains(lx, lz, margin = 0.8f)) continue

                        var tooCloseLamp = false
                        for (lp in placedLampPositions) {
                            val dx = lx - lp.x
                            val dz = lz - lp.z
                            if (dx * dx + dz * dz < minLampSpacingSq) {
                                tooCloseLamp = true
                                break
                            }
                        }
                        if (tooCloseLamp) continue
                        if (collides(lx, lz, 0.35f, footprints)) continue
                        if (isInsideAnyRoadSurface(lx, lz, roads)) continue

                        val yaw = atan2(-sideToggle * nx, -sideToggle * nz)
                        placements.add(PlacedBuilding(chosenLampMesh, lx, 0.0f, lz, yaw, 1.0f))
                        placedLampPositions.add(Vec2(lx, lz))
                        placedLamps++
                    }
                }
            }
        }

        // 9. Build unified GPU mesh on the exact parametric 3D plate polygon!
        val posOut = FloatList(65536)
        val normOut = FloatList(65536)
        val colOut = FloatList(65536)

        val gr = if (isSoviet) 0.36f else 0.34f
        val gg = if (isSoviet) 0.58f else 0.68f
        val gb = if (isSoviet) 0.30f else 0.28f
        appendPolygonPlate(posOut, normOut, colOut, plate, yBot = -0.9f, yTop = 0.0f, gr, gg, gb)

        for (f in fields) {
            appendFencedField(posOut, normOut, colOut, f)
        }

        for (plaza in plazas) {
            appendOctagonSlab(posOut, normOut, colOut, plaza.x, 0.0f, 0.09f, plaza.z, plaza.radius, 0.78f, 0.75f, 0.68f)
        }

        var totalRoadSegments = 0
        for (road in roads) {
            val halfW = when {
                road.isHighway -> 1.08f
                road.isMajor -> 0.72f
                road.isSpur -> 0.44f
                else -> 0.50f
            }
            val yRoad = when {
                road.isHighway -> 0.17f
                road.isMajor -> 0.145f
                road.isSpur -> 0.11f
                else -> 0.125f
            }
            val rCol: Float
            val gCol: Float
            val bCol: Float
            when {
                road.isHighway -> {
                    rCol = 0.21f; gCol = 0.23f; bCol = 0.27f
                }
                road.isSpur -> {
                    rCol = 0.62f; gCol = 0.56f; bCol = 0.44f
                }
                road.isMajor -> {
                    rCol = 0.28f; gCol = 0.30f; bCol = 0.34f
                }
                else -> {
                    rCol = 0.35f; gCol = 0.37f; bCol = 0.41f
                }
            }

            totalRoadSegments += appendContinuousRoadRibbon(
                posOut, normOut, colOut, road.points, halfW, yRoad, rCol, gCol, bCol
            )
            if (road.isHighway) {
                appendDashedCenterline(posOut, normOut, colOut, road.points, yRoad + 0.015f, 0.08f, 1.35f, 1.15f)
            }
        }

        for (j in junctions) {
            appendJunctionMarkings(posOut, normOut, colOut, j, yMark = 0.186f)
        }

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
            boundingExtent = plate.maxRadius * 1.42f,
            widthX = plate.maxX - plate.minX,
            depthZ = plate.maxZ - plate.minZ,
            heightY = 7.0f
        )

        return GeneratedCityResult(
            mesh = cityMesh,
            placedHouses = placedBuildings,
            placedCathedrals = placedCathedrals,
            placedFields = fields.size,
            placedLamps = placedLamps,
            roadSegments = totalRoadSegments,
            isSovietMode = isSoviet,
            seedUsed = config.seed
        )
    }

    /**
     * Triangulates any star-shaped 2D plate polygon (Square, Hexagon, Semicircle,
     * 5-Pointed Star, Rhombus) into a crisp 3D slab with darkened perimeter earth skirts.
     */
    private fun appendPolygonPlate(
        posOut: FloatList,
        normOut: FloatList,
        colOut: FloatList,
        plate: CityPlate,
        yBot: Float,
        yTop: Float,
        r: Float,
        g: Float,
        b: Float
    ) {
        val poly = plate.boundary
        val n = poly.size
        val cx = plate.centroid.x
        val cz = plate.centroid.z
        val sr = r * 0.62f
        val sg = g * 0.62f
        val sb = b * 0.62f

        for (i in 0 until n) {
            val a = poly[i]
            val c = poly[(i + 1) % n]
            // Top surface (+y)
            appendTri(posOut, normOut, colOut, cx, yTop, cz, c.x, yTop, c.z, a.x, yTop, a.z, 0f, 1f, 0f, r, g, b)
            // Bottom cap (-y)
            appendTri(posOut, normOut, colOut, cx, yBot, cz, a.x, yBot, a.z, c.x, yBot, c.z, 0f, -1f, 0f, sr, sg, sb)
            // Perimeter skirt quad
            val dx = c.x - a.x
            val dz = c.z - a.z
            val l = hypot(dx, dz).coerceAtLeast(1e-4f)
            val nx = dz / l
            val nz = -dx / l
            appendTri(posOut, normOut, colOut, a.x, yBot, a.z, c.x, yBot, c.z, c.x, yTop, c.z, nx, 0f, nz, sr, sg, sb)
            appendTri(posOut, normOut, colOut, a.x, yBot, a.z, c.x, yTop, c.z, a.x, yTop, a.z, nx, 0f, nz, sr, sg, sb)
        }
    }

    private fun samplePointInsidePlate(
        plate: CityPlate,
        useCoverage: Boolean,
        margin: Float,
        rng: Random
    ): Vec2 {
        val loX = plate.minX; val hiX = plate.maxX
        val loZ = plate.minZ; val hiZ = plate.maxZ
        for (attempt in 0 until 60) {
            val x = loX + rng.nextFloat() * (hiX - loX)
            val z = loZ + rng.nextFloat() * (hiZ - loZ)
            val ok = if (useCoverage) plate.containsInCoverage(x, z, margin) else plate.contains(x, z, margin)
            if (ok) return Vec2(x, z)
        }
        return plate.centroid
    }

    private fun chooseRandomFocalCenters(count: Int, plate: CityPlate, rng: Random): List<Vec2> {
        val centers = mutableListOf<Vec2>()
        val minSpacing = (plate.maxRadius * plate.coverageLinearScale * 0.48f).coerceIn(7.0f, 18.0f)
        var attempts = 0
        while (centers.size < count && attempts < count * 50) {
            attempts++
            val pt = samplePointInsidePlate(plate, useCoverage = true, margin = 4.2f, rng = rng)
            var tooClose = false
            for (existing in centers) {
                if (hypot(pt.x - existing.x, pt.z - existing.z) < minSpacing) {
                    tooClose = true
                    break
                }
            }
            if (!tooClose) centers.add(pt)
        }
        if (centers.isEmpty()) centers.add(plate.centroid)
        return centers
    }

    private fun fieldFitsInsidePlate(
        fx: Float,
        fz: Float,
        fw: Float,
        fd: Float,
        yawRad: Float,
        plate: CityPlate
    ): Boolean {
        val c = cos(yawRad)
        val s = sin(yawRad)
        val hw = fw * 0.5f + 0.35f
        val hd = fd * 0.5f + 0.35f
        val corners = arrayOf(Vec2(-hw, -hd), Vec2(hw, -hd), Vec2(hw, hd), Vec2(-hw, hd))
        for (lp in corners) {
            val wx = lp.x * c + lp.z * s + fx
            val wz = -lp.x * s + lp.z * c + fz
            if (!plate.contains(wx, wz, margin = 0.6f)) return false
        }
        return true
    }

    private fun isInsideAnyRoadSurface(px: Float, pz: Float, roads: List<RoadPolyline>): Boolean {
        for (road in roads) {
            val roadHalfW = when {
                road.isHighway -> 1.10f
                road.isMajor -> 0.74f
                road.isSpur -> 0.46f
                else -> 0.52f
            }
            val pts = road.points
            for (i in 0 until pts.size - 1) {
                if (TensorField.distPointToSegment(px, pz, pts[i], pts[i + 1]) < roadHalfW + 0.08f) {
                    return true
                }
            }
        }
        return false
    }

    private fun fieldIntersectsAnyRoad(
        fx: Float,
        fz: Float,
        fw: Float,
        fd: Float,
        yawRad: Float,
        roads: List<RoadPolyline>
    ): Boolean {
        val c = cos(yawRad)
        val s = sin(yawRad)
        val hw = fw * 0.5f + 0.25f
        val hd = fd * 0.5f + 0.25f

        val testLocal = arrayOf(
            Vec2(0f, 0f),
            Vec2(-hw, -hd), Vec2(hw, -hd), Vec2(hw, hd), Vec2(-hw, hd),
            Vec2(0f, -hd), Vec2(0f, hd), Vec2(-hw, 0f), Vec2(hw, 0f)
        )
        for (lp in testLocal) {
            val wx = lp.x * c + lp.z * s + fx
            val wz = -lp.x * s + lp.z * c + fz
            if (isNearRoadSegments(wx, wz, roads, extraMargin = 0.45f)) return true
        }
        return false
    }

    private fun isNearRoadSegments(
        px: Float,
        pz: Float,
        roads: List<RoadPolyline>,
        extraMargin: Float
    ): Boolean {
        for (road in roads) {
            val roadHalfW = when {
                road.isHighway -> 1.15f
                road.isMajor -> 0.78f
                road.isSpur -> 0.50f
                else -> 0.56f
            }
            val limit = roadHalfW + extraMargin
            val pts = road.points
            for (i in 0 until pts.size - 1) {
                if (TensorField.distPointToSegment(px, pz, pts[i], pts[i + 1]) < limit) {
                    return true
                }
            }
        }
        return false
    }

    private fun appendDashedCenterline(
        posOut: FloatList,
        normOut: FloatList,
        colOut: FloatList,
        pts: List<Vec2>,
        y: Float,
        halfW: Float,
        dashLen: Float,
        gapLen: Float
    ) {
        if (pts.size < 2) return
        val cycle = dashLen + gapLen
        var accum = 0f
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val segLen = hypot(b.x - a.x, b.z - a.z)
            if (segLen < 1e-3f) continue
            val ux = (b.x - a.x) / segLen
            val uz = (b.z - a.z) / segLen

            var s = 0f
            var guard = 0
            while (s < segLen - 1e-3f && guard < 256) {
                guard++
                val phase = (accum + s) % cycle
                if (phase < dashLen) {
                    val rem = kotlin.math.min(dashLen - phase, segLen - s)
                    if (rem > 0.02f) {
                        val p0 = Vec2(a.x + ux * s, a.z + uz * s)
                        val p1 = Vec2(a.x + ux * (s + rem), a.z + uz * (s + rem))
                        appendFlatStripe(posOut, normOut, colOut, p0, p1, halfW, y, 0.92f, 0.93f, 0.95f)
                    }
                    s += max(rem, 0.05f)
                } else {
                    val skip = kotlin.math.min(cycle - phase, segLen - s)
                    s += max(skip, 0.05f)
                }
            }
            accum += segLen
        }
    }

    private fun appendJunctionMarkings(
        posOut: FloatList,
        normOut: FloatList,
        colOut: FloatList,
        j: HighwayJunction,
        yMark: Float
    ) {
        val backDist = 1.22f
        val tx = j.approachDirX
        val tz = j.approachDirZ
        val nx = -tz
        val nz = tx

        val stopCx = j.x - tx * backDist
        val stopCz = j.z - tz * backDist
        val barHalf = j.streetHalfW * 0.88f
        appendFlatStripe(
            posOut, normOut, colOut,
            Vec2(stopCx - nx * barHalf, stopCz - nz * barHalf),
            Vec2(stopCx + nx * barHalf, stopCz + nz * barHalf),
            halfW = 0.09f, y = yMark, r = 0.94f, g = 0.95f, bCol = 0.96f
        )

        val crossCx = j.x - tx * (backDist + 0.56f)
        val crossCz = j.z - tz * (backDist + 0.56f)
        val stripeCount = 4
        for (k in 0 until stripeCount) {
            val frac = (k.toFloat() / (stripeCount - 1).toFloat()) * 2f - 1f
            val lat = frac * (j.streetHalfW * 0.68f)
            val sx = crossCx + nx * lat
            val sz = crossCz + nz * lat
            appendFlatStripe(
                posOut, normOut, colOut,
                Vec2(sx - tx * 0.24f, sz - tz * 0.24f),
                Vec2(sx + tx * 0.24f, sz + tz * 0.24f),
                halfW = 0.075f, y = yMark, r = 0.92f, g = 0.93f, bCol = 0.95f
            )
        }
    }

    private fun appendFlatStripe(
        posOut: FloatList,
        normOut: FloatList,
        colOut: FloatList,
        a: Vec2,
        b: Vec2,
        halfW: Float,
        y: Float,
        r: Float,
        g: Float,
        bCol: Float
    ) {
        val dx = b.x - a.x
        val dz = b.z - a.z
        val len = hypot(dx, dz)
        if (len < 1e-3f) return
        val nx = (-dz / len) * halfW
        val nz = (dx / len) * halfW
        appendTri(posOut, normOut, colOut, a.x + nx, y, a.z + nz, a.x - nx, y, a.z - nz, b.x - nx, y, b.z - nz, 0f, 1f, 0f, r, g, bCol)
        appendTri(posOut, normOut, colOut, a.x + nx, y, a.z + nz, b.x - nx, y, b.z - nz, b.x + nx, y, b.z + nz, 0f, 1f, 0f, r, g, bCol)
    }

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

        val p0 = worldPt(-hw, hd)
        val p1 = worldPt(hw, hd)
        val p2 = worldPt(hw, -hd)
        val p3 = worldPt(-hw, -hd)
        val yField = 0.06f
        appendTri(posOut, normOut, colOut, p0.x, yField, p0.z, p1.x, yField, p1.z, p2.x, yField, p2.z, 0f, 1f, 0f, f.r, f.g, f.b)
        appendTri(posOut, normOut, colOut, p0.x, yField, p0.z, p2.x, yField, p2.z, p3.x, yField, p3.z, 0f, 1f, 0f, f.r, f.g, f.b)

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

            for (k in 0 until posts) {
                val t = k.toFloat() / posts.toFloat()
                val lp = worldPt(a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t)
                appendBox(posOut, normOut, colOut, lp.x, 0.04f, lp.z, postSize, postH, postSize, fr, fg, fb)
            }

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

        appendTri(posOut, normOut, colOut, aL.x, y1, aL.z, aR.x, y1, aR.z, bR.x, y1, bR.z, 0f, 1f, 0f, r, g, bCol)
        appendTri(posOut, normOut, colOut, aL.x, y1, aL.z, bR.x, y1, bR.z, bL.x, y1, bL.z, 0f, 1f, 0f, r, g, bCol)
        appendTri(posOut, normOut, colOut, aL.x, y0, aL.z, bL.x, y0, bL.z, bL.x, y1, bL.z, nx, 0f, nz, r, g, bCol)
        appendTri(posOut, normOut, colOut, aL.x, y0, aL.z, bL.x, y1, bL.z, aL.x, y1, aL.z, nx, 0f, nz, r, g, bCol)
        appendTri(posOut, normOut, colOut, bR.x, y0, bR.z, aR.x, y0, aR.z, aR.x, y1, aR.z, -nx, 0f, -nz, r, g, bCol)
        appendTri(posOut, normOut, colOut, bR.x, y0, bR.z, aR.x, y1, aR.z, bR.x, y1, bR.z, -nx, 0f, -nz, r, g, bCol)
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
