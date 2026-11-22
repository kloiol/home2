package com.houseviewer.app.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader

class ModelRepository(private val context: Context) {

    private val storageDir: File = File(context.filesDir, "stored_models").apply {
        if (!exists()) mkdirs()
    }

    private val builtInModels: List<HouseModel> by lazy {
        loadBuiltInManifest()
    }

    private fun loadBuiltInManifest(): List<HouseModel> {
        val list = mutableListOf<HouseModel>()
        runCatching {
            context.assets.open("models/manifest.txt").use { stream ->
                BufferedReader(InputStreamReader(stream)).useLines { lines ->
                    for (raw in lines) {
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) continue
                        val parts = line.split("|")
                        if (parts.size >= 4) {
                            list.add(
                                HouseModel(
                                    id = parts[0],
                                    displayName = parts[1],
                                    objPath = parts[2],
                                    mtlPath = parts[3].ifBlank { null },
                                    isBuiltIn = true
                                )
                            )
                        }
                    }
                }
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
                .ifEmpty { "Imported House" }
            HouseModel(
                id = "custom_${file.name}",
                displayName = cleanName,
                objPath = file.absolutePath,
                mtlPath = if (mtlFile.exists()) mtlFile.absolutePath else null,
                isBuiltIn = false
            )
        }

        return builtInModels + customModels
    }

    fun loadMesh(model: HouseModel): MeshData {
        return if (model.isBuiltIn) {
            val materials = model.mtlPath?.let { path ->
                runCatching { context.assets.open(path).use { ObjParser.parseMaterials(it) } }
                    .getOrDefault(emptyMap())
            } ?: emptyMap()
            context.assets.open(model.objPath).use { ObjParser.parseObj(it, materials) }
        } else {
            val materials = model.mtlPath?.let { path ->
                runCatching { FileInputStream(path).use { ObjParser.parseMaterials(it) } }
                    .getOrDefault(emptyMap())
            } ?: emptyMap()
            FileInputStream(model.objPath).use { ObjParser.parseObj(it, materials) }
        }
    }

    fun importObjFromUri(uri: Uri, customTitle: String? = null): HouseModel {
        val rawName = customTitle?.takeIf { it.isNotBlank() } ?: resolveFileName(uri)
        val safeBase = rawName
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .ifEmpty { "House_${System.currentTimeMillis()}" }

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
            objPath = targetFile.absolutePath,
            mtlPath = null,
            isBuiltIn = false
        )
    }

    fun deleteCustomModel(model: HouseModel): Boolean {
        if (model.isBuiltIn) return false
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
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Imported_House.obj"
    }
}
