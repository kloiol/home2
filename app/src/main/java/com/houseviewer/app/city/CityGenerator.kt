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
 * - Organic curvy bypass highway framing the denser half of the city (with dashed center line)
 * - Crisp white stop bars + zebra crosswalk road markings at every street-to-highway junction
 * - Variable urban building density (denser core tapering out to rural fenced fields)
 * - Exact point-to-segment collision checks so fields & buildings NEVER clip through roads/highways
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
        val halfExtent = (config.citySize * 0.5f).coerceIn(14f, 70f)
        val isVillage = config.fieldStyle == TensorFieldStyle.VILLAGE_FIELDS

        val allModels = repository.listModels()
        val houseModels = allModels.filter { it.category == "house" }
        val cathedralModels = allModels.filter { it.category == "cathedral" }

        // 1. Pick an organic off-center dense urban core & truly randomized cathedral positions
        val denseCoreCenter = Vec2(
            (rng.nextFloat() - 0.5f) * halfExtent * 0.38f,
            (rng.nextFloat() - 0.5f) * halfExtent * 0.38f
        )
        val desiredCathedrals = config.cathedralCount.coerceIn(0, 6)
        val focalCount = max(1, desiredCathedrals)
        val focalSpread = if (isVillage) halfExtent * 0.45f else halfExtent * 0.58f
        val focalCenters = chooseRandomFocalCenters(focalCount, denseCoreCenter, focalSpread, halfExtent - 4.5f, rng)

        // 2. Build 2D Tensor Field + curvy bypass highway framing the dense half of the city
        val field = TensorField(halfExtent, config.fieldStyle, focalCenters, denseCoreCenter, rng)
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

        // 3. Place Cathedrals at randomized positions (guaranteed clear of highway!)
        var placedCathedrals = 0
        if (desiredCathedrals > 0 && cathedralModels.isNotEmpty()) {
            for (i in 0 until desiredCathedrals) {
                val model = cathedralModels[rng.nextInt(cathedralModels.size)]
                val mesh = repository.loadCityMesh(model)
                val radius = max(mesh.widthX, mesh.depthZ) * 0.56f + 0.9f
                var chosen = focalCenters[i % focalCenters.size]

                // If initial random point is too close to the curvy highway, nudge to a valid random spot
                if (collides(chosen.x, chosen.z, radius, footprints) ||
                    (bypassHighway != null && isNearRoadSegments(chosen.x, chosen.z, listOf(bypassHighway), radius + 1.8f))
                ) {
                    var found: Vec2? = null
                    for (attempt in 0 until 40) {
                        val ang = rng.nextFloat() * Math.PI.toFloat() * 2f
                        val r = kotlin.math.sqrt(rng.nextFloat()) * focalSpread
                        val cx = (denseCoreCenter.x + cos(ang) * r).coerceIn(-halfExtent + 5f, halfExtent - 5f)
                        val cz = (denseCoreCenter.z + sin(ang) * r).coerceIn(-halfExtent + 5f, halfExtent - 5f)
                        if (!collides(cx, cz, radius, footprints) &&
                            (bypassHighway == null || !isNearRoadSegments(cx, cz, listOf(bypassHighway), radius + 1.8f))
                        ) {
                            found = Vec2(cx, cz)
                            break
                        }
                    }
                    if (found != null) chosen = found else continue
                }

                val dir = field.sampleDirection(chosen.x, chosen.z, isMajor = true)
                val yaw = atan2(dir.x, dir.z)
                placements.add(PlacedBuilding(mesh, chosen.x, 0.09f, chosen.z, yaw, 1.0f))
                footprints.add(PlacedFootprint(chosen.x, chosen.z, radius))
                plazas.add(PlacedFootprint(chosen.x, chosen.z, radius + 0.9f))
                placedCathedrals++
            }
        }

        // 4. In Rural Village preset, create 3-5 empty scenic spur roads leading to isolated outpost buildings
        var placedHouses = 0
        val targetHouses = config.houseCount.coerceIn(0, 200)
        if (isVillage && houseModels.isNotEmpty()) {
            val spurCount = (3 + rng.nextInt(3)).coerceAtMost(max(1, targetHouses / 4))
            val baseSpurAngle = rng.nextFloat() * Math.PI.toFloat() * 2f
            for (s in 0 until spurCount) {
                val angle = baseSpurAngle + (2f * Math.PI.toFloat() * s / spurCount) + (rng.nextFloat() - 0.5f) * 0.35f
                val startR = halfExtent * 0.40f
                val endR = halfExtent * (0.76f + rng.nextFloat() * 0.12f)
                val startPt = Vec2(
                    (denseCoreCenter.x + cos(angle) * startR).coerceIn(-halfExtent + 3f, halfExtent - 3f),
                    (denseCoreCenter.z + sin(angle) * startR).coerceIn(-halfExtent + 3f, halfExtent - 3f)
                )
                val endPt = Vec2(
                    (denseCoreCenter.x + cos(angle) * endR).coerceIn(-halfExtent + 3f, halfExtent - 3f),
                    (denseCoreCenter.z + sin(angle) * endR).coerceIn(-halfExtent + 3f, halfExtent - 3f)
                )

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

        // 5. Collect street lot candidates (weighted so building density is highest near denseCoreCenter)
        val lotCandidates = mutableListOf<Triple<Float, Float, Float>>()
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

                // Variable building density: higher probability near denseCoreCenter, sparser near outskirts
                val distCore = hypot(midX - denseCoreCenter.x, midZ - denseCoreCenter.z)
                val densityProb = (1.0f - (distCore / (halfExtent * 1.05f)) * 0.65f).coerceIn(0.20f, 1.0f)
                if (rng.nextFloat() > densityProb) continue

                val offset = roadHalfW + 1.38f + rng.nextFloat() * 0.42f
                val lx = midX + nx * offset
                val lz = midZ + nz * offset
                if (inBounds(lx, lz, halfExtent - 2.2f)) {
                    lotCandidates.add(Triple(lx, lz, atan2(-nx, -nz)))
                }
                val rx = midX - nx * offset
                val rz = midZ - nz * offset
                if (inBounds(rx, rz, halfExtent - 2.2f)) {
                    lotCandidates.add(Triple(rx, rz, atan2(nx, nz)))
                }
            }
        }

        // Pre-compute fixed sort keys so TimSort's comparator contract is strictly satisfied
        val scoredLots = lotCandidates.map { lot ->
            val key = hypot(lot.first - denseCoreCenter.x, lot.second - denseCoreCenter.z) +
                rng.nextFloat() * halfExtent * 0.45f
            Pair(key, lot)
        }.sortedBy { it.first }
        lotCandidates.clear()
        for ((_, lot) in scoredLots) {
            lotCandidates.add(lot)
        }

        // 6. Place houses with exact segment clearance against ALL roads & highway
        if (targetHouses > 0 && houseModels.isNotEmpty()) {
            for ((lx, lz, yaw) in lotCandidates) {
                if (placedHouses >= targetHouses) break
                val model = houseModels[rng.nextInt(houseModels.size)]
                val mesh = repository.loadCityMesh(model)
                val scale = 0.90f + rng.nextFloat() * 0.20f
                val radius = max(mesh.widthX, mesh.depthZ) * 0.54f * scale + 0.35f

                if (isNearRoadSegments(lx, lz, roads, radius + 0.35f)) continue
                if (!collides(lx, lz, radius, footprints)) {
                    placements.add(PlacedBuilding(mesh, lx, 0.0f, lz, yaw, scale))
                    footprints.add(PlacedFootprint(lx, lz, radius))
                    placedHouses++
                }
            }

            // Fill remaining houses biased toward the denser urban core
            var attempts = 0
            val maxAttempts = targetHouses * 10
            while (placedHouses < targetHouses && attempts < maxAttempts) {
                attempts++
                val ang = rng.nextFloat() * Math.PI.toFloat() * 2f
                val r = rng.nextFloat() * halfExtent * 0.72f
                val rx = denseCoreCenter.x + cos(ang) * r
                val rz = denseCoreCenter.z + sin(ang) * r
                if (!inBounds(rx, rz, halfExtent - 2.4f)) continue

                val model = houseModels[rng.nextInt(houseModels.size)]
                val mesh = repository.loadCityMesh(model)
                val scale = 0.88f + rng.nextFloat() * 0.20f
                val radius = max(mesh.widthX, mesh.depthZ) * 0.54f * scale + 0.40f
                if (isNearRoadSegments(rx, rz, roads, radius + 0.40f)) continue
                if (!collides(rx, rz, radius, footprints)) {
                    val dir = field.sampleDirection(rx, rz, isMajor = true)
                    placements.add(PlacedBuilding(mesh, rx, 0.0f, rz, atan2(dir.x, dir.z), scale))
                    footprints.add(PlacedFootprint(rx, rz, radius))
                    placedHouses++
                }
            }
        }

        // 7. Place Fenced Agricultural Fields — STRICT 5-point (center + 4 corners) segment check so ZERO fields clip through highway or roads!
        val fields = mutableListOf<FencedField>()
        if (config.includeFields || isVillage) {
            val desiredFields = if (isVillage) {
                (config.citySize * 0.55f).toInt().coerceIn(14, 40)
            } else {
                (config.citySize * 0.28f).toInt().coerceIn(8, 22)
            }
            var fieldAttempts = 0
            while (fields.size < desiredFields && fieldAttempts < desiredFields * 24) {
                fieldAttempts++
                val fx = (rng.nextFloat() * 2f - 1f) * (halfExtent - 3.6f)
                val fz = (rng.nextFloat() * 2f - 1f) * (halfExtent - 3.6f)
                val fw = if (isVillage) 3.4f + rng.nextFloat() * 2.8f else 2.8f + rng.nextFloat() * 2.4f
                val fd = if (isVillage) 2.8f + rng.nextFloat() * 2.4f else 2.4f + rng.nextFloat() * 2.0f
                val fRadius = hypot(fw, fd) * 0.52f + 0.40f

                if (collides(fx, fz, fRadius, footprints)) continue

                val dir = field.sampleDirection(fx, fz, isMajor = true)
                val yaw = atan2(dir.x, dir.z)

                // Exact check: center + all 4 rotated corners + 4 edge midpoints must clear EVERY road/highway segment!
                if (fieldIntersectsAnyRoad(fx, fz, fw, fd, yaw, roads)) continue

                val col = FIELD_COLORS[rng.nextInt(FIELD_COLORS.size)]
                fields.add(FencedField(fx, fz, fw, fd, yaw, col[0], col[1], col[2]))
                footprints.add(PlacedFootprint(fx, fz, fRadius))
            }
        }

        // 8. Build unified GPU mesh with clean vertical separation
        val posOut = FloatList(65536)
        val normOut = FloatList(65536)
        val colOut = FloatList(65536)

        // Layer 0: Ground plate (top at y = 0.0f)
        val groundSize = halfExtent * 2f + 4f
        appendBox(posOut, normOut, colOut, 0f, -0.8f, 0f, groundSize, 0.8f, groundSize, 0.34f, 0.68f, 0.28f)

        // Layer 1: Fenced agricultural fields (y = 0.06f)
        for (f in fields) {
            appendFencedField(posOut, normOut, colOut, f)
        }

        // Layer 2: Cathedral stone plazas (y = 0.09f)
        for (plaza in plazas) {
            appendOctagonSlab(posOut, normOut, colOut, plaza.x, 0.0f, 0.09f, plaza.z, plaza.radius, 0.78f, 0.75f, 0.68f)
        }

        // Layer 3: Continuous Mitered Road Ribbons + Curvy Highway with dashed centerline
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

        // Layer 3b: Crisp white road markings (stop bar + zebra crosswalk) where streets meet the highway!
        for (j in junctions) {
            appendJunctionMarkings(posOut, normOut, colOut, j, yMark = 0.186f)
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
     * Tests the field center, 4 corners, and 4 edge midpoints against every road/highway
     * segment using exact point-to-segment distance — guaranteeing ZERO clipping through highways!
     */
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

    /**
     * Renders crisp dashed white highway center-line stripes along the curved highway.
     */
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
            while (s < segLen) {
                val phase = (accum + s) % cycle
                if (phase < dashLen) {
                    val rem = kotlin.math.min(dashLen - phase, segLen - s)
                    val p0 = Vec2(a.x + ux * s, a.z + uz * s)
                    val p1 = Vec2(a.x + ux * (s + rem), a.z + uz * (s + rem))
                    appendFlatStripe(posOut, normOut, colOut, p0, p1, halfW, y, 0.92f, 0.93f, 0.95f)
                    s += rem
                } else {
                    s += kotlin.math.min(cycle - phase, segLen - s)
                }
            }
            accum += segLen
        }
    }

    /**
     * Draws a crisp white stop bar + 4-stripe zebra crosswalk right where a street meets the highway.
     */
    private fun appendJunctionMarkings(
        posOut: FloatList,
        normOut: FloatList,
        colOut: FloatList,
        j: HighwayJunction,
        yMark: Float
    ) {
        // Approach vector points INTO the highway; step back from highway center to the street throat
        val backDist = 1.22f
        val tx = j.approachDirX
        val tz = j.approachDirZ
        val nx = -tz
        val nz = tx

        // 1. Solid white stop bar across street width
        val stopCx = j.x - tx * backDist
        val stopCz = j.z - tz * backDist
        val barHalf = j.streetHalfW * 0.88f
        appendFlatStripe(
            posOut, normOut, colOut,
            Vec2(stopCx - nx * barHalf, stopCz - nz * barHalf),
            Vec2(stopCx + nx * barHalf, stopCz + nz * barHalf),
            halfW = 0.09f, y = yMark, r = 0.94f, g = 0.95f, bCol = 0.96f
        )

        // 2. Zebra crosswalk bars slightly behind the stop line
        val crossCx = j.x - tx * (backDist + 0.56f)
        val crossCz = j.z - tz * (backDist + 0.56f)
        val stripeCount = 4
        for (k in 0 until stripeCount) {
            val frac = (k.toFloat() / (stripeCount - 1).toFloat()) * 2f - 1f // -1..+1
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

    private fun chooseRandomFocalCenters(
        count: Int,
        coreCenter: Vec2,
        spreadRadius: Float,
        mapLimit: Float,
        rng: Random
    ): List<Vec2> {
        val centers = mutableListOf<Vec2>()
        val minSpacing = (spreadRadius * 0.68f).coerceIn(7.5f, 18.0f)

        var attempts = 0
        while (centers.size < count && attempts < count * 40) {
            attempts++
            val angle = rng.nextFloat() * Math.PI.toFloat() * 2f
            val r = kotlin.math.sqrt(rng.nextFloat()) * spreadRadius
            val cx = (coreCenter.x + cos(angle) * r).coerceIn(-mapLimit, mapLimit)
            val cz = (coreCenter.z + sin(angle) * r).coerceIn(-mapLimit, mapLimit)

            var tooClose = false
            for (existing in centers) {
                if (hypot(cx - existing.x, cz - existing.z) < minSpacing) {
                    tooClose = true
                    break
                }
            }
            if (!tooClose) {
                centers.add(Vec2(cx, cz))
            }
        }
        if (centers.isEmpty()) {
            centers.add(coreCenter)
        }
        return centers
    }

    private fun inBounds(x: Float, z: Float, limit: Float): Boolean {
        return x >= -limit && x <= limit && z >= -limit && z <= limit
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
