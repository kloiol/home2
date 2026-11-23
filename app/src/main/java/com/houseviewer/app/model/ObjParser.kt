package com.houseviewer.app.model

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.sqrt

object ObjParser {

    private val defaultColor = floatArrayOf(0.86f, 0.88f, 0.90f)

    fun parseMaterials(mtlStream: InputStream?): Map<String, FloatArray> {
        val materials = mutableMapOf<String, FloatArray>()
        if (mtlStream == null) return materials

        var currentName: String? = null
        BufferedReader(InputStreamReader(mtlStream)).useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0]) {
                    "newmtl" -> if (parts.size >= 2) {
                        currentName = line.substringAfter("newmtl").trim()
                    }
                    "Kd" -> if (currentName != null && parts.size >= 4) {
                        val r = parts[1].toFloatOrNull() ?: 0.8f
                        val g = parts[2].toFloatOrNull() ?: 0.8f
                        val b = parts[3].toFloatOrNull() ?: 0.8f
                        materials[currentName!!] = floatArrayOf(r, g, b)
                    }
                }
            }
        }
        return materials
    }

    fun parseObj(
        objStream: InputStream,
        materials: Map<String, FloatArray>,
        preserveWorldScale: Boolean = true,
        alignBottomToZero: Boolean = false
    ): MeshData {
        val rawVerts = ArrayList<FloatArray>()
        val triIndices = ArrayList<IntArray>()
        val triColors = ArrayList<FloatArray>()

        var activeColor = defaultColor

        BufferedReader(InputStreamReader(objStream)).useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0]) {
                    "v" -> if (parts.size >= 4) {
                        val x = parts[1].toFloatOrNull() ?: 0f
                        val y = parts[2].toFloatOrNull() ?: 0f
                        val z = parts[3].toFloatOrNull() ?: 0f
                        rawVerts.add(floatArrayOf(x, y, z))
                    }
                    "usemtl" -> if (parts.size >= 2) {
                        val matName = line.substringAfter("usemtl").trim()
                        activeColor = materials[matName] ?: defaultColor
                    }
                    "f" -> if (parts.size >= 4) {
                        val faceVerts = IntArray(parts.size - 1)
                        var valid = true
                        for (i in 1 until parts.size) {
                            val token = parts[i].substringBefore("/")
                            val rawIdx = token.toIntOrNull()
                            if (rawIdx == null || rawIdx == 0) {
                                valid = false
                                break
                            }
                            val idx = if (rawIdx > 0) rawIdx - 1 else rawVerts.size + rawIdx
                            if (idx < 0 || idx >= rawVerts.size) {
                                valid = false
                                break
                            }
                            faceVerts[i - 1] = idx
                        }
                        if (valid) {
                            for (i in 1 until faceVerts.size - 1) {
                                triIndices.add(intArrayOf(faceVerts[0], faceVerts[i], faceVerts[i + 1]))
                                triColors.add(activeColor)
                            }
                        }
                    }
                }
            }
        }

        if (rawVerts.isEmpty() || triIndices.isEmpty()) {
            return MeshData(FloatArray(0), FloatArray(0), FloatArray(0), 0, 2.2f, 2f, 2f, 2f)
        }

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY

        for (v in rawVerts) {
            if (v[0] < minX) minX = v[0]
            if (v[0] > maxX) maxX = v[0]
            if (v[1] < minY) minY = v[1]
            if (v[1] > maxY) maxY = v[1]
            if (v[2] < minZ) minZ = v[2]
            if (v[2] > maxZ) maxZ = v[2]
        }

        val cx = (minX + maxX) * 0.5f
        val cy = if (alignBottomToZero) minY else (minY + maxY) * 0.5f
        val cz = (minZ + maxZ) * 0.5f
        val extent = max(maxX - minX, max(maxY - minY, maxZ - minZ))
        val scale = if (preserveWorldScale) {
            1.0f
        } else {
            if (extent > 1e-5f) 2.2f / extent else 1.0f
        }
        val finalExtent = (extent * scale).coerceAtLeast(1.2f)
        val widthX = ((maxX - minX) * scale).coerceAtLeast(0.5f)
        val heightY = ((maxY - minY) * scale).coerceAtLeast(0.5f)
        val depthZ = ((maxZ - minZ) * scale).coerceAtLeast(0.5f)

        val vertexCount = triIndices.size * 3
        val positions = FloatArray(vertexCount * 3)
        val normals = FloatArray(vertexCount * 3)
        val colors = FloatArray(vertexCount * 3)

        var pOffset = 0
        var nOffset = 0
        var cOffset = 0

        for (t in triIndices.indices) {
            val tri = triIndices[t]
            val col = triColors[t]
            val v0 = rawVerts[tri[0]]
            val v1 = rawVerts[tri[1]]
            val v2 = rawVerts[tri[2]]

            val p0x = (v0[0] - cx) * scale
            val p0y = (v0[1] - cy) * scale
            val p0z = (v0[2] - cz) * scale

            val p1x = (v1[0] - cx) * scale
            val p1y = (v1[1] - cy) * scale
            val p1z = (v1[2] - cz) * scale

            val p2x = (v2[0] - cx) * scale
            val p2y = (v2[1] - cy) * scale
            val p2z = (v2[2] - cz) * scale

            val ux = p1x - p0x
            val uy = p1y - p0y
            val uz = p1z - p0z
            val vx = p2x - p0x
            val vy = p2y - p0y
            val vz = p2z - p0z

            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-6f) {
                nx /= len
                ny /= len
                nz /= len
            } else {
                nx = 0f; ny = 1f; nz = 0f
            }

            positions[pOffset++] = p0x; positions[pOffset++] = p0y; positions[pOffset++] = p0z
            positions[pOffset++] = p1x; positions[pOffset++] = p1y; positions[pOffset++] = p1z
            positions[pOffset++] = p2x; positions[pOffset++] = p2y; positions[pOffset++] = p2z

            for (k in 0 until 3) {
                normals[nOffset++] = nx; normals[nOffset++] = ny; normals[nOffset++] = nz
                colors[cOffset++] = col[0]; colors[cOffset++] = col[1]; colors[cOffset++] = col[2]
            }
        }

        return MeshData(positions, normals, colors, vertexCount, finalExtent, widthX, depthZ, heightY)
    }
}
