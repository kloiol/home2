package com.houseviewer.app.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

class ModelRepository(private val context: Context) {

    private val storageDir: File = File(context.filesDir, "stored_models").apply {
        if (!exists()) mkdirs()
    }

    private val cityMeshCache = ConcurrentHashMap<String, MeshData>()

    private val bundleEntries: Map<String, ByteArray> by lazy {
        val map = HashMap<String, ByteArray>()
        runCatching {
            context.assets.open("models_bundle.zip").use { raw ->
                ZipInputStream(raw).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            map[entry.name] = zis.readBytes()
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }
        map
    }

    private val builtInModels: List<HouseModel> by lazy {
        loadBuiltInManifest()
    }

    private fun loadBuiltInManifest(): List<HouseModel> {
        val list = mutableListOf<HouseModel>()
        val manifestBytes = bundleEntries["manifest.txt"] ?: return list
        val text = manifestBytes.toString(Charsets.UTF_8)
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split("|")
            if (parts.size >= 5) {
                list.add(
                    HouseModel(
                        id = parts[0],
                        displayName = parts[1],
                        category = parts[2].lowercase(),
                        objPath = parts[3],
                        mtlPath = parts[4].ifBlank { null },
                        isBuiltIn = true
                    )
                )
            } else if (parts.size == 4) {
                list.add(
                    HouseModel(
                        id = parts[0],
                        displayName = parts[1],
                        category = "house",
                        objPath = parts[2],
                        mtlPath = parts[3].ifBlank { null },
                        isBuiltIn = true
                    )
                )
            }
        }
        return list
    }

    fun listModels(): List<HouseModel> {
        val customFiles = storageDir.listFiles { file ->
            file.isFile && file.extension.equals("obj", ignoreCase = true)
        }?.sortedBy { it.lastModified() } ?: emptyList()

        val customModels = customFiles.map { file ->
            val mtlFile = File(storageDir, "${file.nameWithoutExtension}.mtl")
            val cleanName = file.nameWithoutExtension
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
                .ifEmpty { "Imported Model" }
            HouseModel(
                id = "custom_${file.name}",
                displayName = cleanName,
                category = "custom",
                objPath = file.absolutePath,
                mtlPath = if (mtlFile.exists()) mtlFile.absolutePath else null,
                isBuiltIn = false
            )
        }

        return builtInModels + customModels
    }

    fun loadMesh(model: HouseModel): MeshData {
        return loadMeshInternal(model, alignBottomToZero = false)
    }

    fun loadCityMesh(model: HouseModel): MeshData {
        return cityMeshCache.getOrPut(model.id) {
            loadMeshInternal(model, alignBottomToZero = true)
        }
    }

    private fun loadMeshInternal(model: HouseModel, alignBottomToZero: Boolean): MeshData {
        return if (model.isBuiltIn) {
            val materials = model.mtlPath?.let { path ->
                bundleEntries[path]?.let { bytes ->
                    ByteArrayInputStream(bytes).use { ObjParser.parseMaterials(it) }
                }
            } ?: emptyMap()
            val objBytes = bundleEntries[model.objPath]
                ?: throw IllegalStateException("Missing bundled model: ${model.objPath}")
            ByteArrayInputStream(objBytes).use {
                ObjParser.parseObj(it, materials, preserveWorldScale = true, alignBottomToZero = alignBottomToZero)
            }
        } else {
            val materials = model.mtlPath?.let { path ->
                runCatching { FileInputStream(path).use { ObjParser.parseMaterials(it) } }
                    .getOrDefault(emptyMap())
            } ?: emptyMap()
            FileInputStream(model.objPath).use {
                ObjParser.parseObj(it, materials, preserveWorldScale = false, alignBottomToZero = alignBottomToZero)
            }
        }
    }

    fun importObjFromUri(uri: Uri, customTitle: String? = null): HouseModel {
        val rawName = customTitle?.takeIf { it.isNotBlank() } ?: resolveFileName(uri)
        val safeBase = rawName
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .ifEmpty { "Model_${System.currentTimeMillis()}" }

        var targetFile = File(storageDir, "$safeBase.obj")
        var suffix = 2
        while (targetFile.exists()) {
            targetFile = File(storageDir, "${safeBase}_$suffix.obj")
            suffix++
        }

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Unable to read selected file.")

        val displayName = targetFile.nameWithoutExtension.replace('_', ' ')
        return HouseModel(
            id = "custom_${targetFile.name}",
            displayName = displayName,
            category = "custom",
            objPath = targetFile.absolutePath,
            mtlPath = null,
            isBuiltIn = false
        )
    }

    fun deleteCustomModel(model: HouseModel): Boolean {
        if (model.isBuiltIn) return false
        cityMeshCache.remove(model.id)
        val objFile = File(model.objPath)
        val mtlFile = model.mtlPath?.let { File(it) }
        mtlFile?.delete()
        return objFile.delete()
    }

    private fun resolveFileName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Imported_Model.obj"
    }
}
